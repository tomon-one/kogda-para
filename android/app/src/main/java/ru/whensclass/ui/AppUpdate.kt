package ru.whensclass.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withStateAtLeast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.whensclass.AppContainer
import ru.whensclass.data.AppUpdate
import ru.whensclass.data.ReleaseDto

// Обновление приложения: есть ли новее, качается ли, что сказать, если не вышло.
// Держится отдельно от App.kt: экранов там и без обновления хватает.

/** Что известно про обновление и что с ним можно сделать. */
internal class UpdateUi(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val download: State<AppUpdate.Download?>,
    private val errorState: MutableState<String?>,
    private val focusState: MutableState<Boolean>,
) {
    /** Выложенная сборка новее этой; null — новее нет или не дозвонились. */
    var update by mutableStateOf<ReleaseDto?>(null)
        private set

    /** Отдельно от [update]: «новее нет» и «до сервера не достучались» — разные вещи. */
    var failed by mutableStateOf(false)
        private set

    var checking by mutableStateOf(false)
        private set

    /** Хоть раз проверяли вручную — до этого «проверить» ещё не нажимали. */
    var checked by mutableStateOf(false)
        private set

    /** Скачивается и ставится прямо сейчас. */
    val installing: Boolean get() = download.value == AppUpdate.Download.Running

    /** Почему не удалось скачать: без этого провал молчалив. */
    var error: String?
        get() = errorState.value
        set(value) {
            errorState.value = value
        }

    /** Прокрутить к «Версии» в настройках. */
    var focus: Boolean
        get() = focusState.value
        set(value) {
            focusState.value = value
        }

    /** Проверка по правилам [AppUpdate.checkForScreen]; force — по уведомлению. */
    suspend fun onScreen(force: Boolean) {
        when (val result = container.updates.checkForScreen(force = force)) {
            is AppUpdate.Check.Available -> update = result.release
            AppUpdate.Check.UpToDate -> update = null
            // Не дозвонились — известное прежде не забываем.
            AppUpdate.Check.Failed -> Unit
        }
    }

    /** «Проверить обновление» в настройках — с ответом человеку. */
    fun check() {
        scope.launch {
            checking = true
            error = null
            when (val result = container.updates.check()) {
                is AppUpdate.Check.Available -> {
                    update = result.release
                    failed = false
                }
                AppUpdate.Check.UpToDate -> {
                    update = null
                    failed = false
                }
                AppUpdate.Check.Failed -> failed = true
            }
            checking = false
            checked = true
        }
    }

    /** «Обновить»: заново спросить сервер и качать выложенное сейчас. */
    fun start() {
        scope.launch {
            error = null
            // Запомненная сборка могла уйти с сервера (держатся две последние) — качать выложенную сейчас.
            when (val result = container.updates.check(github = false)) {
                is AppUpdate.Check.Available -> update = result.release
                AppUpdate.Check.UpToDate -> update = null
                AppUpdate.Check.Failed -> Unit
            }
            update?.let { container.updates.start(it) }
        }
    }

    /** Нажали значок обновления, а сборки ещё нет: спросить заново, иначе «Версия» откроется без сборки. */
    fun refreshIfMissing() {
        if (update != null) return
        scope.launch {
            (container.updates.checkForScreen(force = true) as? AppUpdate.Check.Available)
                ?.let { update = it.release }
        }
    }

    /**
     * Итог загрузки. Установщик открывается, когда человек в приложении: из фона Android 10+ его не пускает.
     */
    suspend fun install(done: AppUpdate.Download?) {
        val finished = done as? AppUpdate.Download.Done ?: return
        when (val result = finished.result) {
            is AppUpdate.Result.Failed -> error = result.why
            is AppUpdate.Result.Ready -> container.updates.install(result)
            // Сессия не открылась — прежний путь: окно системного установщика.
            is AppUpdate.Result.Manual -> lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                runCatching { context.startActivity(result.intent) }
                    .onFailure { error = "установщик Android не найден" }
            }
        }
        container.updates.taken()
    }
}

/**
 * Обновление и его проверки: раз при открытии (по уведомлению — на каждое нажатие) и на загрузку.
 */
@Composable
internal fun rememberUpdateUi(
    container: AppContainer,
    scope: CoroutineScope,
    context: Context,
    lifecycle: Lifecycle,
    openUpdate: Boolean,
    openSeq: Int,
): UpdateUi {
    val error = rememberSaveable { mutableStateOf<String?>(null) }
    val focus = rememberSaveable { mutableStateOf(openUpdate) }
    val download = container.updates.download.collectAsState()
    val ui = remember { UpdateUi(container, scope, context, lifecycle, download, error, focus) }

    var forcedOpen by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(openSeq, ScreenClock.resumes) {
        val force = openUpdate && forcedOpen != openSeq
        if (openUpdate) forcedOpen = openSeq
        ui.onScreen(force)
    }
    LaunchedEffect(download.value) { ui.install(download.value) }
    return ui
}
