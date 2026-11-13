package ru.whensclass.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.whensclass.data.GroupDto
import ru.whensclass.data.RetakesDto
import ru.whensclass.data.RoomsAnswer
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.ScheduleStore
import ru.whensclass.data.sheetLink
import ru.whensclass.widget.collegeToday
import ru.whensclass.widget.formatFetchedAt
import ru.whensclass.widget.plural


/** Главный экран: расписание на неделю, вкладки студентов и преподавателей, плашки сбоя и пропажи группы. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    /** Нижний ряд вкладок стоит при листании (настройка «Второй ряд вкладок не уезжает»). */
    rowStays: Boolean = false,
    startDay: String? = null,
    /** Растёт на каждое открытие с днём по живому экрану (onNewIntent). */
    startKey: Int = 0,
    groupName: String,
    teachers: List<GroupDto>?,
    loadTeacherSchedule: suspend (String) -> ru.whensclass.data.ScheduleAnswer,
    pinnedTeachers: List<String> = emptyList(),
    onTogglePinnedTeacher: (String) -> Unit = {},
    schedule: ScheduleDto?,
    fetchedAt: Long,
    hasUpdate: Boolean,
    refreshing: Boolean,
    /** Идёт загрузка, которую ⟳ не показывает (при открытии): без снимка — «загружается», а не «проверьте интернет». */
    loading: Boolean = false,
    teacherMode: Boolean = false,
    reloadKey: Int = 0,
    groups: List<GroupDto>? = null,
    loadGroupSchedule: suspend (String) -> ru.whensclass.data.ScheduleAnswer = { ru.whensclass.data.ScheduleAnswer(null) },
    pinnedGroups: List<String> = emptyList(),
    onTogglePinnedGroup: (String) -> Unit = {},
    selfTeacherId: String? = null,
    onSettings: () -> Unit,
    onUpdateBadge: () -> Unit,
    onRefresh: () -> Unit,
    /** Последнее ручное обновление не удалось — крестик вместо галочки. */
    refreshFailed: Boolean = false,
    refreshError: String? = null,
    onErrorShown: () -> Unit = {},
    loadTally: suspend () -> ScheduleStore.Tally = { ScheduleStore.Tally(0, 0) },
    serverBroken: Boolean = false,
    /** Сервер не отвечает телефону — не то же, что «не прочитал таблицу». */
    unreachable: Boolean = false,
    /** Колледж правит таблицу (сказал автор) — не сбой сервера. */
    editing: Boolean = false,
    /** Сервер больше не обслуживает эту сборку — нужно обновить приложение. */
    unsupported: Boolean = false,
    sourceUrl: String? = null,
    /** С какого момента сервер лежит (ISO, UTC) — для давности на плашке. */
    serverSince: String? = null,
    /** Группы (преподавателя) в таблице больше нет — сервер отвечает 404. */
    gone: Boolean = false,
    onRepick: () -> Unit = {},
    /** Подписи выбранных групп у пар: названиями или номерами (настройки). */
    groupsByName: Boolean = true,
    /** Показать один раз плашку о канале новостей. */
    channelNotice: Boolean = false,
    onChannelSeen: () -> Unit = {},
    /** Лист пересдач; null — грузится или не загрузился ([retakesFailed]). */
    retakes: RetakesDto? = null,
    retakesFailed: Boolean = false,
    retakesAnswered: Boolean = false,
    onRetryRetakes: () -> Unit = {},
    /** Кабинеты дня; null — сервер не ответил или дня в таблице нет. */
    loadRooms: suspend (String?) -> RoomsAnswer = { RoomsAnswer(null) },
    roomsMemory: RoomsMemory = remember { RoomsMemory() },
) {
    // Часы со звонками: «сегодня» и давность сбоя на плашке пересчитываются сами.
    val now = rememberNow(schedule?.bellsOn(collegeToday()).orEmpty())
    val today = now.toLocalDate()

    // Преподаватель открывает приложение на своём разделе; вкладка переживает поворот. Имя в
    // «Пересдачах» нажали — открыть его во вкладке преподавателей (только на новое нажатие), с
    // ролью: вкладка у каждой роли своя ("teachers:$teacherMode").
    var openTeacher by rememberSaveable(teacherMode) { mutableStateOf<String?>(null) }
    var openTeacherKey by rememberSaveable(teacherMode) { mutableIntStateOf(0) }
    // Нажали на саму вкладку преподавателей — к тому, что было открыто на ней.
    var teachersEnterKey by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable(teacherMode) {
        mutableStateOf(if (teacherMode) Tab.TEACHERS else Tab.STUDENTS)
    }
    // Вкладка помнит своё (открытого преподавателя или группу, поиск, прокрутку): иначе при
    // переключении её экран уходит из композиции, и вернувшийся снова ищет в списке (как на сайте:
    // state.opened в today.js).
    val tabStates = rememberSaveableStateHolder()
    // Нажали на день в виджете по живому экрану — к своему расписанию. Только на новое нажатие:
    // экран переживает поворот и поход в настройки.
    var handledStart by rememberSaveable { mutableIntStateOf(startKey) }
    LaunchedEffect(startKey) {
        if (startKey != handledStart) {
            handledStart = startKey
            tab = if (teacherMode) Tab.TEACHERS else Tab.STUDENTS
        }
    }
    // «Назад» с любой вкладки, кроме своей, — к своему расписанию; открытого преподавателя или
    // группу закрывает раньше их экран (его обработчик зарегистрирован позже).
    val home = if (teacherMode) Tab.TEACHERS else Tab.STUDENTS
    BackHandler(enabled = tab != home) { tab = home }
    val snackbar = remember { SnackbarHostState() }
    // Неудачу показываем плашкой, чтобы человек не ушёл уверенным в свежих данных.
    LaunchedEffect(refreshError) {
        refreshError?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }

    var showTally by remember { mutableStateOf(false) }
    if (showTally) {
        TallyDialog(loadTally = loadTally, onDismiss = { showTally = false })
    }

    // В альбомной шапка уезжает при прокрутке вместе с вкладками (ScheduleTabs): неподвижная
    // отнимает больше половины высоты.
    val short = LocalConfiguration.current.screenHeightDp < SHORT_SCREEN_DP
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(short) { if (!short) bar.state.heightOffset = 0f }

    Scaffold(
        modifier = if (short) Modifier.nestedScroll(bar.nestedScrollConnection) else Modifier,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                // Своя плашка: у Material она светлая в любой теме, а цвета карточки сливаются с
                // карточкой под ней (Theme.kt, inverseSurface).
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            groupName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            // У преподавателя здесь ФИО целиком: без ограничения оно расползается на три строки.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Долгое нажатие по названию группы — счёт ответов,
                            // нарочно без подсказки.
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = { showTally = true })
                            },
                        )
                        Text(
                            formatFetchedAt(fetchedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    RefreshButton(
                        refreshing = refreshing,
                        // Почему крестик — у каждой причины своё.
                        brokenWhy = when {
                            refreshFailed -> "Не удалось обновить расписание"
                            gone -> if (teacherMode) "Вас нет в таблице" else "Группы нет в таблице"
                            unsupported -> "Нужно обновить приложение"
                            unreachable -> "Сервер расписания не отвечает"
                            editing -> "Колледж правит таблицу"
                            serverBroken -> "Сервер не смог обновить расписание"
                            else -> null
                        },
                        onRefresh = onRefresh,
                    )
                    IconButton(onClick = if (hasUpdate) onUpdateBadge else onSettings) {
                        // Значок над шестерёнкой: вышла новая сборка. Нажатие открывает настройки
                        // на разделе обновления.
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                            if (hasUpdate) {
                                // Кружок со стрелкой вниз: обычная точка не говорит, что случилось.
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp).offset(x = 5.dp, y = (-4).dp),
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Есть обновление",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = if (short) bar else null,
            )
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
        // В таблицу — к своей колонке и сегодняшнему дню, если сервер сказал, где они; иначе в книгу.
        val plates: @Composable () -> Unit = {
            if (gone) Gone(groupName, teacherMode, onRepick, shown = schedule != null)
            if (channelNotice) ChannelNotice(onChannelSeen)
            if (unsupported) {
                Unsupported(onUpdateBadge)
            } else if (serverBroken) {
                ServerBroken(
                    sheetLink(schedule, today, sourceUrl), serverSince, unreachable, editing,
                )
            }
        }
        // Мало высоты (альбомная, половина экрана): плашки едут со списком, иначе неподвижная шапка
        // отнимает всё место. Дней нет — плашки над объяснением, которое тоже листается.
        val platesInList = maxHeight < 480.dp && tab == Tab.STUDENTS && !teacherMode
        // Преподаватель и открытые расписания: плашки ставит сам экран — в список дней, а без него над собой.
        val platesInScreen = maxHeight < 480.dp && (tab == Tab.TEACHERS || tab == Tab.STUDENTS && teacherMode)
        Column(modifier = Modifier.fillMaxSize()) {
            if (!platesInList && !platesInScreen) Column(Modifier.fillMaxWidth().holdTouches()) { plates() }
            ScheduleTabs(
                current = tab,
                teacherMode = teacherMode,
                live = retakes?.activeOn(today) == true,
                rowStays = rowStays,
                // Под плашками: у «Стекла» список заходит под них и под шапку.
                modifier = Modifier.zIndex(-1f),
                onPick = { picked ->
                    if (picked == Tab.TEACHERS && tab != Tab.TEACHERS) teachersEnterKey++
                    tab = picked
                },
            ) {
                when (tab) {
                    Tab.STUDENTS -> if (teacherMode) {
                        // У преподавателя своей группы нет: раздел студентов — список групп, чьё
                        // расписание можно посмотреть.
                        tabStates.SaveableStateProvider("groups") {
                            TeacherScreen(
                                now = now,
                                teachers = groups,
                                loadSchedule = loadGroupSchedule,
                                pinned = pinnedGroups,
                                onTogglePin = onTogglePinnedGroup,
                                reloadKey = reloadKey,
                                unsupported = unsupported,
                                plates = if (platesInScreen) plates else null,
                                searchLabel = "Поиск по названию группы",
                                listName = "Список групп",
                                othersTitle = "Другие группы",
                                endNote = { n ->
                                    "Всё. " +
                                        plural(n, "группа", "группы", "групп") +
                                        "."
                                },
                            )
                        }
                        return@ScheduleTabs
                    }

                    Tab.TEACHERS -> {
                        // Отсутствие своего расписания преподавателя объясняется так же, как у
                        // студента, — до списка чужих фамилий. Пропажа без снимка — объяснение на
                        // плашке: «проверьте интернет» под ней было бы неправдой. Своих дней нет, а
                        // ответ есть — объяснение над списком: без него имя из «Пересдач» вело бы в
                        // тупик.
                        val noOwnDays = teacherMode && schedule != null && schedule.days.isEmpty()
                        if (teacherMode && !noOwnDays &&
                            (gone && schedule == null || explainMissing(schedule, today, sourceUrl, loading = refreshing || loading))
                        ) {
                            return@ScheduleTabs
                        }
                        // С ролью — своя память: у преподавателя это его вкладка.
                        tabStates.SaveableStateProvider("teachers:$teacherMode") {
                          Column {
                            if (noOwnDays) {
                                Text(
                                    "На эти дни ваших пар в таблице нет: колледж их ещё не выложил.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            TeacherScreen(
                                now = now,
                                teachers = teachers,
                                loadSchedule = loadTeacherSchedule,
                                pinned = pinnedTeachers,
                                onTogglePin = onTogglePinnedTeacher,
                                reloadKey = reloadKey,
                                unsupported = unsupported,
                                plates = if (platesInScreen) plates else null,
                                // В роли преподавателя своё расписание уже загружено.
                                ownSchedule = if (teacherMode && !noOwnDays) schedule else null,
                                // День, на который нажали в виджете.
                                startDay = if (teacherMode) startDay else null,
                                startKey = startKey,
                                // Себя отмечаем, только когда человек и правда преподаватель.
                                selfId = if (teacherMode) selfTeacherId else null,
                                openId = openTeacher,
                                openKey = openTeacherKey,
                                enterKey = teachersEnterKey,
                                onLeaveOpened = { tab = Tab.RETAKES },
                            )
                          }
                        }
                        return@ScheduleTabs
                    }

                    Tab.ROOMS -> {
                        tabStates.SaveableStateProvider("rooms") {
                            RoomsScreen(loadRooms, today, now.toLocalTime(), roomsMemory, unsupported)
                        }
                        return@ScheduleTabs
                    }

                    Tab.RETAKES -> {
                        tabStates.SaveableStateProvider("retakes") {
                            // Не загрузились — при входе на вкладку пробовать снова, а не ждать возврата в приложение.
                            LaunchedEffect(Unit) { if (retakesFailed) onRetryRetakes() }
                            RetakesScreen(retakes, retakesFailed, today, onRetryRetakes, answered = retakesAnswered, unsupported = unsupported, teachers = teachers, onOpenTeacher = { id ->
                                openTeacher = id
                                openTeacherKey++
                                tab = Tab.TEACHERS
                            })
                        }
                        return@ScheduleTabs
                    }

                    // Листа экзаменов в таблице ещё нет: говорим, когда будет, а не показываем
                    // пустоту — она читается как недоделка.
                    Tab.EXAMS -> {
                        Explanation("Экзаменов нет", "Расписание экзаменов колледж выкладывает к сессии, в декабре.")
                        return@ScheduleTabs
                    }
                }

                val listPlates = if (platesInList) plates else null
                if (gone && schedule == null) {
                    listPlates?.let { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { it() } }
                    return@ScheduleTabs
                }
                if (explainMissing(schedule, today, sourceUrl, loading = refreshing || loading, header = listPlates)) return@ScheduleTabs
                schedule ?: return@ScheduleTabs

                ScheduleDays(
                    schedule = schedule,
                    today = today,
                    startDay = startDay,
                    startKey = startKey,
                    header = listPlates,
                    groupsByName = groupsByName,
                    weekStrip = true,
                    underHead = true,
                    clock = now,
                )
            }
        }
        }
    }
}

