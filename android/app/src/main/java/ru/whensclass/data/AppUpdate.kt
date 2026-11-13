package ru.whensclass.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
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
import ru.whensclass.AppContainer
import ru.whensclass.BuildConfig
import ru.whensclass.notify.Notifications

/**
 * Проверка и установка обновлений. Проверка и загрузка автоматические, установка по кнопке сессией
 * установщика Android: она возвращает итог, неудача видна в настройках. На Android 12+ своё
 * обновление ставится без окна (USER_ACTION_NOT_REQUIRED).
 */
class AppUpdate(private val context: Context, private val api: ScheduleApi) {

    // Одна загрузка за раз: две в тот же файл стирают друг другу байты.
    private val downloading = Mutex()

    private companion object {
        /** Куда кладём скачанное. Внутренняя память: снаружи туда не залезть. */
        const val DIR = "updates"
        const val APK = "application/vnd.android.package-archive"
        /** Потолок от ошибки сервера, а не размер сборки: она весит около 2,5 МБ. */
        const val MAX_SIZE = 16L * 1024 * 1024
        /** Экран проверяет сборку не чаще раза в полчаса ([checkForScreen]). */
        const val CHECK_EVERY_MILLIS = 30L * 60 * 1000
        /** Не дозвонился — экран спросит снова не раньше чем через столько. */
        const val FAILED_EVERY_MILLIS = 10L * 60 * 1000
        /** Фон спрашивает GitHub не чаще раза в шесть часов ([announceIfNew]). */
        const val GITHUB_EVERY_MILLIS = 6L * 60 * 60 * 1000
    }

    /**
     * Что известно о новой сборке. «Не дозвонился» отдельно от «обновлений нет»: не писать
     * «Установлена последняя версия», не зная этого.
     */
    sealed interface Check {
        data class Available(val release: ReleaseDto) : Check
        data object UpToDate : Check
        data object Failed : Check
    }

    /** Последняя проверка — экрана, кнопки или фона: когда и что ответили. */
    @Volatile private var lastCheck: Pair<Long, Check>? = null

    /** Когда экран сам спрашивал GitHub — не чаще раза в полчаса. */
    @Volatile private var screenGithubAt = 0L

    /**
     * Проверка для экрана: при открытии и возвращении — не чаще [CHECK_EVERY_MILLIS], по
     * уведомлению о версии — всегда. Не пора — прежний ответ.
     */
    suspend fun checkForScreen(force: Boolean): Check {
        val now = System.currentTimeMillis()
        val cached = lastCheck
        // Ответ помнится и от кнопки, и от фона: иначе возврат затёр бы найденную ими сборку.
        // Неудача — десять минут: у GitHub 60 запросов в час на адрес оператора.
        if (!force && cached != null) {
            val fresh = if (cached.second == Check.Failed) FAILED_EVERY_MILLIS else CHECK_EVERY_MILLIS
            if (now - cached.first in 0 until fresh) return cached.second
        }
        val github = force || now - screenGithubAt !in 0 until CHECK_EVERY_MILLIS
        return check(github) { screenGithubAt = now }
    }

    suspend fun check(github: Boolean = true, askedGithub: () -> Unit = {}): Check = withContext(Dispatchers.IO) {
        // Сервер не ответил — спросить выпуски на GitHub: починка или новый адрес сервера должны
        // дойти и когда он лежит.
        val release = runCatching { api.release() }.getOrNull()
            ?: (if (github) { askedGithub(); runCatching { api.githubRelease() }.getOrNull() } else null)
        when {
            release == null -> Check.Failed
            release.versionCode > BuildConfig.VERSION_CODE -> Check.Available(release)
            else -> Check.UpToDate
        }.also { lastCheck = System.currentTimeMillis() to it }
    }

    /** Сказать о новой сборке, если она вышла (про одну — раз). Зовётся из фонового обновления. */
    suspend fun announceIfNew(store: ScheduleStore) {
        if (!store.notifyUpdatesEnabled()) return
        // С GitHub из фона — не чаще раза в шесть часов (лимит 60 запросов в час); экран и кнопка — по своим правилам.
        val now = System.currentTimeMillis()
        val github = now - store.githubCheckedAt() !in 0 until GITHUB_EVERY_MILLIS
        var asked = false
        val release = (check(github) { asked = true } as? Check.Available)?.release
        if (asked) runCatching { store.setGithubCheckedAt(now) }
        if (release == null) return
        if (!shouldAnnounce(release.versionCode, BuildConfig.VERSION_CODE, store.announcedVersion())) return

        val shown = Notifications.newVersion(
            context,
            "Вышла версия ${release.versionName}",
            release.notes.ifBlank { "Откройте настройки, чтобы установить." },
        )
        // Засчитываем, только если система уведомление приняла: иначе после выдачи разрешения о сборке не сказали бы.
        if (shown) store.setAnnouncedVersion(release.versionCode)
    }

