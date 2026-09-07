package ru.whensclass.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import ru.whensclass.AppContainer
import ru.whensclass.data.GroupDto
import ru.whensclass.data.ReleaseDto
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.ThemeChoice
import ru.whensclass.work.SyncWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Человек открыл приложение — самое время сходить за свежим расписанием.
        SyncWorker.now(this)

        // Виджет мог попросить открыть конкретный день.
        val day = intent?.getStringExtra(EXTRA_DAY)
        setContent { App(startDay = day) }
    }

    companion object {
        const val EXTRA_DAY = "day"
    }
}

// Приветствие показывается один раз. Поставить true, чтобы обкатать его текст,
// не переустанавливая приложение.
private const val ALWAYS_SHOW_WELCOME = false

/** Экраны приложения. Их четыре, поэтому обходимся без библиотеки навигации. */
// Порядок важен: по нему считается, куда «едет» экран при переходе.
private enum class Screen { WELCOME, GROUPS, TODAY, SETTINGS }

/** Красный колледжа — из его же логотипа. */
private val BRAND = Color(0xFFD60403)
private val BRAND_LIGHT = Color(0xFFFF6B70)

/** Тёмная схема в тон виджету: чистый чёрный не светится на OLED. */
private val DarkScheme = darkColorScheme(
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF9AA0A6),
    primary = BRAND_LIGHT,
    onPrimary = Color(0xFF000000),
    error = BRAND_LIGHT,
)

private val LightScheme = lightColorScheme(
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF16181B),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF16181B),
    surfaceVariant = Color(0xFFECEEF1),
    onSurfaceVariant = Color(0xFF5C6672),
    primary = BRAND,
    onPrimary = Color(0xFFFFFFFF),
    error = BRAND,
)

