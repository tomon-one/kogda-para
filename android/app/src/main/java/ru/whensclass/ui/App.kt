package ru.whensclass.ui

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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStateAtLeast
import ru.whensclass.AppContainer
import ru.whensclass.data.sheetLink
import ru.whensclass.data.addExtraGroups
import ru.whensclass.data.cachedGroups
import ru.whensclass.data.cachedTeachers
import ru.whensclass.data.freshGroups
import ru.whensclass.data.freshTeachers
import ru.whensclass.data.followRenamedPins
import ru.whensclass.data.rooms
import ru.whensclass.data.retakes
import ru.whensclass.data.teacherSchedule
import ru.whensclass.data.groupSchedule
import ru.whensclass.data.selectGroup
import ru.whensclass.data.selectSelfAsTeacher
import ru.whensclass.data.removeExtraGroup
import ru.whensclass.data.DEFAULT_NOTIFY_BEFORE
import ru.whensclass.data.GroupDto
import ru.whensclass.data.MAX_GROUPS
import ru.whensclass.data.subgroupsOf
import ru.whensclass.data.RefreshResult
import ru.whensclass.data.setNotifyChanges
import ru.whensclass.data.setNotifyGroupsGone
import ru.whensclass.data.setNotifyServer
import ru.whensclass.data.setNotifyUpdates
import ru.whensclass.data.togglePinnedGroup
import ru.whensclass.data.togglePinnedTeacher
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.widget.ThemeChoice

