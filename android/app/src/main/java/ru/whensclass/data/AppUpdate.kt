package ru.whensclass.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

        Notifications.newVersion(
            context,
            "Вышла версия ${release.versionName}",
            release.notes.ifBlank { "Откройте настройки, чтобы установить." },
        )
        store.setAnnouncedVersion(release.versionCode)
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
        val file = File(dir(), name(release))
        if (!ready(file, release)) {
            // Недокачанный файл раньше считался готовым: проверки было ровно
            // «существует и не пустой». Установщик получал обрезанный APK и
            // говорил «Не удалось выполнить синтаксический анализ пакета» —
            // на файле, который просто не дошёл.
            file.delete()
            val written = runCatching { api.downloadTo(release.url, file) }
                .getOrElse { error ->
                    file.delete()
                    return@withContext Result.Failed(reason(error))
                }
            if (!ready(file, release)) {
                file.delete()
                return@withContext Result.Failed(
                    "файл дошёл не целиком: $written из ${release.size} байт",
                )
            }
        }
        sweep(file)
        install(file)
        Result.Started
    }

    private fun reason(error: Throwable): String = when (error) {
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