    /** Чем кончилась попытка обновиться. */
    sealed interface Result {
        /**
         * Файл скачан и проверен; [intent] открывает системный установщик.
         * Открывает экран: из фона Android 10+ запуск молча отбрасывает.
         */
        data class Ready(val file: File, val versionCode: Int, val intent: Intent) : Result
        /** Сессия установщика не открылась — [intent] открывает его окно, как раньше. */
        data class Manual(val intent: Intent) : Result
        data class Failed(val why: String) : Result
    }

    /** Загрузка или установка идёт; null — не идёт. Иначе — чем кончилась, пока экран не забрал. */
    sealed interface Download {
        data object Running : Download
        data class Done(val result: Result) : Download
    }

    // Загрузка живёт здесь, а не в экране: поворот пересоздаёт экран, и она началась бы второй раз.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download.asStateFlow()

    /**
     * Скачивает обновление и готовит установщик одним нажатием. Итог — в [download]; экран забирает
     * его через [taken].
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
     * Не вышло с сервера — та же сборка с GitHub (другой номер человек не выбирал). Не вышло и там
     * — первая причина.
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
            // Качаем во временное имя и переименовываем после сверки размера: обрезанный APK
            // установщик встречает невнятной ошибкой разбора пакета.
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
        // Размер — не подлинность: APK другого пакета Android поставил бы рядом как новое приложение.
        genuine(file, release)?.let { why ->
            file.delete()
            return Result.Failed(why)
        }
        sweep(file)
        return Result.Ready(file, release.versionCode, installIntent(file))
    }

    /**
     * Отдаёт проверенный файл установщику Android; итог придёт в [InstallResultReceiver]. Сессия не
     * открылась — [Result.Manual]. Здесь, а не в экране: смена [download] перезапускает экран, и
     * установка отменила бы сама себя.
     */
    fun install(ready: Result.Ready) {
        // Сразу: экран, забирая итог ([taken]), не должен стереть неудачу ниже.
        _download.value = Download.Running
        scope.launch {
            runCatching { session(ready) }.onFailure {
                _download.value = Download.Done(Result.Manual(ready.intent))
            }
        }
    }

    private suspend fun session(ready: Result.Ready) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(ready.file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, ready.file.length()).use { out ->
                    ready.file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Изменяемый: установщик дописывает в него статус и окно подтверждения.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val result = Intent(context, InstallResultReceiver::class.java)
                    .putExtra(EXTRA_VERSION, ready.versionCode)
                runCatching { AppContainer.get(context).store.setInstalling(ready.versionCode) }
                session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            throw error
        }
    }

    /** Установщик ждёт подтверждения в своём окне: итог придёт, только если в нём ответят. */
    internal fun awaitingUser() {
        if (_download.value == Download.Running) _download.value = null
    }

    /** Итог от установщика ([InstallResultReceiver]). Удачный приходит уже новой сборке. */
    internal fun installed(status: Int) {
        _download.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> null
            else -> Download.Done(Result.Failed(installFailure(status)))
        }
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
            // Совет «удалить и поставить заново» рядом с текстом обновления с сервера позволил бы
            // завладевшему сервером увести людей на свою сборку.
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
     * Готов ли файл к установке: размер совпал с объявленным сервером. Иначе оборванная загрузка
     * сошла бы за готовую.
     */
    private fun ready(file: File, release: ReleaseDto): Boolean =
        file.exists() && release.size > 0 && file.length() == release.size

    private fun dir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    // Имя по номеру сборки: две сборки могут называться одинаково.
    private fun name(release: ReleaseDto): String = "kogda-para-${release.versionCode}.apk"

    /** Убирает всё, кроме текущего файла, — иначе прошлые сборки копятся. */
    private fun sweep(keep: File) {
        dir().listFiles()?.forEach { file ->
            if (file != keep) file.delete()
        }
    }

    /**
     * Намерение для установщика — явное: неявное ACTION_VIEW с правом чтения получило бы любое
     * приложение с фильтром на APK. Системного нет — неявное: иначе не обновиться.
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

internal const val EXTRA_VERSION = "ru.whensclass.update.VERSION"

/**
 * Итог сессии установщика. Попросил подтверждения — открыть окно: человек только что нажал
 * «Обновить». Остальное — в [AppUpdate].
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val updates = AppContainer.get(context).updates
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            val opened = confirm != null && runCatching {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            // Окно у человека: брошенное оно итога не пришлёт, и «Обновляется…» висело бы до смерти процесса.
            if (opened) updates.awaitingUser() else updates.installed(PackageInstaller.STATUS_FAILURE)
            return
        }
        updates.installed(status)
        // Сборку поставили — об этом скажет уже новая (MidnightReceiver, MY_PACKAGE_REPLACED).
        if (status == PackageInstaller.STATUS_SUCCESS) return
        val code = intent.getIntExtra(EXTRA_VERSION, 0)
        val why = installFailure(status)
        val finish = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { AppContainer.get(context).store.setLastInstall("сборка $code: $why") }
            } finally {
                finish.finish()
            }
        }
    }
}
