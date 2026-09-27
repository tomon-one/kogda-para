package ru.whensclass.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

/** Выпуск на GitHub — запасной источник, когда сервер приложения не отвечает. */
@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("assets") val assets: List<GithubAsset> = emptyList(),
)

@Serializable
internal data class GithubAsset(
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
)

private val APK_NAME = Regex("""kogda-para-(\d+)\.apk""")

/**
 * Самая новая сборка среди выпусков на GitHub. Номер сборки — из имени файла
 * (`kogda-para-2.apk`), версия — из тега; выпуск без такого файла не в счёт.
 */
internal fun newestOnGithub(releases: List<GithubRelease>): ReleaseDto? = releases
    .filterNot { it.draft }
    .mapNotNull { release ->
        release.assets.firstNotNullOfOrNull { asset ->
            APK_NAME.matchEntire(asset.name)?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
                ReleaseDto(versionCode = code, versionName = release.tag, url = asset.url, size = asset.size)
            }
        }
    }
    .maxByOrNull { it.versionCode }

private val VERSION_NAME = Regex("""(b|pr|r)-\p{L}+\.(\d+)\.(\d+)\.(\d+)""")

/**
 * Сборка прежнего счёта. До 27.09.2026 номера дошли до 86-го, а имена были
 * «0.x», «b-…» и «pr-Зерно.0.10.x»; потом счёт начат заново. Прежние сборки
 * подписаны тем же ключом, и с номером больше нынешнего Android поставил бы
 * такую поверх — после этого новые сборки для телефона «старее», и
 * обновления встали бы навсегда. Имя версии зашито в подписанный файл, его не
 * подделать: по нему прежние и отсекаются.
 */
internal fun olderNumbering(versionName: String?): Boolean {
    val match = VERSION_NAME.matchEntire(versionName ?: return true) ?: return true
    val (prefix, major, minor) = match.destructured
    return major.toInt() == 0 && (prefix == "b" || minor.toInt() >= 10)
}

/**
 * Объявлять ли сборку. Про одну и ту же — один раз, но «уже объявлено»
 * сверяется на равенство, а не «не меньше»: номер приходит с сервера без
 * проверки, и один ответ с versionCode 2147483647 навсегда глушил объявления
 * настоящих сборок.
 */
internal fun shouldAnnounce(release: Int, installed: Int, announced: Int): Boolean =
    release > installed && release != announced

/**
 * Годится ли подпись скачанного файла. Нынешний сертификат установленного
 * приложения должен быть среди сертификатов файла — так судит и сам Android.
 * У сборки с ротацией ключа в файле история [старый, новый], и прежняя
 * сверка «история файла внутри истории установленного» отвергала её, хотя
 * установщик бы принял. Подделать историю без
 * нашего ключа нельзя.
 */
internal fun signedAlike(installedCurrent: Set<String>, archiveAll: Set<String>): Boolean =
    installedCurrent.isNotEmpty() && archiveAll.containsAll(installedCurrent)

/**
 * Почему загрузка не удалась — словами человека. Раньше на всё, кроме пары
 * случаев, показывался сырой английский текст Java: «write failed: ENOSPC»,
 * «unexpected end of stream», «Trust anchor for certification path not
 * found».
 */
