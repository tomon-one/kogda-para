package ru.whensclass.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.whensclass.AppContainer
import ru.whensclass.data.GroupDto
import ru.whensclass.data.RetakesDto
import ru.whensclass.data.followRenamedPins
import ru.whensclass.data.freshGroups
import ru.whensclass.data.freshTeachers
import ru.whensclass.data.cachedGroups
import ru.whensclass.data.cachedTeachers
import ru.whensclass.data.retakes

// То, что экран забирает с сервера помимо расписания: списки групп и преподавателей и лист
// пересдач. Держится отдельно от App.kt: у экранов своих забот хватает.

/** Списки групп и преподавателей: сохранённый — сразу, свежий — следом. */
internal class ListsUi(private val container: AppContainer) {
    var groups by mutableStateOf<List<GroupDto>?>(null)
        private set
    var teachers by mutableStateOf<List<GroupDto>?>(null)
        private set

    /** Растёт на «Повторить» и ⟳: по нему эффект перечитывает списки. */
    var reloadKey by mutableIntStateOf(0)
        private set

    private var fresh = false
    private var retry = false

    /**
     * [picking] — открыт экран выбора: он всегда со свежими списками, группу могли разделить днём.
     */
    suspend fun load(picking: Boolean) {
        val repository = container.repository
        // Сохранённые — сразу, без сети: экран открывается и в метро. Свежие — следом и оба разом.
        if (groups.isNullOrEmpty()) repository.cachedGroups().takeIf { it.isNotEmpty() }?.let { groups = it }
        if (teachers.isNullOrEmpty()) repository.cachedTeachers().takeIf { it.isNotEmpty() }?.let { teachers = it }
        if (fresh && !picking) return
        // Свежие — не чаще LISTS_FRESH_MILLIS, если сохранённые есть, как на сайте; «Повторить» и
        // пустой список — сразу.
        val age = System.currentTimeMillis() - container.store.listsFetchedAt()
        if (!picking && !retry && !groups.isNullOrEmpty() && !teachers.isNullOrEmpty() &&
            age in 0 until LISTS_FRESH_MILLIS
        ) {
            fresh = true
            return
        }
        retry = false
        val (freshGroups, freshTeachers) = coroutineScope {
            val g = async { repository.freshGroups() }
            val t = async { repository.freshTeachers() }
            g.await() to t.await()
        }
        freshGroups?.let { groups = it }
        freshTeachers?.let { teachers = it }
        // Ни свежего, ни сохранённого — «не загрузился», а не вечный спиннер.
        if (groups == null) groups = emptyList()
        if (teachers == null) teachers = emptyList()
        if (freshGroups != null && freshTeachers != null) {
            fresh = true
            container.store.setListsFetchedAt(System.currentTimeMillis())
            repository.followRenamedPins(freshGroups, freshTeachers)
        }
    }

    /** «Повторить» — в сеть, даже если свежие уже приходили. */
    fun retryNow() {
        fresh = false
        retry = true
        reloadKey++
    }

    /** ⟳ на расписании: списки не перезапрашиваются чаще, но пустые — заново. */
    fun reload() {
        reloadKey++
    }
}

/** Лист пересдач: при открытии расписания, не чаще раза в полчаса (служба смотрит его раз в час). */
internal class RetakesUi(private val container: AppContainer) {
    var body by mutableStateOf<RetakesDto?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    /** Не пришёл, хотя сервер ответил ошибкой, — а не «нет связи». */
    var answered by mutableStateOf(false)
        private set

    /** Растёт на «Повторить»: по нему эффект перечитывает лист. */
    var retryKey by mutableIntStateOf(0)
        private set

    private var at by mutableLongStateOf(0L)

    suspend fun load() {
        if (body != null && System.currentTimeMillis() - at < RETAKES_FRESH_MILLIS) return
        failed = false
        val answer = container.repository.retakes()
        val fresh = answer.body
        answered = answer.answered
        if (fresh != null) {
            body = fresh
            at = System.currentTimeMillis()
        } else if (body == null) {
            failed = true
        }
    }

    fun retry() {
        retryKey++
    }
}

@Composable
internal fun rememberListsUi(container: AppContainer, screen: Screen): ListsUi {
    val ui = remember { ListsUi(container) }
    // И на каждое возвращение, пока свежих списков нет: иначе после первого запуска без связи они
    // не перечитались бы.
    LaunchedEffect(screen, ui.reloadKey, ScreenClock.resumes) {
        ui.load(picking = screen == Screen.GROUPS)
    }
    return ui
}

@Composable
internal fun rememberRetakesUi(container: AppContainer, screen: Screen): RetakesUi {
    val ui = remember { RetakesUi(container) }
    LaunchedEffect(screen, ui.retryKey, ScreenClock.resumes) {
        if (screen == Screen.TODAY) ui.load()
    }
    return ui
}
