package ru.whensclass.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.whensclass.BuildConfig
import ru.whensclass.notify.Notifications

/** Что лежит на сервере — приложение раздаётся файлом, а не через магазин. */
@Serializable
data class ReleaseDto(
    @SerialName("versionCode") val versionCode: Int,
    @SerialName("versionName") val versionName: String,
    @SerialName("url") val url: String,
    @SerialName("size") val size: Long = 0,
    @SerialName("notes") val notes: String = "",
)

/**
 * Проверка и установка обновлений.
 *
 * Полностью автоматически поставить обновление нельзя: Android всегда
 * спрашивает у человека подтверждение на установку файла. Поэтому здесь
 * автоматическое — только проверка и загрузка, а последний шаг открывает
 * системный установщик.
 */
class AppUpdate(private val context: Context, private val api: ScheduleApi) {

    // Одна загрузка за раз. Повторное нажатие после поворота экрана запускало
    // вторую загрузку в тот же файл, они стирали друг другу байты, и человек
    // видел «файл дошёл не целиком: 0 из …», хотя всё скачалось (второй
    // аудит, М42). AppUpdate один на приложение (AppContainer).
    private val downloading = Mutex()

    private companion object {
        /** Куда кладём скачанное. Внутренняя память: снаружи туда не залезть. */
        const val DIR = "updates"
    }

    /**
     * Что известно о новой сборке.
     *
     * Раньше проверка возвращала null и на «обновлений нет», и на «не дозвонился
     * до сервера», а настройки в обоих случаях писали «Установлена последняя
     * версия». Магазина нет, узнать об исправлении больше неоткуда: приложение
     * не должно утверждать про версию то, чего оно не знает.
     */
    sealed interface Check {
        data class Available(val release: ReleaseDto) : Check
        data object UpToDate : Check
        data object Failed : Check
    }

    suspend fun check(): Check = withContext(Dispatchers.IO) {
        val release = runCatching { api.release() }.getOrNull()
            ?: return@withContext Check.Failed
        if (release.versionCode > BuildConfig.VERSION_CODE) {
            Check.Available(release)
        } else {
            Check.UpToDate
        }
    }

    /**
     * Сказать о новой сборке, если она вышла.
     *
     * Зовётся из фонового обновления расписания: приложение раздаётся файлом,
     * и узнать о новой версии человеку больше неоткуда. Про одну и ту же
     * сборку говорим один раз — иначе это будет ежечасное напоминание.
     */
    suspend fun announceIfNew(store: ScheduleStore) {
        if (!store.notifyUpdatesEnabled()) return
        val release = (check() as? Check.Available)?.release ?: return
        if (store.announcedVersion() >= release.versionCode) return

        val shown = Notifications.newVersion(
            context,
            "Вышла версия ${release.versionName}",
            release.notes.ifBlank { "Откройте настройки, чтобы установить." },
        )
        // Засчитываем, только если система уведомление приняла: при
        // запрещённых уведомлениях сборка раньше считалась объявленной, и
        // после выдачи разрешения о ней больше не говорили (второй аудит, М41).
        if (shown) store.setAnnouncedVersion(release.versionCode)
    }

    /** Чем кончилась попытка обновиться. */
    sealed interface Result {
        data object Started : Result
        data class Failed(val why: String) : Result
    }

    /**
     * Скачивает обновление и открывает установщик, когда файл готов.
     *
     * Раньше загрузка и установка были двумя нажатиями: первое ставило файл в
     * очередь, второе — открывало установщик. Со стороны это выглядело как
     * кнопка, срабатывающая через раз.
     */
    suspend fun downloadAndInstall(release: ReleaseDto): Result = withContext(Dispatchers.IO) {
        downloading.withLock { fetchAndInstall(release) }
    }