internal fun updateFailure(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        // 429 — предел скачиваний с одного адреса в nginx; за общим адресом
        // оператора или Wi-Fi колледжа в день раздачи он реален.
        error is HttpFailure && (error.code == 429 || error.code == 503) ->
            "сервер занят, попробуйте через минуту"
        error is HttpFailure -> "сервер ответил ${error.code}"
        error is TooLarge -> "файл больше объявленного — загрузка остановлена"
        chain.any { it.message.orEmpty().let { m -> "ENOSPC" in m || "No space left" in m } } ->
            "на телефоне не хватает места"
        error is java.net.UnknownHostException -> "нет связи с сервером"
        // SocketTimeoutException — частный случай; общий срок загрузки OkHttp
        // бросает голым InterruptedIOException("timeout").
        error is java.io.InterruptedIOException -> "сервер не ответил вовремя"
        chain.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException } ->
            // «Сертификат» новичку ничего не говорит (разбор текстов 27.09).
            "эта сеть не пускает к серверу — возможно, Wi-Fi ждёт входа на своей странице. " +
                "Попробуйте через мобильную сеть"
        error is java.io.IOException -> "связь оборвалась, попробуйте ещё раз"
        // Показывается как «Не удалось: …» — не «не удалось скачать» второй раз.
        else -> "причина неизвестна"
    }
}

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
    // видел «файл дошёл не целиком: 0 из …», хотя всё скачалось. AppUpdate один на приложение (AppContainer).
    private val downloading = Mutex()

    private companion object {
        /** Куда кладём скачанное. Внутренняя память: снаружи туда не залезть. */
        const val DIR = "updates"
        const val APK = "application/vnd.android.package-archive"
        /** Больше этого сборка не бывает: она весит около 2,5 МБ. */
        const val MAX_SIZE = 64L * 1024 * 1024
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
        // Сервер не ответил — спросить выпуски на GitHub: сборка с починкой
        // или новым адресом сервера должна дойти и тогда, когда сервер лежит.
        val release = runCatching { api.release() }.getOrNull()
            ?: runCatching { api.githubRelease() }.getOrNull()
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
        if (!shouldAnnounce(release.versionCode, BuildConfig.VERSION_CODE, store.announcedVersion())) return

        val shown = Notifications.newVersion(
            context,
            "Вышла версия ${release.versionName}",
            release.notes.ifBlank { "Откройте настройки, чтобы установить." },
        )
        // Засчитываем, только если система уведомление приняла: при
        // запрещённых уведомлениях сборка раньше считалась объявленной, и
        // после выдачи разрешения о ней больше не говорили.
        if (shown) store.setAnnouncedVersion(release.versionCode)
    }

    /** Чем кончилась попытка обновиться. */
    sealed interface Result {
        /**
         * Файл скачан и проверен; [intent] открывает системный установщик.
         * Открывает экран, а не мы: из фона Android 10+ запуск молча
         * отбрасывал, если человек ушёл из приложения, пока шла загрузка.
         */
        data class Ready(val intent: Intent) : Result
        data class Failed(val why: String) : Result
    }

    /** Загрузка идёт; null — не идёт. Иначе — чем кончилась, пока экран не забрал. */
    sealed interface Download {
        data object Running : Download
        data class Done(val result: Result) : Download
    }

    // Загрузка живёт здесь, а не в экране: поворот пересоздавал экран, кнопка
    // снова звала «Обновить приложение», хотя загрузка шла, и второе нажатие
    // открывало установщик дважды.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download.asStateFlow()

    /**
     * Скачивает обновление и готовит установщик, когда файл готов. Итог —
     * в [download]; экран забирает его через [taken].
     *
     * Раньше загрузка и установка были двумя нажатиями: первое ставило файл в
     * очередь, второе — открывало установщик. Со стороны это выглядело как
     * кнопка, срабатывающая через раз.
     */
    fun start(release: ReleaseDto) {
        if (_download.value == Download.Running) return
        _download.value = Download.Running
        scope.launch {
            val result = runCatching { downloading.withLock { fetchWithFallback(release) } }
                .getOrElse { Result.Failed(updateFailure(it)) }
            _download.value = Download.Done(result)
        }
    }

    /**
     * Не вышло с сервера — та же сборка с GitHub. Только та же: другой номер
     * человек не выбирал. Не вышло и там — показываем первую причину.
     */
    private suspend fun fetchWithFallback(release: ReleaseDto): Result {
        val first = runCatching { fetchAndInstall(release) }.getOrElse { Result.Failed(updateFailure(it)) }
        if (first !is Result.Failed) return first
        val mirror = runCatching { api.githubRelease() }.getOrNull()
            ?.takeIf { it.versionCode == release.versionCode && it.url != release.url }
            ?: return first
        val second = runCatching { fetchAndInstall(mirror) }.getOrElse { Result.Failed(updateFailure(it)) }
        return if (second is Result.Failed) first else second
    }

    /** Экран показал итог загрузки или открыл установщик. */
    fun taken() {
        if (_download.value is Download.Done) _download.value = null
    }

    private suspend fun fetchAndInstall(release: ReleaseDto): Result {
        if (release.size <= 0 || release.size > MAX_SIZE) {
            return Result.Failed("сервер не сообщил размер сборки")
        }
        if (!api.isOurs(release.url)) {
            return Result.Failed("адрес файла — не сервер приложения и не GitHub")
        }
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
            val written = runCatching { api.downloadTo(release.url, partial, release.size) }
                .getOrElse { error ->
                    partial.delete()
                    return Result.Failed(updateFailure(error))
                }
            if (!ready(partial, release) || !partial.renameTo(file)) {
                partial.delete()
                return Result.Failed("файл дошёл не целиком: $written из ${release.size} байт")
            }
        }
        // Размер — не подлинность. Раньше скачанное уходило установщику как
        // есть: APK другого пакета Android поставил бы рядом как новое
        // приложение, и обещание «подсунуть поддельное обновление нельзя»
        // держалось только для подмены нашего пакета.
        genuine(file, release)?.let { why ->
            file.delete()
            return Result.Failed(why)
        }
        sweep(file)
        return Result.Ready(installIntent(file))
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
        if (olderNumbering(archive.versionName)) {
            return "это сборка прежнего счёта (${archive.versionName}) — ставить её нельзя"
        }
        val mine = certificates(runCatching { installedInfo(pm) }.getOrNull(), currentOnly = true)
        val theirs = certificates(archive, currentOnly = false)
        if (!signedAlike(mine, theirs)) {
            // Что делать — прямо здесь: install.md советовал на отказ
            // установщика «удалить и поставить заново», а текст обновления с
            // сервера стоял рядом — тот, кто завладел сервером, этим уводил
            // людей на свою сборку.
            return "подпись файла не та. Не удаляйте приложение ради этого " +
                "обновления и не ставьте файл по ссылкам из его текста — напишите автору в " +
                "Telegram: @toomonn"
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

    /**
     * Отпечатки сертификатов. [currentOnly] — только нынешний: история
     * ротированного ключа идёт от старого к новому, нынешний последний.
     */
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo?, currentOnly: Boolean): Set<String> {
        if (info == null) return emptySet()
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            val signing = info.signingInfo!!
            when {
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                currentOnly -> signing.signingCertificateHistory?.takeLast(1)?.toTypedArray()
                else -> signing.signingCertificateHistory
            }
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

    /**
     * Намерение для системного установщика — явное. Неявное ACTION_VIEW с
     * правом чтения получало любое приложение с фильтром на APK — однажды
     * выбранное «Всегда» для файлов из мессенджера, — и то могло изобразить
     * установку. Системного не нашлось —
     * остаётся неявное: без установщика не обновиться вовсе.
     */
    private fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY)
            .map { it.activityInfo }
            .firstOrNull { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            ?.let { intent.setClassName(it.packageName, it.name) }
        return intent
    }
}
