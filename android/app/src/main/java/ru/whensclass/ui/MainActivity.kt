package ru.whensclass.ui

import android.os.Bundle
import android.os.Build
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import ru.whensclass.AppContainer
import ru.whensclass.data.AppUpdate
import ru.whensclass.data.DEFAULT_NOTIFY_BEFORE
import ru.whensclass.data.GroupDto
import ru.whensclass.data.RefreshResult
import ru.whensclass.data.ReleaseDto
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.WeekWidget
import ru.whensclass.widget.ThemeChoice
import ru.whensclass.work.SyncWorker

class MainActivity : ComponentActivity() {

    // Разрешение на точное время напоминаний выдаётся в настройках телефона,
    // за пределами приложения. Перечитываем его при каждом возвращении, иначе
    // переключатель остаётся выключенным сразу после того, как его включили.
    private val exactAlarms = mutableStateOf(false)

    // Разрешение на сами уведомления. Его тоже могут выдать и отобрать в
    // настройках телефона, поэтому перечитываем при каждом возвращении.
    private val notifications = mutableStateOf(true)

    // Первый onResume идёт сразу за onCreate, где расписание уже запрошено:
    // второй запрос подряд там ни к чему.
    private var started = false

    override fun onResume() {
        super.onResume()
        // «Приложение забирает расписание при каждом открытии» — обещание из
        // настроек. Оба захода за расписанием висели на onCreate, поэтому
        // возврат из фона (список недавних, значок на экране) ничего не
        // запрашивал: Activity жива, onCreate не зовётся.
        if (started) SyncWorker.now(this)
        started = true
        notifications.value = Notifications.allowed(this)
        val allowed = LessonAlarms.exactAllowed(this)
        if (allowed != exactAlarms.value) {
            exactAlarms.value = allowed
            // Разрешение появилось — переставить будильники уже точными.
            LessonAlarms.reschedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Человек открыл приложение — самое время сходить за свежим расписанием.
        SyncWorker.now(this)
        // И ещё один раз, когда таблицу открывать не пришлось.
        lifecycleScope.launch { AppContainer.get(applicationContext).store.countOpen() }

        // Виджет мог попросить открыть конкретный день, а уведомление о
        // новой версии — сразу настройки с кнопкой установки.
        val day = intent?.getStringExtra(EXTRA_DAY)
        val update = intent?.getBooleanExtra(EXTRA_UPDATE, false) == true
        setContent {
            App(
                startDay = day,
                openUpdate = update,
                exactAlarms = exactAlarms.value,
                notifications = notifications.value,
            )
        }
    }

    companion object {
        const val EXTRA_DAY = "day"
        const val EXTRA_UPDATE = "update"
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

/**
 * Светлая схема: серый лист, белые карточки.
 *
 * Было наоборот — белый фон и сероватые карточки, да ещё с синевой в сером.
 * Карточка почти сливалась с фоном, а холодный серый читался как грязь.
 * Здесь серые нейтральные, без примеси, и карточка отделена от листа.
 */
private val LightScheme = lightColorScheme(
    background = Color(0xFFF1F2F4),
    onBackground = Color(0xFF15171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15171A),
    surfaceVariant = Color(0xFFE7E8EA),
    onSurfaceVariant = Color(0xFF5E6266),
    primary = BRAND,
    onPrimary = Color(0xFFFFFFFF),
    error = BRAND,
)

@Composable
private fun App(
    startDay: String? = null,
    openUpdate: Boolean = false,
    exactAlarms: Boolean = false,
    notifications: Boolean = true,
) {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val scope = rememberCoroutineScope()

    // Разрешение на уведомления спрашиваем сами. С Android 13 оно не выдаётся
    // по умолчанию, а targetSdk 37 означает, что система и не спросит: раньше
    // спрашивать было некому, и на свежем телефоне молчали разом напоминания о
    // паре, сообщения об отменах и о новых версиях. Отказ ничего не ломает —
    // в настройках останется подсказка, как выдать разрешение позже.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifications) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val groupName by container.store.groupName.collectAsState(initial = null)
    val secondGroupName by container.store.secondGroupName.collectAsState(initial = null)
    val schedule by container.repository.schedule.collectAsState(initial = null)
    val fetchedAt by container.repository.fetchedAt.collectAsState(initial = 0L)
    val storedTheme by container.store.theme.collectAsState(initial = "system")
    val welcomeSeen by container.store.welcomeSeen.collectAsState(initial = true)
    val loaded by container.store.loaded.collectAsState(initial = false)
    val notifyBefore by container.store.notifyBefore
        .collectAsState(initial = DEFAULT_NOTIFY_BEFORE)
    val notifyEnabled by container.store.notifyEnabled.collectAsState(initial = false)
    val notifyChanges by container.store.notifyChanges.collectAsState(initial = true)
    val notifyUpdates by container.store.notifyUpdates.collectAsState(initial = true)
    val pinnedTeachers by container.store.pinnedTeachers.collectAsState(initial = emptyList())
    val teacherMode by container.store.isTeacher.collectAsState(initial = false)
    val serverStatus by container.store.serverStatus.collectAsState(initial = "ok")
    val tableUrl by container.store.sourceUrl.collectAsState(initial = null)
    val teacherName by container.store.teacherName.collectAsState(initial = null)
    val teacherId by container.store.teacherId.collectAsState(initial = null)
    val pinnedGroups by container.store.pinnedGroups.collectAsState(initial = emptyList())
    var groups by remember { mutableStateOf<List<GroupDto>?>(null) }
    LaunchedEffect(Unit) { groups = container.repository.groups() }
    // Выбранная тема применяется сразу, не дожидаясь записи на диск и обратной
    // волны из хранилища. Из-за этого круга смена выглядела рваной: экран ждал
    // ответа хранилища, а перерисовка виджетов, идущая там же, его задерживала.
    var chosenTheme by remember { mutableStateOf<ThemeChoice?>(null) }
    val theme = chosenTheme ?: ThemeChoice.from(storedTheme)

    var screen by remember { mutableStateOf(if (openUpdate) Screen.SETTINGS else Screen.TODAY) }
    var update by remember { mutableStateOf<ReleaseDto?>(null) }
    // Отдельно от update: «сервер сказал, что новее ничего нет» и «до сервера
    // не достучались» — разные вещи, и человеку об этом надо говорить разное.
    var updateFailed by remember { mutableStateOf(false) }
    // Почему не удалось скачать. Раньше провал был молчаливым: кнопка
    // возвращалась из «Скачиваю…» в исходное, и всё.
    var updateError by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateChecked by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    // Почему обновление не вышло. Раньше кнопка ставила галочку «Расписание
    // обновлено» в любом случае, даже когда связи не было и данные прежние.
    var refreshError by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var focusUpdate by remember { mutableStateOf(openUpdate) }
    // Список преподавателей грузим один раз за запуск и держим здесь: если
    // держать его во вкладке, он перезагружается на каждое переключение.
    var teachers by remember { mutableStateOf<List<GroupDto>?>(null) }
    LaunchedEffect(Unit) { teachers = container.repository.teachers() }

    var reloadKey by remember { mutableStateOf(0) }
    // Какой список показывать на экране выбора: null — по текущей роли.
    var pickTeacher by remember { mutableStateOf<Boolean?>(null) }
    // Тот же экран, но выбирают не свою группу, а соседнюю подгруппу.
    var pickSecond by remember { mutableStateOf(false) }

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
    LaunchedEffect(Unit) {
        (container.updates.check() as? AppUpdate.Check.Available)?.let { update = it.release }
    }

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

    // Тема выбирается внутри приложения, а значки системной полосы — снаружи.
    // Без этой связки светлая тема при тёмной системе оставляла белые часы и
    // заряд на белом фоне: полоса выглядела пустой.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as android.app.Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // Пустой фон, пока настройки читаются с диска: это доли секунды,
            // но без него успевает мелькнуть чужой экран.
            if (!loaded) return@Surface

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

                    Screen.GROUPS -> if (pickSecond) {
                        GroupPickerScreen(
                            loadGroups = { container.repository.groups() },
                            canGoBack = true,
                            onBack = {
                                pickSecond = false
                                screen = Screen.SETTINGS
                            },
                            onPick = { group: GroupDto ->
                                scope.launch {
                                    container.store.selectSecondGroup(group.id, group.name)
                                    // Перерисовать сразу: смена подгруппы стирает
                                    // расписание, и до ответа сети виджет иначе
                                    // показывает пары прежней соседки. Если сети
                                    // нет вовсе, он так и останется с ними.
                                    container.repository.redrawWidgets()
                                    container.repository.refresh(force = true)
                                    pickSecond = false
                                    screen = Screen.SETTINGS
                                }
                            },
                        )
                    } else if (pickTeacher ?: teacherMode) {
                        // Список преподавателей: человек выбирает себя. Роль
                        // меняется вместе с выбором, а не до него — иначе на
                        // мгновение показывается чужое расписание.
                        SelfPickerScreen(
                            teachers = teachers,
                            loadDiagnostics = { container.repository.diagnostics() },
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
                            loadDiagnostics = { container.repository.diagnostics() },
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
                        notifyEnabled = notifyEnabled,
                        notifyChanges = notifyChanges,
                        notifyUpdates = notifyUpdates,
                        exactAlarms = exactAlarms,
                        notifications = notifications,
                        onNotifyBefore = { minutes ->
                            scope.launch {
                                container.store.setNotifyBefore(minutes)
                                LessonAlarms.reschedule(context)
                            }
                        },
                        onNotifyEnabled = { on ->
                            scope.launch {
                                container.store.setNotifyEnabled(on)
                                LessonAlarms.reschedule(context)
                            }
                        },
                        onNotifyChanges = { on ->
                            scope.launch { container.store.setNotifyChanges(on) }
                        },
                        onNotifyUpdates = { on ->
                            scope.launch { container.store.setNotifyUpdates(on) }
                        },
                        focusUpdate = focusUpdate,
                        checkingUpdate = checkingUpdate,
                        updateChecked = updateChecked,
                        updateFailed = updateFailed,
                        updateError = updateError,
                        loadDiagnostics = { container.repository.diagnostics() },
                        onCheckUpdate = {
                            scope.launch {
                                checkingUpdate = true
                                updateError = null
                                when (val result = container.updates.check()) {
                                    is AppUpdate.Check.Available -> {
                                        update = result.release
                                        updateFailed = false
                                    }
                                    AppUpdate.Check.UpToDate -> {
                                        update = null
                                        updateFailed = false
                                    }
                                    AppUpdate.Check.Failed -> updateFailed = true
                                }
                                checkingUpdate = false
                                updateChecked = true
                            }
                        },
                        onUpdate = {
                            val release = update
                            if (release != null) {
                                scope.launch {
                                    installing = true
                                    updateError = null
                                    val result = container.updates.downloadAndInstall(release)
                                    if (result is AppUpdate.Result.Failed) {
                                        updateError = result.why
                                    }
                                    installing = false
                                }
                            }
                        },
                        onTheme = { choice ->
                            chosenTheme = choice
                            scope.launch {
                                container.store.setTheme(ThemeChoice.toStored(choice))
                                // Виджет обязан перекраситься сразу, а не через
                                // час при очередном обновлении.
                                ScheduleWidget().updateAll(context)
                                WeekWidget().updateAll(context)
                                NextLessonWidget().updateAll(context)
                            }
                        },
                        onChangeGroup = {
                            pickSecond = false
                            screen = Screen.GROUPS
                        },
                        secondGroupName = secondGroupName,
                        onPickSecondGroup = {
                            pickSecond = true
                            screen = Screen.GROUPS
                        },
                        onClearSecondGroup = {
                            scope.launch {
                                container.store.clearSecondGroup()
                                container.repository.redrawWidgets()
                                container.repository.refresh(force = true)
                            }
                        },
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
                        refreshError = refreshError,
                        onErrorShown = { refreshError = null },
                        loadTally = { container.store.tally() },
                        serverBroken = serverStatus != "ok",
                        sourceUrl = tableUrl,
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