@Composable
private fun App(startDay: String? = null) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val scope = rememberCoroutineScope()

    val groupName by container.store.groupName.collectAsState(initial = null)
    val schedule by container.repository.schedule.collectAsState(initial = null)
    val fetchedAt by container.repository.fetchedAt.collectAsState(initial = 0L)
    val storedTheme by container.store.theme.collectAsState(initial = "system")
    val welcomeSeen by container.store.welcomeSeen.collectAsState(initial = true)
    val notifyBefore by container.store.notifyBefore.collectAsState(initial = 0)
    val notifyChanges by container.store.notifyChanges.collectAsState(initial = true)
    val pinnedTeachers by container.store.pinnedTeachers.collectAsState(initial = emptyList())
    val teacherMode by container.store.isTeacher.collectAsState(initial = false)
    val teacherName by container.store.teacherName.collectAsState(initial = null)
    val teacherId by container.store.teacherId.collectAsState(initial = null)
    val pinnedGroups by container.store.pinnedGroups.collectAsState(initial = emptyList())
    var groups by remember { mutableStateOf<List<GroupDto>?>(null) }
    LaunchedEffect(Unit) { groups = container.repository.groups() }
    val theme = ThemeChoice.from(storedTheme)

    var screen by remember { mutableStateOf(Screen.TODAY) }
    var update by remember { mutableStateOf<ReleaseDto?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateChecked by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var focusUpdate by remember { mutableStateOf(false) }
    // Список преподавателей грузим один раз за запуск и держим здесь: если
    // держать его во вкладке, он перезагружается на каждое переключение.
    var teachers by remember { mutableStateOf<List<GroupDto>?>(null) }
    LaunchedEffect(Unit) { teachers = container.repository.teachers() }

    var reloadKey by remember { mutableStateOf(0) }
    // Какой список показывать на экране выбора: null — по текущей роли.
    var pickTeacher by remember { mutableStateOf<Boolean?>(null) }

    val refreshNow: () -> Unit = {
        scope.launch {
            refreshing = true
            reloadKey++
            // Напрямую, без WorkManager: он вправе отложить задачу на минуты,
            // а человек только что нажал кнопку и ждёт ответа сейчас.
            container.repository.refresh(force = true)
            refreshing = false
        }
    }

    // Проверяем обновление один раз при запуске: чаще незачем, сборки выходят
    // не по расписанию.
    LaunchedEffect(Unit) { update = container.updates.check() }

    // И сразу забираем свежее расписание: после установки новой версии старые
    // данные на экране выглядят как поломка.
    LaunchedEffect(Unit) { container.repository.refresh() }
    var welcomeDone by remember { mutableStateOf(false) }
    // Кого показываем — зависит от роли: группу или самого преподавателя.
    val chosenName = if (teacherMode) teacherName else groupName
    val current = when {
        ALWAYS_SHOW_WELCOME && !welcomeDone -> Screen.WELCOME
        !welcomeSeen -> Screen.WELCOME
        chosenName == null -> Screen.GROUPS
        else -> screen
    }

    val dark = when (theme) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // Экраны сменяются со сдвигом: вглубь — справа налево, назад —
            // наоборот. Резкая подмена читалась как подвисание.
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val shift = if (forward) 1 else -1
                    (slideInHorizontally(tween(220)) { (it * shift) / 6 } + fadeIn(tween(180)))
                        .togetherWith(
                            slideOutHorizontally(tween(220)) { (-it * shift) / 6 } +
                                fadeOut(tween(140))
                        )
                },
                label = "screen",
            ) { target ->
                when (target) {
                    Screen.WELCOME -> WelcomeScreen(
                        onContinue = {
                            welcomeDone = true
                            scope.launch { container.store.markWelcomeSeen() }
                        },
                    )

                    Screen.GROUPS -> if (pickTeacher ?: teacherMode) {
                        // Список преподавателей: человек выбирает себя. Роль
                        // меняется вместе с выбором, а не до него — иначе на
                        // мгновение показывается чужое расписание.
                        SelfPickerScreen(
                            teachers = teachers,
                            canGoBack = chosenName != null,
                            onBack = {
                                pickTeacher = null
                                screen = Screen.SETTINGS
                            },
                            onStudentMode = { pickTeacher = false },
                            onPick = { teacher ->
                                scope.launch {
                                    container.repository.selectSelfAsTeacher(teacher)
                                    pickTeacher = null
                                    screen = Screen.TODAY
                                }
                            },
                        )
                    } else {
                        GroupPickerScreen(
                            loadGroups = { container.repository.groups() },
                            canGoBack = chosenName != null,
                            onBack = {
                                pickTeacher = null
                                screen = Screen.SETTINGS
                            },
                            onTeacherMode = { pickTeacher = true },
                            onPick = { group: GroupDto ->
                                scope.launch {
                                    container.repository.selectGroup(group)
                                    pickTeacher = null
                                    screen = Screen.TODAY
                                }
                            },
                        )
                    }

                    Screen.SETTINGS -> SettingsScreen(
                        groupName = chosenName,
                        teacherMode = teacherMode,
                        onSwitchRole = {
                            pickTeacher = !teacherMode
                            screen = Screen.GROUPS
                        },
                        theme = theme,
                        update = update,
                        installing = installing,
                        notifyBefore = notifyBefore,
                        notifyChanges = notifyChanges,
                        onNotifyBefore = { minutes ->
                            scope.launch {
                                container.store.setNotifyBefore(minutes)
                                LessonAlarms.reschedule(context)
                            }
                        },
                        onNotifyChanges = { on ->
                            scope.launch { container.store.setNotifyChanges(on) }
                        },
                        focusUpdate = focusUpdate,
                        checkingUpdate = checkingUpdate,
                        updateChecked = updateChecked,
                        onCheckUpdate = {
                            scope.launch {
                                checkingUpdate = true
                                update = container.updates.check()
                                checkingUpdate = false
                                updateChecked = true
                            }
                        },
                        onUpdate = {
                            val release = update
                            if (release != null) {
                                scope.launch {
                                    installing = true
                                    container.updates.downloadAndInstall(release)
                                    installing = false
                                }
                            }
                        },
                        onTheme = { choice ->
                            scope.launch {
                                container.store.setTheme(ThemeChoice.toStored(choice))
                                // Виджет обязан перекраситься сразу, а не через
                                // час при очередном обновлении.
                                ScheduleWidget().updateAll(context)
                                NextLessonWidget().updateAll(context)
                            }
                        },
                        onChangeGroup = { screen = Screen.GROUPS },
                        onRefresh = refreshNow,
                        onBack = { screen = Screen.TODAY },
                    )

                    Screen.TODAY -> TodayScreen(
                        startDay = startDay,
                        groupName = chosenName.orEmpty(),
                        teacherMode = teacherMode,
                        teachers = teachers,
                        loadTeacherSchedule = { container.repository.teacherSchedule(it) },
                        loadGroupSchedule = { container.repository.groupSchedule(it) },
                        groups = groups,
                        pinnedGroups = pinnedGroups,
                        onTogglePinnedGroup = { id ->
                            scope.launch { container.store.togglePinnedGroup(id) }
                        },
                        selfTeacherId = teacherId,
                        pinnedTeachers = pinnedTeachers,
                        onTogglePinnedTeacher = { id ->
                            scope.launch { container.store.togglePinnedTeacher(id) }
                        },
                        schedule = schedule,
                        fetchedAt = fetchedAt,
                        hasUpdate = update != null,
                        refreshing = refreshing,
                        reloadKey = reloadKey,
                        onSettings = {
                            focusUpdate = false
                            screen = Screen.SETTINGS
                        },
                        onUpdateBadge = {
                            focusUpdate = true
                            screen = Screen.SETTINGS
                        },
                        onRefresh = refreshNow,
                    )
                }
            }
        }
    }
}