    private suspend fun fetchAndInstall(release: ReleaseDto): Result {
        val file = File(dir(), name(release))
        if (!ready(file, release)) {
            // Недокачанный файл раньше считался готовым: проверки было ровно
            // «существует и не пустой». Установщик получал обрезанный APK и
            // говорил «Не удалось выполнить синтаксический анализ пакета» —
            // на файле, который просто не дошёл. Качаем во временное имя и
            // переименовываем после сверки размера.
            file.delete()
            val partial = File(dir(), name(release) + ".part")
            partial.delete()
            val written = runCatching { api.downloadTo(release.url, partial) }
                .getOrElse { error ->
                    partial.delete()
                    return Result.Failed(reason(error))
                }
            if (!ready(partial, release) || !partial.renameTo(file)) {
                partial.delete()
                return Result.Failed("файл дошёл не целиком: $written из ${release.size} байт")
            }
        }
        // Размер — не подлинность. Раньше скачанное уходило установщику как
        // есть: APK другого пакета Android поставил бы рядом как новое
        // приложение, и обещание «подсунуть поддельное обновление нельзя»
        // держалось только для подмены нашего пакета (второй аудит, В29).
        genuine(file, release)?.let { why ->
            file.delete()
            return Result.Failed(why)
        }
        sweep(file)
        install(file)
        return Result.Started
    }

    /** null — файл наш: тот же пакет, та же подпись, объявленный номер. Иначе — почему нет. */
    private fun genuine(file: File, release: ReleaseDto): String? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: return "скачанный файл — не приложение"
        if (archive.packageName != context.packageName) {
            return "скачанный файл — другое приложение (${archive.packageName})"
        }
        if (versionCode(archive) != release.versionCode.toLong()) {
            return "в файле сборка ${versionCode(archive)}, а объявлена ${release.versionCode}"
        }
        val mine = certificates(runCatching { installedInfo(pm) }.getOrNull())
        val theirs = certificates(archive)
        if (theirs.isEmpty() || mine.isEmpty() || !mine.containsAll(theirs)) {
            return "подпись скачанного файла не совпадает с установленным приложением"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? {
        // На части версий Android подписи архива приходят только старым флагом.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?.takeIf { it.signingInfo != null }
                ?.let { return it }
        }
        return pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            val signing = info.signingInfo!!
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty()
            .map { sig -> digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }
            .toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun reason(error: Throwable): String = when (error) {
        // 429 — предел одновременных скачиваний с одного адреса в nginx; за
        // общим адресом оператора в день раздачи он реален (второй аудит, М43).
        is HttpFailure -> if (error.code == 429 || error.code == 503) {
            "сервер занят, попробуйте через минуту"
        } else {
            "сервер ответил ${error.code}"
        }
        is java.net.UnknownHostException -> "нет связи с сервером"
        is java.net.SocketTimeoutException -> "сервер не ответил вовремя"
        is java.io.IOException -> error.message ?: "не удалось скачать"
        else -> "не удалось скачать"
    }

    /**
     * Готов ли файл к установке.
     *
     * Сервер присылает размер сборки — сверяем с ним. Без этой сверки годным
     * считался любой непустой файл, и оборванная загрузка навсегда занимала
     * место «уже скачанного обновления».
     */
    private fun ready(file: File, release: ReleaseDto): Boolean =
        file.exists() && release.size > 0 && file.length() == release.size

    private fun dir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    // Имя по номеру сборки, а не по имени версии: имя человеку показывают, а
    // машине оно не годится — две сборки могут называться одинаково.
    private fun name(release: ReleaseDto): String = "kogda-para-${release.versionCode}.apk"

    /** Убирает всё, кроме текущего файла: прошлые сборки копились навсегда. */
    private fun sweep(keep: File) {
        dir().listFiles()?.forEach { file ->
            if (file != keep) file.delete()
        }
    }

    /** Открывает системный установщик для уже скачанного файла. */
    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.files", file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Уже скачанный и проверенный файл этой версии, если он есть. */
    fun downloaded(release: ReleaseDto): File? =
        File(dir(), name(release)).takeIf { ready(it, release) }
}
