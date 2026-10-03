package ru.whensclass.ui

import android.os.Build
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStateAtLeast
import ru.whensclass.AppContainer
import ru.whensclass.data.sheetLink
import ru.whensclass.data.AppUpdate
import ru.whensclass.data.DEFAULT_NOTIFY_BEFORE
import ru.whensclass.data.GroupDto
import ru.whensclass.data.MAX_GROUPS
import ru.whensclass.data.subgroupsOf
import ru.whensclass.data.RefreshResult
import ru.whensclass.data.ReleaseDto
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.widget.ThemeChoice

/** Экраны приложения. Их четыре, поэтому обходимся без библиотеки навигации. */
// Порядок важен: по нему считается, куда «едет» экран при переходе.
private enum class Screen { WELCOME, GROUPS, TODAY, SETTINGS }


@Composable
internal fun App(
    startDay: String? = null,
    openUpdate: Boolean = false,
    openSeq: Int = 0,
    exactAlarms: Boolean = false,
    notifications: Boolean = true,
    phone: ru.whensclass.notify.PhoneState = ru.whensclass.notify.PhoneState(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val container = remember { AppContainer.get(context) }
    val scope = rememberCoroutineScope()

    // Разрешение на уведомления спрашиваем сами: с Android 13 оно не выдаётся
    // по умолчанию, а при новом targetSdk система сама не спросит. Отказ ничего
    // не ломает — в настройках останется подсказка, как выдать его позже.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    // Один раз, а не на каждое пересоздание экрана (поворот).
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (askedNotifications) return@LaunchedEffect
        askedNotifications = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifications) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val groupName by container.store.groupName.collectAsState(initial = null)
    val groupId by container.store.groupId.collectAsState(initial = null)
    val extraGroups by container.repository.extraGroups.collectAsState(initial = emptyList())
    val groupsByName by container.store.groupsByName.collectAsState(initial = true)
    val schedule by container.repository.schedule.collectAsState(initial = null)
    // Для экрана — вместе с парами остальных выбранных групп; виджеты и
    // напоминания — по своему расписанию.
    val shownSchedule by container.repository.shownSchedule.collectAsState(initial = null)
    val fetchedAt by container.repository.fetchedAt.collectAsState(initial = 0L)
    val storedTheme by container.store.theme.collectAsState(initial = "system")
    val welcomeSeen by container.store.welcomeSeen.collectAsState(initial = true)
    val loaded by container.store.loaded.collectAsState(initial = false)
    val notifyBefore by container.store.notifyBefore
        .collectAsState(initial = DEFAULT_NOTIFY_BEFORE)
    val notifyEnabled by container.store.notifyEnabled.collectAsState(initial = false)
    val notifyChanges by container.store.notifyChanges.collectAsState(initial = true)
    val notifyUpdates by container.store.notifyUpdates.collectAsState(initial = true)
    val notifyServer by container.store.notifyServer.collectAsState(initial = true)
    val notifyGroupsGone by container.store.notifyGroupsGone.collectAsState(initial = true)
    val pinnedTeachers by container.store.pinnedTeachers.collectAsState(initial = emptyList())
    val teacherMode by container.store.isTeacher.collectAsState(initial = false)
    val serverStatus by container.store.serverStatus.collectAsState(initial = "ok")
    val tableUrl by container.store.sourceUrl.collectAsState(initial = null)
    val serverSince by container.store.serverSince.collectAsState(initial = null)
    val gone by container.store.gone.collectAsState(initial = false)
    val teacherName by container.store.teacherName.collectAsState(initial = null)
    val teacherId by container.store.teacherId.collectAsState(initial = null)
    val pinnedGroups by container.store.pinnedGroups.collectAsState(initial = emptyList())
    var groups by remember { mutableStateOf<List<GroupDto>?>(null) }
    // Выбранная тема применяется сразу, не дожидаясь круга через хранилище:
    // его задерживает перерисовка виджетов, и смена выглядела бы рваной.
    var chosenTheme by remember { mutableStateOf<ThemeChoice?>(null) }
    val theme = chosenTheme ?: ThemeChoice.from(storedTheme)

    // Экран, выбор и загрузка — через rememberSaveable: поворот, разделение
    // экрана и смена темы пересоздают Activity.
    var screen by rememberSaveable { mutableStateOf(if (openUpdate) Screen.SETTINGS else Screen.TODAY) }
    // Откуда пришли к выбору группы: туда и «назад» — и стрелкой, и жестом.
    var groupsFrom by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    var update by remember { mutableStateOf<ReleaseDto?>(null) }
    // Отдельно от update: «сервер сказал, что новее ничего нет» и «до сервера
    // не достучались» — разные вещи, и человеку об этом надо говорить разное.
    var updateFailed by remember { mutableStateOf(false) }
    // Почему не удалось скачать: без этого провал молчалив.
    var updateError by rememberSaveable { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateChecked by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    // Почему ручное обновление не вышло — текстом для плашки.
    var refreshError by remember { mutableStateOf<String?>(null) }
    // Последнее ручное обновление не удалось: кнопка покажет крестик, а не
    // галочку «Расписание обновлено».
    var refreshFailed by remember { mutableStateOf(false) }
    // Добавить другие группы — и сказать, если их пары не пришли: значок без
    // пар иначе читается как «у группы пар нет».
    suspend fun addGroups(groups: List<GroupDto>) {
        val result = container.repository.addExtraGroups(groups)
        if (result is RefreshResult.Partial) refreshError = partialText(result.missed, result.fresh)
    }
    val download by container.updates.download.collectAsState()
    val installing = download == AppUpdate.Download.Running
    var focusUpdate by rememberSaveable { mutableStateOf(openUpdate) }
    // Списки держим здесь, а не во вкладке, чтобы не перезагружать их на
    // каждое переключение; пока пусто — повтор при каждом заходе (метро,
    // спящий вайфай).
    var teachers by remember { mutableStateOf<List<GroupDto>?>(null) }
    // Свежие списки за этот заход уже пришли — дальше хватит их.
    var listsFresh by remember { mutableStateOf(false) }
    // «Повторить» — в сеть, даже если сохранённые моложе 12 часов.
    var listsRetry by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    // Какой список показывать на экране выбора: null — по текущей роли.
    var pickTeacher by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Тот же экран, но выбирают не свою группу, а ещё одну к ней.
    var pickExtra by rememberSaveable { mutableStateOf(false) }

    // Итог загрузки обновления. Установщик открывается, когда человек в
    // приложении: из фона Android 10+ его молча не пускает.
    LaunchedEffect(download) {
        val done = download as? AppUpdate.Download.Done ?: return@LaunchedEffect
        when (val result = done.result) {
            is AppUpdate.Result.Failed -> updateError = result.why
            is AppUpdate.Result.Ready -> lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                runCatching { context.startActivity(result.intent) }
                    .onFailure { updateError = "установщик Android не найден" }
            }
        }
        container.updates.taken()
    }

    // Новое нажатие по живому экрану: к дню из виджета или к обновлению.
    // Уведомление без дня («Расписание изменилось») — тоже к расписанию.
    LaunchedEffect(openSeq) {
        if (openSeq == 0) return@LaunchedEffect
        if (openUpdate) {
            focusUpdate = true
            screen = Screen.SETTINGS
        } else {
            pickExtra = false
            pickTeacher = null
            screen = Screen.TODAY
        }
    }

    // И на каждое возвращение в приложение, пока свежих списков нет: иначе
    // после первого запуска без связи список не перечитался бы до перезапуска.
    LaunchedEffect(screen, reloadKey, ScreenClock.resumes) {
        val repository = container.repository
        // Сохранённые — сразу, без сети: экран открывается и в метро.
        // Свежие — следом и оба разом, а не по очереди.
        if (groups.isNullOrEmpty()) repository.cachedGroups().takeIf { it.isNotEmpty() }?.let { groups = it }
        if (teachers.isNullOrEmpty()) repository.cachedTeachers().takeIf { it.isNotEmpty() }?.let { teachers = it }
        if (listsFresh) return@LaunchedEffect
        // Свежие списки — не чаще раза в LISTS_FRESH_MILLIS, если сохранённые
        // есть: как на сайте. «Повторить» и пустой список — сразу.
        val age = System.currentTimeMillis() - container.store.listsFetchedAt()
        if (!listsRetry && !groups.isNullOrEmpty() && !teachers.isNullOrEmpty() && age in 0 until LISTS_FRESH_MILLIS) {
            listsFresh = true
            return@LaunchedEffect
        }
        listsRetry = false
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
            listsFresh = true
            container.store.setListsFetchedAt(System.currentTimeMillis())
            repository.followRenamedPins(freshGroups, freshTeachers)
        }
    }

    // Навигация своя, поэтому системному «назад» о ней надо сказать: иначе
    // оно закрывает приложение с любого экрана.
    BackHandler(enabled = screen != Screen.TODAY) {
        val back = if (screen == Screen.GROUPS) groupsFrom else Screen.TODAY
        pickExtra = false
        pickTeacher = null
        screen = back
    }

    // «Повторить» у списков — заново и сеть, даже если свежие уже приходили.
    val retryLists: () -> Unit = {
        listsFresh = false
        listsRetry = true
        reloadKey++
    }

    // Сервер эту сборку не обслуживает (426): списки не придут — к обновлению
    // в настройках, а не «проверьте интернет».
    val toUpdate: (() -> Unit)? = if (serverStatus == ru.whensclass.data.STATUS_UNSUPPORTED) {
        {
            focusUpdate = true
            screen = Screen.SETTINGS
        }
    } else {
        null
    }

    // Выбрали группу или себя: сразу на экран расписания, и ⟳ крутится, пока
    // идёт сеть.
    val afterPick: (suspend () -> Unit) -> Unit = { select ->
        scope.launch {
            refreshing = true
            screen = Screen.TODAY
            select()
            // После записи выбора: на первом запуске до неё виден экран выбора,
            // и сброс роли мелькнул бы списком групп.
            pickTeacher = null
            refreshing = false
        }
    }

    val refreshNow: () -> Unit = {
        scope.launch {
            refreshing = true
            // Списки на ⟳ не перезапрашиваются: они — раз в 12 часов.
            reloadKey++
            // Напрямую, без WorkManager: он вправе отложить задачу на минуты,
            // а человек только что нажал кнопку и ждёт ответа сейчас.
            val result = container.repository.refresh(force = true)
            refreshFailed = result is RefreshResult.Failed || result is RefreshResult.Partial
            when (result) {
                is RefreshResult.Failed -> refreshError = refreshFailure(result.error, teacherMode)
                // Своё обновилось, другие группы — не все: без галочки и с
                // именами тех, чьи прежние пары на экране.
                is RefreshResult.Partial -> refreshError = partialText(result.missed, result.fresh)
                // Сервер здоров, группы нет: сказать об этом, а не молча
                // погасить ⟳ крестиком «сервер не смог».
                RefreshResult.Gone -> refreshError =
                    if (teacherMode) "Вас больше нет в таблице — выберите себя заново"
                    else "Группы больше нет в таблице — выберите заново"
                else -> Unit
            }
            refreshing = false
        }
    }

    // Проверка обновления — по правилам [AppUpdate.checkForScreen].
    // Принудительно по уведомлению — один раз на открытие, не на каждое
    // возвращение.
    var forcedOpen by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(openSeq, ScreenClock.resumes) {
        val force = openUpdate && forcedOpen != openSeq
        if (openUpdate) forcedOpen = openSeq
        when (val result = container.updates.checkForScreen(force = force)) {
            is AppUpdate.Check.Available -> update = result.release
            AppUpdate.Check.UpToDate -> update = null
            // Не дозвонились — известное прежде не забываем.
            AppUpdate.Check.Failed -> Unit
        }
    }

    // И сразу забираем свежее расписание: после установки новой версии старые
    // данные на экране выглядят как поломка.
    LaunchedEffect(Unit) { container.repository.refresh() }
    // Кого показываем — зависит от роли: группу или самого преподавателя.
    val chosenName = if (teacherMode) teacherName else groupName
    val current = when {
        !welcomeSeen -> Screen.WELCOME
        // К обновлению — и без выбора: сборку, которую сервер не обслуживает,
        // список групп не получит, и выбрать будет нечего.
        chosenName == null && screen != Screen.SETTINGS -> Screen.GROUPS
        else -> screen
    }

    val dark = when (theme) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }

    // Тема выбирается внутри приложения, а значки системной полосы — снаружи:
    // без этой связки светлая тема при тёмной системе даёт белые значки на
    // белом фоне.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as android.app.Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        // До Android 10 полосу навигации красит enableEdgeToEdge — по теме
        // системы, а не приложения.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = (if (dark) DarkScheme else LightScheme).background.toArgb()
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
            // наоборот; резкая подмена читается как подвисание.
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
                            scope.launch { container.store.markWelcomeSeen() }
                        },
                    )

                    Screen.GROUPS -> if (pickExtra) {
                        GroupPickerScreen(
                            // Своя и уже выбранные — не в списке: дважды одну не показываем.
                            groups = groups?.filter { group ->
                                group.id != groupId && extraGroups.none { it.id == group.id }
                            },
                            onRetry = retryLists,
                            // «Вторая группа», «Третья группа»: какой по счёту она
                            // встанет в значках у пар.
                            title = ordinalGroup(extraGroups.size + 2),
                            canGoBack = true,
                            onBack = {
                                pickExtra = false
                                screen = groupsFrom
                            },
                            onPick = { group: GroupDto ->
                                pickExtra = false
                                screen = Screen.SETTINGS
                                scope.launch { addGroups(listOf(group)) }
                            },
                        )
                    } else if (pickTeacher ?: teacherMode) {
                        // Список преподавателей: человек выбирает себя. Роль
                        // меняется вместе с выбором, а не до него — иначе на
                        // мгновение показывается чужое расписание.
                        SelfPickerScreen(
                            teachers = teachers,
                            onRetry = retryLists,
                            loadDiagnostics = { container.repository.diagnostics() },
                            onUpdate = toUpdate,
                            canGoBack = chosenName != null,
                            onBack = {
                                pickTeacher = null
                                screen = groupsFrom
                            },
                            onStudentMode = { pickTeacher = false },
                            onPick = { teacher ->
                                afterPick { container.repository.selectSelfAsTeacher(teacher) }
                            },
                        )
                    } else {
                        GroupPickerScreen(
                            groups = groups,
                            onRetry = retryLists,
                            loadDiagnostics = { container.repository.diagnostics() },
                            onUpdate = toUpdate,
                            canGoBack = chosenName != null,
                            onBack = {
                                pickTeacher = null
                                screen = groupsFrom
                            },
                            onTeacherMode = { pickTeacher = true },
                            onPick = { group: GroupDto ->
                                afterPick { container.repository.selectGroup(group) }
                            },
                        )
                    }

                    Screen.SETTINGS -> SettingsScreen(
                        groupName = chosenName,
                        teacherMode = teacherMode,
                        theme = theme,
                        update = update,
                        installing = installing,
                        notifyBefore = notifyBefore,
                        notifyEnabled = notifyEnabled,
                        notifyChanges = notifyChanges,
                        notifyUpdates = notifyUpdates,
                        notifyServer = notifyServer,
                        notifyGroupsGone = notifyGroupsGone,
                        exactAlarms = exactAlarms,
                        notifications = notifications,
                        phone = phone,
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
                        onNotifyServer = { on ->
                            scope.launch { container.store.setNotifyServer(on) }
                        },
                        onNotifyGroupsGone = { on ->
                            scope.launch { container.store.setNotifyGroupsGone(on) }
                        },
                        focusUpdate = focusUpdate,
                        onUpdateFocused = { focusUpdate = false },
                        checkingUpdate = checkingUpdate,
                        updateChecked = updateChecked,
                        updateFailed = updateFailed,
                        updateError = updateError,
                        loadDiagnostics = { container.repository.diagnostics() },
                        sheetUrl = { sheetLink(schedule, ru.whensclass.widget.collegeToday(), tableUrl) },
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
                            update?.let {
                                updateError = null
                                container.updates.start(it)
                            }
                        },
                        onTheme = { choice ->
                            chosenTheme = choice
                            scope.launch {
                                container.store.setTheme(ThemeChoice.toStored(choice))
                                // Виджет перекрашивается сразу. Через репозиторий:
                                // там вшит счёт показов.
                                container.repository.redrawWidgets()
                            }
                        },
                        onChangeGroup = {
                            pickExtra = false
                            groupsFrom = Screen.SETTINGS
                            screen = Screen.GROUPS
                        },
                        extraGroups = extraGroups,
                        groupsByName = groupsByName,
                        onGroupsByName = { byName -> scope.launch { container.store.setGroupsByName(byName) } },
                        // Подгруппы своей группы, которых ещё нет среди выбранных, —
                        // строками с «Добавить», сколько влезет.
                        subgroups = groupName?.let { name -> subgroupsOf(name, groups.orEmpty()) }.orEmpty()
                            .filter { group -> extraGroups.none { it.id == group.id } }
                            .take(MAX_GROUPS - 1 - extraGroups.size),
                        onAddGroup = {
                            pickExtra = true
                            groupsFrom = Screen.SETTINGS
                            screen = Screen.GROUPS
                        },
                        onAddSubgroup = { group ->
                            scope.launch { addGroups(listOf(group)) }
                        },
                        onRemoveGroup = { id ->
                            scope.launch { container.repository.removeExtraGroup(id) }
                        },
                        onBack = { screen = Screen.TODAY },
                    )

                    Screen.TODAY -> TodayScreen(
                        startDay = startDay,
                        startKey = openSeq,
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
                        schedule = shownSchedule,
                        groupsByName = groupsByName,
                        fetchedAt = fetchedAt,
                        hasUpdate = update != null,
                        refreshing = refreshing,
                        refreshFailed = refreshFailed,
                        refreshError = refreshError,
                        onErrorShown = { refreshError = null },
                        loadTally = { container.store.tally() },
                        serverBroken = serverStatus != "ok",
                        unreachable = serverStatus == ru.whensclass.data.STATUS_UNREACHABLE,
                        unsupported = serverStatus == ru.whensclass.data.STATUS_UNSUPPORTED,
                        sourceUrl = tableUrl,
                        serverSince = serverSince,
                        gone = gone,
                        onRepick = {
                            pickTeacher = teacherMode
                            pickExtra = false
                            groupsFrom = Screen.TODAY
                            screen = Screen.GROUPS
                        },
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

/** Свежие списки групп и преподавателей — не чаще этого, если сохранённые есть. */
internal const val LISTS_FRESH_MILLIS = 12L * 60 * 60 * 1000

/**
 * Своё пришло, другие группы — не все. Про прежние пары — только если они
 * есть: у только что добавленной группы их нет.
 */
internal fun partialText(missed: List<String>, fresh: Set<String>): String {
    val one = missed.size == 1
    val names = missed.joinToString(", ")
    return when {
        missed.all { it in fresh } ->
            if (one) "Не загрузилась группа $names: её пары придут со следующим обновлением"
            else "Не загрузились группы $names: их пары придут со следующим обновлением"
        missed.none { it in fresh } ->
            if (one) "Не обновилась группа $names: на экране её прежние пары"
            else "Не обновились группы $names: на экране их прежние пары"
        else -> "Не обновились группы $names: пары придут со следующим обновлением"
    }
}

/**
 * Почему ручное обновление не удалось — словами для плашки. 503 здесь — не
 * «занят», а «расписания на сервере нет» (docs/api.md); занятость nginx —
 * это 429.
 */
internal fun refreshFailure(error: Throwable, teacher: Boolean = false): String = when (error) {
    is ru.whensclass.data.HttpFailure -> when (error.code) {
        429 -> "Сервер занят, попробуйте через минуту"
        426 -> "Эта версия приложения больше не поддерживается — обновите его"
        // Первый час после пропажи ([ScheduleStore.gone]): код ответа выглядел
        // бы поломкой приложения.
        404 -> if (teacher) "Вас не нашлось в таблице. Не вернётся за час — выберите себя заново"
        else "Группы не нашлось в таблице. Не вернётся за час — выберите её заново"
        // 503 — когда снимка нет вовсе (новый сервер, потерянные данные).
        503 -> "Сервер сейчас не отдаёт это расписание, на экране прежнее. Не пройдёт за час — " +
            "напишите автору в Telegram: @toomonn"
        else -> "Не удалось обновить: сервер ответил ${error.code}"
    }
    else -> "Не удалось обновить: нет связи с сервером"
}