/** Экраны приложения. Их четыре, поэтому обходимся без библиотеки навигации. */
// Порядок важен: по нему считается, куда «едет» экран при переходе.
internal enum class Screen { WELCOME, GROUPS, TODAY, SETTINGS, SUBJECTS }


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

    AskNotificationPermission(notifications)

    val groupName by container.store.groupName.collectAsState(initial = null)
    val groupId by container.store.groupId.collectAsState(initial = null)
    val extraGroups by container.repository.extraGroups.collectAsState(initial = emptyList())
    val groupsByName by container.store.groupsByName.collectAsState(initial = true)
    val shortSubjects by container.store.shortSubjects.collectAsState(initial = false)
    val subjectShorts by container.store.subjectShorts.collectAsState(initial = emptyMap())
    val subjects = ru.whensclass.data.SubjectNames(shortSubjects, subjectShorts)
    // Пока не прочитано — считаем увиденной: плашка не мигает при каждом запуске.
    val channelSeen by container.store.channelSeen.collectAsState(initial = true)
    val schedule by container.repository.schedule.collectAsState(initial = null)
    // Для экрана — вместе с парами остальных групп; виджеты и напоминания — по своему расписанию.
    val shownSchedule by container.repository.shownSchedule.collectAsState(initial = null)
    val fetchedAt by container.repository.fetchedAt.collectAsState(initial = 0L)
    val storedTheme by container.store.theme.collectAsState(initial = "system")
    // Null — ещё не прочитано: первый кадр иначе мелькал бы обычной тёмной.
    val glowStored by container.store.glow.collectAsState(initial = null)
    val glow = glowStored == true
    // «Vice» действует только до срока: флаг в хранилище остаётся, даже когда тема уже не работает.
    val viceStored by container.store.vice.collectAsState(initial = null)
    val vice = viceActive(viceStored == true, ru.whensclass.widget.collegeToday())
    // «Стекло» не работает, пока включён «Vice»; свой выбор оно при этом не теряет.
    val glass = glow && !vice
    val rowStays by container.store.rowStays.collectAsState(initial = false)
    val compact by container.store.compact.collectAsState(initial = null)
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
    val serverChecked by container.store.serverChecked.collectAsState(initial = null)
    val digestMinutes by container.store.digestMinutes.collectAsState(initial = 0)
    val gone by container.store.gone.collectAsState(initial = false)
    val notFoundPending by container.store.notFoundPending.collectAsState(initial = false)
    val teacherName by container.store.teacherName.collectAsState(initial = null)
    val teacherId by container.store.teacherId.collectAsState(initial = null)
    val pinnedGroups by container.store.pinnedGroups.collectAsState(initial = emptyList())
    // Тема применяется сразу, не ожидая круга через хранилище: его задерживает перерисовка виджетов.
    var chosenTheme by remember { mutableStateOf<ThemeChoice?>(null) }
    val theme = chosenTheme ?: ThemeChoice.from(storedTheme)

    // Экран, выбор и загрузка — через rememberSaveable: поворот, разделение
    // экрана и смена темы пересоздают Activity.
    var screen by rememberSaveable { mutableStateOf(if (openUpdate) Screen.SETTINGS else Screen.TODAY) }
    // Откуда пришли к выбору группы: туда и «назад» — и стрелкой, и жестом.
    var groupsFrom by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    val upd = rememberUpdateUi(container, scope, context, lifecycle, openUpdate, openSeq)
    var refreshing by remember { mutableStateOf(false) }
    // Почему ручное обновление не вышло — текстом для плашки.
    var refreshError by remember { mutableStateOf<String?>(null) }
    // Последнее ручное обновление не удалось: кнопка покажет крестик, а не галочку.
    var refreshFailed by remember { mutableStateOf(false) }
    // Добавить другие группы — и сказать, если их пары не пришли: значок без пар читается как «пар нет».
    suspend fun addGroups(groups: List<GroupDto>) {
        val result = container.repository.addExtraGroups(groups)
        if (result is RefreshResult.Partial) refreshError = partialText(result.missed, result.fresh)
    }
    // Списки и пересдачи держим здесь, чтобы не перезагружать на каждое переключение вкладки.
    val lists = rememberListsUi(container, screen)
    val rt = rememberRetakesUi(container, screen)
    val roomsMemory = remember { RoomsMemory() }
    // Какой список показывать на экране выбора: null — по текущей роли.
    var pickTeacher by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Тот же экран, но выбирают не свою группу, а ещё одну к ней.
    var pickExtra by rememberSaveable { mutableStateOf(false) }

    // Новое нажатие по живому экрану: к дню из виджета или к обновлению; уведомление без дня — к расписанию.
    LaunchedEffect(openSeq) {
        if (openSeq == 0) return@LaunchedEffect
        if (openUpdate) {
            upd.focus = true
            screen = Screen.SETTINGS
        } else {
            pickExtra = false
            pickTeacher = null
            screen = Screen.TODAY
        }
    }

    // Навигация своя: системному «назад» о ней надо сказать, иначе оно закрывает приложение с любого экрана.
    BackHandler(enabled = screen != Screen.TODAY) {
        // Сменили роль кнопкой на экране выбора — назад к прежнему выбору, как стрелка.
        if (screen == Screen.GROUPS && !pickExtra && pickTeacher != null && pickTeacher != teacherMode) {
            pickTeacher = null
            return@BackHandler
        }
        val back = when (screen) {
            Screen.GROUPS -> groupsFrom
            // Из таблицы сокращений — назад в настройки, откуда пришли.
            Screen.SUBJECTS -> Screen.SETTINGS
            else -> Screen.TODAY
        }
        pickExtra = false
        pickTeacher = null
        screen = back
    }

    // «Повторить» у списков — заново и сеть, даже если свежие уже приходили.
    val retryLists: () -> Unit = lists::retryNow

    // Сервер эту сборку не обслуживает (426): списки не придут — к обновлению, а не «проверьте интернет».
    val toUpdate: (() -> Unit)? = if (serverStatus == ru.whensclass.data.STATUS_UNSUPPORTED) {
        {
            upd.focus = true
            screen = Screen.SETTINGS
        }
    } else {
        null
    }

    // Выбрали группу или себя: сразу на экран расписания, ⟳ крутится, пока идёт сеть.
    val afterPick: (suspend () -> RefreshResult) -> Unit = { select ->
        scope.launch {
            refreshing = true
            screen = Screen.TODAY
            // Значок ⟳ — по этой загрузке: без сети не «обновлено», а после прежней неудачи удачный выбор — не крестик.
            val result = select()
            refreshFailed = result is RefreshResult.Failed || result is RefreshResult.Partial
            // После записи выбора: на первом запуске до неё сброс роли мелькнул бы списком групп.
            pickTeacher = null
            refreshing = false
        }
    }

    val refreshNow: () -> Unit = {
        scope.launch {
            refreshing = true
            // Списки на ⟳ не перезапрашиваются: они — раз в 12 часов.
            lists.reload()
            // Пересдачи и кабинеты, что не загрузились, — заново вместе с расписанием.
            if (rt.body == null) rt.retry()
            roomsMemory.nudge++
            // Напрямую, без WorkManager: он может отложить задачу на минуты, а человек ждёт ответа.
            val result = container.repository.refresh(force = true)
            refreshFailed = result is RefreshResult.Failed || result is RefreshResult.Partial
            when (result) {
                is RefreshResult.Failed -> refreshError = refreshFailure(result.error, teacherMode)
                // Своё обновилось, другие группы не все: без галочки, с именами тех, чьи прежние пары на экране.
                is RefreshResult.Partial -> refreshError = partialText(result.missed, result.fresh)
                // Сервер здоров, группы нет: сказать об этом, а не гасить ⟳ крестиком.
                RefreshResult.Gone -> refreshError =
                    if (teacherMode) "Вас больше нет в таблице — выберите себя заново"
                    else "Группы больше нет в таблице — выберите заново"
                else -> Unit
            }
            refreshing = false
        }
    }

    // И сразу забираем свежее расписание: после установки старые данные выглядят как поломка.
    var opening by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try { container.repository.refresh() } finally { opening = false }
    }
    // Кого показываем — зависит от роли: группу или самого преподавателя.
    val chosenName = if (teacherMode) teacherName else groupName
    val current = when {
        !welcomeSeen -> Screen.WELCOME
        // К обновлению и без выбора: сборка, которую сервер не обслуживает, списка групп не получит.
        chosenName == null && screen != Screen.SETTINGS -> Screen.GROUPS
        else -> screen
    }

    val dark = when (theme) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }

    val scheme = when {
        vice && dark -> ViceDarkScheme
        vice -> ViceLightScheme
        glass && dark -> GlassDarkScheme
        glass -> GlassLightScheme
        dark -> DarkScheme
        else -> LightScheme
    }
    ApplyDarkTheme(dark, scheme)
    MaterialTheme(colorScheme = scheme) {
        Surface(
            // Закат «Vice» рисуется под карточками: у схемы непрозрачный фон, и Surface закрыл бы его.
            modifier = when {
                glass -> Modifier.fillMaxSize().drawBehind { glowBackdrop(dark) }
                vice -> Modifier.fillMaxSize().drawBehind { viceBackdrop(dark) }
                else -> Modifier.fillMaxSize()
            },
            color = if (vice) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
        ) {
            // Пустой фон, пока настройки читаются с диска: иначе мелькнёт чужой экран.
            if (!loaded || glowStored == null || viceStored == null) return@Surface
            val screenStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

            // Экраны сменяются со сдвигом (вглубь — справа налево, назад — наоборот): резкая
            // подмена читается как подвисание.
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
                // Экран помнит своё, пока открыт другой: из настроек «назад» — на ту же вкладку, с поиском и выбором.
                screenStates.SaveableStateProvider(target.name) {
                when (target) {
                    Screen.WELCOME -> WelcomeScreen(
                        onContinue = {
                            scope.launch { container.store.markWelcomeSeen() }
                        },
                    )

                    Screen.GROUPS -> if (pickExtra) {
                        PickerScreen(
                            // Своя и уже выбранные — не в списке: дважды одну не показываем.
                            items = lists.groups?.filter { group ->
                                group.id != groupId && extraGroups.none { it.id == group.id }
                            },
                            onRetry = retryLists,
                            // «Вторая группа», «Третья группа» — какой по счёту встанет в значках у пар.
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
                        // Список преподавателей: человек выбирает себя. Роль меняется вместе с
                        // выбором, иначе на миг покажется чужое расписание.
                        PickerScreen(
                            items = lists.teachers,
                            teachers = true,
                            onRetry = retryLists,
                            loadDiagnostics = { container.repository.diagnostics() },
                            onUpdate = toUpdate,
                            // Пришли сюда кнопкой «Я преподаватель» — назад к выбору группы.
                            canGoBack = chosenName != null || pickTeacher == true && !teacherMode,
                            onBack = {
                                val switched = pickTeacher == true && !teacherMode
                                pickTeacher = null
                                if (!switched) screen = groupsFrom
                            },
                            onSwitchRole = { pickTeacher = false },
                            onPick = { teacher ->
                                afterPick { container.repository.selectSelfAsTeacher(teacher) }
                            },
                        )
                    } else {
                        PickerScreen(
                            items = lists.groups,
                            onRetry = retryLists,
                            loadDiagnostics = { container.repository.diagnostics() },
                            onUpdate = toUpdate,
                            canGoBack = chosenName != null || pickTeacher == false && teacherMode,
                            onBack = {
                                val switched = pickTeacher == false && teacherMode
                                pickTeacher = null
                                if (!switched) screen = groupsFrom
                            },
                            onSwitchRole = { pickTeacher = true },
                            onPick = { group: GroupDto ->
                                afterPick { container.repository.selectGroup(group) }
                            },
                        )
                    }

                    Screen.SETTINGS -> SettingsScreen(
                        groupName = chosenName,
                        teacherMode = teacherMode,
                        theme = theme,
                        glow = glow,
                        onGlow = { on -> scope.launch { container.store.setGlow(on) } },
                        vice = vice,
                        onVice = { on -> scope.launch { container.store.setVice(on) } },
                        rowStays = rowStays,
                        onRowStays = { on -> scope.launch { container.store.setRowStays(on) } },
                        compact = compact,
                        onCompact = { on -> scope.launch { container.store.setCompact(on) } },
                        update = upd.update,
                        installing = upd.installing,
                        notifyBefore = notifyBefore,
                        notifyEnabled = notifyEnabled,
                        notifyChanges = notifyChanges,
                        notifyUpdates = notifyUpdates,
                        notifyServer = notifyServer,
                        notifyGroupsGone = notifyGroupsGone,
                        digestMinutes = digestMinutes,
                        onDigest = { on ->
                            scope.launch {
                                container.store.setDigestMinutes(
                                    if (on) container.store.digestLast() else 0,
                                )
                                ru.whensclass.notify.Digest.reschedule(context)
                            }
                        },
                        onDigestTime = { minutes ->
                            scope.launch {
                                container.store.setDigestMinutes(minutes)
                                ru.whensclass.notify.Digest.reschedule(context)
                            }
                        },
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
                        focusUpdate = upd.focus,
                        onUpdateFocused = { upd.focus = false },
                        checkingUpdate = upd.checking,
                        updateChecked = upd.checked,
                        updateFailed = upd.failed,
                        updateError = upd.error,
                        loadDiagnostics = { container.repository.diagnostics() },
                        sheetUrl = { sheetLink(schedule, ru.whensclass.widget.collegeToday(), tableUrl) },
                        onCheckUpdate = upd::check,
                        onUpdate = upd::start,
                        onTheme = { choice ->
                            chosenTheme = choice
                            scope.launch {
                                container.store.setTheme(ThemeChoice.toStored(choice))
                                // Виджет перекрашивается сразу.
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
                        shortSubjects = shortSubjects,
                        onShortSubjects = { on -> scope.launch { container.store.setShortSubjects(on) } },
                        onSubjects = { screen = Screen.SUBJECTS },
                        // Подгруппы своей группы, ещё не выбранные, — строками с «Добавить», сколько влезет.
                        subgroups = groupName?.let { name -> subgroupsOf(name, lists.groups.orEmpty()) }.orEmpty()
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

                    Screen.SUBJECTS -> SubjectsScreen(
                        schedule = schedule,
                        names = subjects,
                        onSave = { full, short ->
                            scope.launch { container.store.setSubjectShort(full, short) }
                        },
                        onBack = { screen = Screen.SETTINGS },
                    )

                    Screen.TODAY -> TodayScreen(
                        rowStays = rowStays,
                        compact = compact,
                        retakes = rt.body,
                        retakesFailed = rt.failed,
                        retakesAnswered = rt.answered,
                        onRetryRetakes = rt::retry,
                        loadRooms = { container.repository.rooms(it) },
                        roomsMemory = roomsMemory,
                        startDay = startDay,
                        startKey = openSeq,
                        groupName = chosenName.orEmpty(),
                        teacherMode = teacherMode,
                        teachers = lists.teachers,
                        loadTeacherSchedule = { container.repository.teacherSchedule(it) },
                        loadGroupSchedule = { container.repository.groupSchedule(it) },
                        groups = lists.groups,
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
                        subjects = subjects,
                        channelNotice = !channelSeen,
                        onChannelSeen = { scope.launch { container.store.setChannelSeen() } },
                        fetchedAt = fetchedAt,
                        hasUpdate = upd.update != null,
                        refreshing = refreshing,
                        loading = opening,
                        refreshFailed = refreshFailed,
                        refreshError = refreshError,
                        onErrorShown = { refreshError = null },
                        loadTally = { container.store.tally() },
                        saveDayImage = { groupId, date -> container.repository.dayImage(groupId, date) },
                        serverBroken = serverStatus != "ok",
                        unreachable = serverStatus == ru.whensclass.data.STATUS_UNREACHABLE,
                        editing = serverStatus == ru.whensclass.data.STATUS_EDITING,
                        unsupported = serverStatus == ru.whensclass.data.STATUS_UNSUPPORTED,
                        sourceUrl = tableUrl,
                        serverSince = serverSince,
                        serverChecked = serverChecked,
                        // Показать нечего, сервер говорит «нет такой группы» — сразу «выбрать заново», как на сайте.
                        gone = gone || (notFoundPending && shownSchedule == null),
                        onRepick = {
                            pickTeacher = teacherMode
                            pickExtra = false
                            groupsFrom = Screen.TODAY
                            screen = Screen.GROUPS
                        },
                        reloadKey = lists.reloadKey,
                        onSettings = {
                            upd.focus = false
                            screen = Screen.SETTINGS
                        },
                        onUpdateBadge = {
                            upd.focus = true
                            screen = Screen.SETTINGS
                            upd.refreshIfMissing()
                        },
                        onRefresh = refreshNow,
                    )
                }
                }
            }
        }
    }
}