/**
 * Кнопка обновления. Крутится, пока идёт запрос, и доводит оборот до конца: замерший на полпути
 * значок выглядит зависшим. Закончив, показывает галочку, иначе «готово» не видно, когда расписание
 * не изменилось.
 */
@Composable
private fun RefreshButton(refreshing: Boolean, brokenWhy: String?, onRefresh: () -> Unit) {
    val angle = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    var spinning by remember { mutableStateOf(false) }
    val busy by rememberUpdatedState(refreshing)

    LaunchedEffect(refreshing) {
        if (refreshing) spinning = true
    }

    // Ключ — «крутимся», а не «идёт запрос»: иначе окончание запроса отменяло бы корутину, и значок замирал бы.
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        done = false
        do {
            angle.animateTo(angle.value + 360f, tween(450, easing = LinearEasing))
        } while (busy)
        angle.snapTo(0f)
        done = true
        delay(900)
        done = false
        spinning = false
    }

    IconButton(onClick = onRefresh, enabled = !refreshing) {
        // Без Crossfade: он держит в дереве оба значка и заводит вторую анимацию — на непрогретом коде рывки.
        if (done && brokenWhy != null) {
            // Запрос прошёл, но расписание прежнее: галочка обещала бы свежесть. Подробности — на плашке.
            Icon(Icons.Default.Close, contentDescription = brokenWhy)
        } else if (done) {
            Icon(Icons.Default.Check, contentDescription = "Расписание обновлено")
        } else {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Обновить расписание",
                // Поворот в фазе отрисовки: Modifier.rotate(angle.value) читает значение при сборке
                // и пересобирает экран каждый кадр.
                modifier = Modifier.graphicsLayer { rotationZ = angle.value },
            )
        }
    }
}
