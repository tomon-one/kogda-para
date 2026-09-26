package ru.whensclass.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.whensclass.data.DayDto
import ru.whensclass.data.GroupDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.ScheduleStore
import ru.whensclass.data.sheetLink
import ru.whensclass.widget.currentLessonNumber
import ru.whensclass.widget.formatDayTitle
import ru.whensclass.widget.formatDurationLong
import ru.whensclass.widget.formatFetchedAt
import ru.whensclass.widget.formatSince
import ru.whensclass.widget.kindName
import ru.whensclass.widget.onlineLabel
import ru.whensclass.widget.roomLabel
import ru.whensclass.widget.lessonTime
import ru.whensclass.widget.plural
import ru.whensclass.widget.shortenName

/** Ширина колонки времени: «09:00–10:30» должно помещаться в одну строку. */
private val TIME_COLUMN = 92.dp

/**
 * Расписание на неделю вперёд.
 *
 * Пары выстроены таблицей: колонка времени одной ширины на все строки, иначе
 * взгляд не находит, когда начинается следующая. Строки разделены линиями во
 * всю ширину карточки — так день читается как расписание, а не как набор
 * отдельных плиток.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    startDay: String? = null,
    /** Растёт на каждое открытие с днём по живому экрану (onNewIntent). */
    startKey: Int = 0,
    groupName: String,
    teachers: List<GroupDto>?,
    loadTeacherSchedule: suspend (String) -> ScheduleDto?,
    pinnedTeachers: List<String> = emptyList(),
    onTogglePinnedTeacher: (String) -> Unit = {},
    schedule: ScheduleDto?,
    fetchedAt: Long,
    hasUpdate: Boolean,
    refreshing: Boolean,
    teacherMode: Boolean = false,
    reloadKey: Int = 0,
    groups: List<GroupDto>? = null,
    loadGroupSchedule: suspend (String) -> ScheduleDto? = { null },
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
    loadTally: suspend () -> ScheduleStore.Tally = { ScheduleStore.Tally(0, 0, 0) },
    serverBroken: Boolean = false,
    /** Сервер не отвечает телефону — не то же, что «не прочитал таблицу». */
    unreachable: Boolean = false,
    sourceUrl: String? = null,
    /** С какого момента сервер лежит (ISO, UTC) — для давности на плашке. */
    serverSince: String? = null,
    /** Группы (преподавателя) в таблице больше нет — сервер отвечает 404. */
    gone: Boolean = false,
    onRepick: () -> Unit = {},
) {
    // Часы со звонками: и «сегодня», и давность сбоя на плашке пересчитываются
    // сами (второй аудит, В20, М30).
    val now = rememberNow(schedule?.bells.orEmpty())
    val today = now.toLocalDate()

    // Преподаватель открывает приложение на своём разделе.
    // Вкладка переживает поворот экрана (третий аудит, М30 прогона 2).
    var tab by rememberSaveable(teacherMode) {
        mutableStateOf(if (teacherMode) Tab.TEACHERS else Tab.STUDENTS)
    }
    // Нажали на день в виджете по живому экрану — к своему расписанию, а не к
    // открытой чужой вкладке.
    LaunchedEffect(startKey) {
        if (startKey > 0) tab = if (teacherMode) Tab.TEACHERS else Tab.STUDENTS
    }
    val snackbar = remember { SnackbarHostState() }
    // Неудачу показываем плашкой: галочка «Расписание обновлено» загоралась и
    // тогда, когда связи не было, и человек уходил уверенным в свежих данных.
    LaunchedEffect(refreshError) {
        refreshError?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }
    val scope = rememberCoroutineScope()

    var showTally by remember { mutableStateOf(false) }
    if (showTally) {
        TallyDialog(loadTally = loadTally, onDismiss = { showTally = false })
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                // Своя плашка: у Material по умолчанию она светлая в любой теме.
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
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
                            // Единственное место, где в шапку попадает
                            // произвольно длинный текст: у преподавателя
                            // тут фамилия, имя и отчество целиком, до
                            // тридцати семи знаков. Без ограничения оно
                            // расползалось на три строки и наезжало на
                            // время обновления под собой.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Долгое нажатие по названию группы — счёт ответов.
                            // Ничего не подсказывает, что он здесь: на то и
                            // расчёт. Обычное нажатие не занято, но и не нужно.
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
                        // Почему крестик — у каждой причины своё: пропажа
                        // группы — не «сервер не смог» (М24 прогона 2).
                        brokenWhy = when {
                            refreshFailed -> "Не удалось обновить расписание"
                            gone -> if (teacherMode) "Вас нет в таблице" else "Группы нет в таблице"
                            unreachable -> "Сервер расписания не отвечает"
                            serverBroken -> "Сервер не смог обновить расписание"
                            else -> null
                        },
                        onRefresh = onRefresh,
                    )
                    IconButton(onClick = if (hasUpdate) onUpdateBadge else onSettings) {
                        // Точка над шестерёнкой: вышла новая сборка. Нажатие
                        // открывает настройки сразу на разделе обновления.
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                            if (hasUpdate) {
                                // Кружок со стрелкой вниз: обычная точка не
                                // говорит, что именно случилось, а буква «↓»
                                // в бейдже выглядела кривовато.
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
                ),
            )
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
        // В таблицу — к своей колонке и сегодняшнему дню, если сервер
        // рассказал, где они; иначе просто в книгу.
        val plates: @Composable () -> Unit = {
            if (gone) Gone(groupName, teacherMode, onRepick)
            if (serverBroken) {
                ServerBroken(
                    sheetLink(schedule, today, sourceUrl), serverSince, schedule?.generatedAt, now, unreachable,
                )
            }
        }
        // Мало высоты — альбомная ориентация, половина экрана: плашки едут
        // вместе со списком своих пар, а не отнимают у него всё место. Шапка
        // не прокручивается, и списку оставалось 0–30 dp (М29 прогона 2).
        val platesInList = maxHeight < 480.dp && tab == Tab.STUDENTS && !teacherMode &&
            !schedule?.days.isNullOrEmpty()
        Column(modifier = Modifier.fillMaxSize()) {
            if (!platesInList) plates()
            ScheduleTabs(
                current = tab,
                teacherMode = teacherMode,
                onPick = { picked ->
                    if (picked.ready) {
                        tab = picked
                    } else {
                        // Раздел откроется, когда колледж опубликует эти листы.
                        scope.launch { snackbar.showSnackbar(picked.emptyMessage) }
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            when (tab) {
                Tab.STUDENTS -> if (teacherMode) {
                    // У преподавателя своей группы нет: раздел студентов —
                    // это список групп, чьё расписание можно посмотреть.
                    TeacherScreen(
                        teachers = groups,
                        loadSchedule = loadGroupSchedule,
                        pinned = pinnedGroups,
                        onTogglePin = onTogglePinnedGroup,
                        reloadKey = reloadKey,
                        searchLabel = "Поиск по названию группы",
                        listName = "Список групп",
                        othersTitle = "Другие группы",
                        endNote = { n ->
                            "Всё. " +
                                plural(n, "группа", "группы", "групп") +
                                "."
                        },
                    )
                    return@Column
                }

                Tab.TEACHERS -> {
                    // Своё расписание преподавателя объясняется так же, как у
                    // студента. Раньше ветка выходила раньше этих проверок, и
                    // при days=[] преподаватель видел пустой экран, а при
                    // schedule==null — чужие фамилии вместо своего (второй
                    // аудит, В13).
                    if (teacherMode && explainMissing(schedule, today, sourceUrl)) return@Column
                    TeacherScreen(
                        teachers = teachers,
                        loadSchedule = loadTeacherSchedule,
                        pinned = pinnedTeachers,
                        onTogglePin = onTogglePinnedTeacher,
                        reloadKey = reloadKey,
                        // В роли преподавателя своё расписание уже загружено.
                        ownSchedule = if (teacherMode) schedule else null,
                        // День, на который нажали в виджете, терялся: вкладка
                        // преподавателя открывалась всегда на сегодня.
                        startDay = if (teacherMode) startDay else null,
                        startKey = startKey,
                        // Себя отмечаем, только когда человек и правда
                        // преподаватель: у студента это просто чужая фамилия.
                        selfId = if (teacherMode) selfTeacherId else null,
                    )
                    return@Column
                }

                else -> Unit
            }

        if (explainMissing(schedule, today, sourceUrl, loading = refreshing)) return@Column
        schedule ?: return@Column

        ScheduleDays(
            schedule = schedule,
            today = today,
            startDay = startDay,
            startKey = startKey,
            header = if (platesInList) plates else null,
        )
        }
        }
    }
}

/**
 * Кнопка обновления.
 *
 * Крутится, пока идёт запрос, и обязательно доводит оборот до конца: значок,
 * замерший на половине поворота, выглядит как зависший. Закончив, показывает
 * галочку — короткое «готово», которого иначе не видно, когда расписание не
 * изменилось.
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

    // Ключ — «крутимся», а не «идёт запрос»: иначе окончание запроса отменяло
    // бы эту же корутину, и значок замирал бы, едва тронувшись.
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
        // Без Crossfade: он держит в дереве оба значка и заводит вторую анимацию
        // поверх первой. На первых запусках, пока код ещё не прогрет, это и
        // давало рывки у самой заметной анимации приложения.
        if (done && brokenWhy != null) {
            // Запрос прошёл, но сервер отдал прежнее расписание: галочка
            // здесь обещала бы свежесть, которой нет (учебная тревога
            // 14 сентября). Подробности — на плашке под шапкой.
            Icon(Icons.Default.Close, contentDescription = brokenWhy)
        } else if (done) {
            Icon(Icons.Default.Check, contentDescription = "Расписание обновлено")
        } else {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Обновить расписание",
                // Поворот в фазе отрисовки. Через Modifier.rotate(angle.value)
                // значение читалось при сборке дерева, и каждый кадр пересобирал
                // экран целиком.
                modifier = Modifier.graphicsLayer { rotationZ = angle.value },
            )
        }
    }
}

/** Разделы расписания. Пересдачи и экзамены колледж публикует отдельными листами. */
enum class Tab(val title: String, val ready: Boolean, val emptyMessage: String = "") {
    STUDENTS("Студентам", true),
    TEACHERS("Преподавателям", true),
    // Говорим, когда появится, а не «пока пусто»: пустая вкладка без срока
    // читается как недоделка, со сроком — как план. Про пересдачи важно
    // сказать сразу, что личных не будет: в листе колледжа нет колонки
    // группы, и собрать их оттуда нельзя ни при каком разборе.
    RETAKES("Пересдачи", false, "Будут списком по предметам: в листе не написано, чьи они"),
    EXAMS("Экзамены", false, "Появятся к сессии — колледж выкладывает их в декабре"),
}

/**
 * Порядок вкладок в верхнем ряду.
 *
 * Преподавателю первым нужен раздел преподавателей: он открывает приложение
 * ради своих пар, а не чужих.
 */
private fun topTabs(teacherMode: Boolean): List<Tab> =
    if (teacherMode) listOf(Tab.TEACHERS, Tab.STUDENTS) else listOf(Tab.STUDENTS, Tab.TEACHERS)

@Composable
private fun ScheduleTabs(current: Tab, teacherMode: Boolean, onPick: (Tab) -> Unit) {
    val top = topTabs(teacherMode)
    Column {
        // Полоска рисуется только в том ряду, где выбранная вкладка: иначе
        // подчёркнутыми оказываются сразу две.
        TabRow(
            selectedTabIndex = top.indexOf(current).coerceAtLeast(0),
            containerColor = MaterialTheme.colorScheme.background,
            divider = {},
            indicator = { positions ->
                val index = top.indexOf(current)
                if (index >= 0) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[index]),
                    )
                }
            },
        ) {
            top.forEach { TabButton(it, current, onPick) }
        }
        TabRow(
            selectedTabIndex = (current.ordinal - 2).coerceIn(0, 1),
            containerColor = MaterialTheme.colorScheme.background,
            divider = {},
            indicator = { positions ->
                // Полоску под нижним рядом рисуем, только когда там и правда
                // выбран раздел: иначе она подчёркивает пустое место.
                if (current.ordinal >= 2) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[current.ordinal - 2]),
                    )
                }
            },
        ) {
            TabButton(Tab.RETAKES, current, onPick)
            TabButton(Tab.EXAMS, current, onPick)
        }
    }
}

/**
 * Объяснение вместо пустого экрана.
 *
 * Пустой раздел без единого слова читается как поломка, поэтому везде, где
 * показывать нечего, приложение говорит почему.
 */
@Composable
private fun Explanation(title: String, text: String, sourceUrl: String? = null, busy: Boolean = false) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp).padding(bottom = 4.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        // Кто виноват — колледж не выложил или мы не нашли лист — отсюда
        // не видно. Зато видно, где лежит ответ.
        sourceUrl?.let { url ->
            ActionButton(
                label = "Открыть таблицу колледжа",
                onClick = { openLink(context, url) },
            )
        }
    }
}

@Composable
private fun TabButton(tab: Tab, current: Tab, onPick: (Tab) -> Unit) {
    Tab(
        selected = tab == current,
        onClick = { onPick(tab) },
        text = {
            Text(
                tab.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                // На разделённом экране «Преподавателям» в свою колонку не
                // влезает, и без многоточия непонятно, что подпись урезана.
                overflow = TextOverflow.Ellipsis,
                // Неготовые разделы видно, но они приглушены.
                color = if (tab.ready) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * Дни расписания списком. Общий для вкладок «Студентам» и «Преподавателям»:
 * различие лишь в том, что у преподавателя вместо его имени стоит группа.
 */
@Composable
fun ScheduleDays(
    schedule: ScheduleDto,
    today: LocalDate,
    modifier: Modifier = Modifier,
    startDay: String? = null,
    startKey: Int = 0,
    /** Первой строкой списка — например, плашки, когда шапке тесно. */
    header: (@Composable () -> Unit)? = null,
) {
    val now = rememberNow(schedule.bells)
    val days = remember(schedule) { daysWithGaps(schedule) }
    val shift = if (header != null) 1 else 0

    // Открываемся на сегодняшнем дне: неделя показывается с понедельника, и без
    // этого расписание начинается с прожитых дней.
    //
    // Позиция задаётся при создании списка, а не прокруткой после первого кадра.
    // Прокрутка успевала показать понедельник и уехать с него на глазах — тем
    // заметнее, чем медленнее запуск, а самый медленный он как раз первый.
    // Дня нет — ближайший к нему: окно прошлой недели открывалось на своём
    // понедельнике, а не на конце (третий аудит, М33 прогона 1).
    val target = startDay ?: today.toString()
    val opening = remember(days, target) { dayIndex(days, target) + shift }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = opening)

    // Какой день ещё надо показать. Позиция списка задаётся только при его
    // создании, а экран живёт и через ночь: наутро значок открывал вчерашний
    // день, а в понедельник — конец следующей недели (В2 прогона 2). Поэтому:
    // новые сутки — снова сегодняшний день; нужного дня пока нет в окне —
    // ждём, пока обновление его принесёт (М33 прогона 1). Сохраняется через
    // поворот: пролистанное человеком поворот не отменяет (М30 прогона 2).
    var wanted by rememberSaveable { mutableStateOf<String?>(target) }
    val firstToday = rememberSaveable { today.toString() }
    LaunchedEffect(today) {
        if (today.toString() != firstToday) wanted = today.toString()
    }
    // Нажали на день в виджете, а экран жив: к этому дню.
    LaunchedEffect(startKey) {
        if (startKey > 0 && startDay != null) wanted = startDay
    }
    LaunchedEffect(wanted, days) {
        val want = wanted ?: return@LaunchedEffect
        if (days.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(dayIndex(days, want) + shift)
        if (days.any { it.date >= want }) wanted = null
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header?.let { item(key = "header") { it() } }
        items(days, key = { it.date }) { day ->
            DayCard(day, schedule.bells, now)
        }
    }
}

/** Первый день не раньше [date]; все раньше — последний. */
internal fun dayIndex(days: List<DayDto>, date: String): Int {
    val index = days.indexOfFirst { it.date >= date }
    return if (index >= 0) index else days.lastIndex.coerceAtLeast(0)
}

/**
 * Дни, которых в ответе нет, а по листу колледжа они есть.
 *
 * Воскресений в листе не бывает вовсе, поэтому сервер такой день не присылает —
 * и суббота в списке сменялась понедельником без единого слова. Для воскресенья
 * это ещё можно было угадать, но так же молча пропал бы любой другой день,
 * которого в данных не оказалось.
 *
 * Дневной виджет это различает с первого аудита: он сверяет дату с `cov` и
 * говорит «выходной». Здесь то же самое, только пустой карточкой — она уже
 * умеет говорить, что пар нет.
 *
 * Дыры заполняются только между первым и последним пришедшим днём и только
 * внутри `cov`: выдумывать дни за краем листа мы не вправе, там расписания
 * может и не быть.
 */
internal fun daysWithGaps(schedule: ScheduleDto): List<DayDto> {
    val present = schedule.days
    if (present.size < 2) return present
    val cover = schedule.coverage
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        .takeIf { it.size == 2 } ?: return present
    val known = present.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
    val from = known.minOrNull() ?: return present
    val to = known.maxOrNull() ?: return present

    val have = present.associateBy { it.date }
    val out = mutableListOf<DayDto>()
    var day = from
    while (!day.isAfter(to)) {
        val date = day.toString()
        val existing = have[date]
        when {
            existing != null -> out += existing
            !day.isBefore(cover[0]) && !day.isAfter(cover[1]) ->
                out += DayDto(date = date, absent = true)
        }
        day = day.plusDays(1)
    }
    return out
}

@Composable
private fun DayCard(
    day: DayDto,
    bells: Map<String, List<String>>,
    now: LocalDateTime,
) {
    val today = now.toLocalDate()
    val date = remember(day.date) { runCatching { LocalDate.parse(day.date) }.getOrNull() }
    val isToday = date == today
    val past = date != null && date.isBefore(today)
    // Момент — параметром от часов со звонками (rememberNow в ScheduleDays):
    // раньше он брался при компоновке карточки, и подсветка со звонком не
    // двигалась, пока экран открыт (дефект 7 в handoff).
    val current = if (isToday) currentLessonNumber(bells, today, now) else null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        // Прошедший день не выбрасываем — иногда нужно вспомнить, что было
        // в начале недели, — но приглушаем, чтобы он не спорил с сегодняшним.
        // Цветом, а не прозрачностью: alpha 0,45 давала контраст 2:1–3:1, и
        // вчерашнюю аудиторию было не прочесть (третий аудит, М22 прогона 2).
        Column {
            DayHeader(date?.let(::formatDayTitle) ?: day.date, isToday, past)

            if (day.lessons.isEmpty()) {
                Text(
                    if (day.absent) absentDay(day.date) else freeDay(day.date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            } else {
                day.lessons.forEachIndexed { index, lesson ->
                    // Линия во всю ширину карточки — расписание, а не плитки.
                    if (index > 0) HorizontalDivider()
                    // Отменённая пара в своё время — не «идёт сейчас» (М17
                    // прогона 2).
                    LessonRow(lesson, bells, isNow = lesson.number == current && !lesson.isCancelled, past)
                }
                // День, в котором отменили всё до единой пары. Случай редкий
                // и по-своему счастливый: пары показать надо, а сказать о нём
                // больше нечего.
                if (day.lessons.all { it.isCancelled }) {
                    Text(
                        "Всё отменили. Завидую",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * Что написать в свободный день.
 *
 * Единственное место в приложении, где новость хорошая, — грех говорить о ней
 * теми же двумя словами, что и об отсутствии данных. Строка выбирается по дате,
 * поэтому у одного и того же дня она всегда одна: подмигнуть — не то же самое,
 * что мельтешить.
 */
private fun freeDay(date: String): String {
    val day = runCatching { LocalDate.parse(date) }.getOrNull()
        ?: return "Пар нет"
    // Заголовок карточки день уже назвал, поэтому повторять его тут нечем.
    // В воскресенье пар не бывает никогда — радоваться нечему, это просто
    // так устроено, и слово выбрано соответствующее.
    return if (day.dayOfWeek == DayOfWeek.SUNDAY) "Выходной"
    else FREE[day.dayOfYear % FREE.size]
}

/**
 * День, которого в ответе нет, а по листу он есть: воскресенье или будень без
 * строки (праздник). Не «пар нет. Это не ошибка» — это утверждало бы, что
 * колледж выложил день пустым, — а то же, что пишет виджет (третий аудит, М26
 * прогона 1).
 */
private fun absentDay(date: String): String {
    val day = runCatching { LocalDate.parse(date) }.getOrNull()
    return if (day?.dayOfWeek == DayOfWeek.SUNDAY) "Выходной" else "Выходной: пар в этот день нет"
}

/** Про будни, у которых пар не оказалось. Редкая новость, и хорошая. */
private val FREE = listOf(
    "Пар нет. Повезло",
    "Пар нет. Это не ошибка",
    "Пар нет. Совсем",
    "Пусто. Так тоже бывает",
)

@Composable
private fun DayHeader(title: String, isToday: Boolean, past: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isToday) {
            // Красная метка слева: сегодняшний день находится взглядом сразу.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text(
            title.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = when {
                isToday -> MaterialTheme.colorScheme.primary
                past -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(
                start = if (isToday) 12.dp else 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
        )
    }
}

@Composable
private fun LessonRow(
    lesson: LessonDto,
    bells: Map<String, List<String>>,
    isNow: Boolean,
    past: Boolean = false,
) {
    val main = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    // Колонка растёт со шрифтом: «09:00–10:30» в sp, колонка в dp, и с
    // крупным шрифтом конец пары уходил в многоточие (М28 прогона 2).
    val timeColumn = TIME_COLUMN * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                else MaterialTheme.colorScheme.surface
            )
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(timeColumn)) {
            Text(
                "${lesson.number} пара",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            lessonTime(bells, lesson.number)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isNow) FontWeight.Bold else FontWeight.Medium,
                    color = if (isNow) MaterialTheme.colorScheme.primary else main,
                    maxLines = 1,
                    // Колонка времени шириной ровно под «09:00–10:30» при
                    // обычном шрифте. С крупным системным диапазон перестаёт
                    // помещаться, и обрыв без многоточия читается как другое
                    // время.
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isNow) {
                Text(
                    "идёт сейчас",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        VerticalDivider(
            modifier = Modifier.fillMaxHeight().padding(start = 12.dp, end = 12.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                lesson.subject,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = main,
                textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
            )

            Spacer(Modifier.height(4.dp))
            // Тип занятия и аудитория — то, ради чего сюда и заглядывают,
            // поэтому они идут сразу под названием и заметно, а не подписью
            // мелким шрифтом.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Аудитория ужимается, тип занятия — нет: «Лекция» короче
                // и важнее, а длинное текстовое название места иначе съедало
                // строку целиком.
                val shrink = Modifier.weight(1f, fill = false)
                if (lesson.isOnline) {
                    Place(onlineLabel(lesson).replaceFirstChar { it.uppercase() }, muted = past, modifier = shrink)
                } else {
                    // Ни кабинета, ни ссылки — так и говорим: пустая строка
                    // читается как «не загрузилось», хотя в таблице там пусто.
                    val room = roomLabel(lesson.room)
                    Place(room ?: "Не указано", muted = room == null || past, modifier = shrink)
                }
                kindName(lesson.kind)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            lesson.url?.let { OnlineLink(it) }

            // Подпись группы стоит вместо преподавателя: у преподавателя в
            // своём расписании важно, кому читается пара, а у пары из второй
            // подгруппы — чья она. В остальных случаях там преподаватель.
            val group = lesson.groups
            if (group != null) {
                Text(
                    group,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                lesson.teachers.forEach {
                    Text(
                        shortenName(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (lesson.isCancelled) {
                Text(
                    lesson.note?.let { "Отменена — $it" } ?: "Отменена",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                // У замены — «вместо: Математика»: без неё новая пара в том же
                // часе выглядела бы ошибкой таблицы (третий аудит, В2 прогона 1).
                lesson.note?.let {
                    Text(
                        it.replaceFirstChar { c -> c.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        }
    }
}

/**
 * Место занятия — единственная выделенная пометка в строке.
 *
 * Раньше рядом стояла вторая такая же, для типа занятия, и две капсулы подряд
 * выбивались из спокойного вида остальных строк.
 */
@Composable
private fun Place(text: String, muted: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        // Отсутствие места — не то, что нужно подсвечивать цветом.
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        // Две строки: аудитория бывает и текстом — «Спортзал Б.Хмельницкого
        // 2», «выездная, с 15.00», — и в одну строку её хвост уходил в
        // многоточие рядом с типом занятия (третий аудит, В7 прогона 2). Без
        // многоточия обрыв читался как самостоятельное короткое название, а
        // без weight эта строка отбирала место у типа, и «Лекция» пропадала.
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(end = 8.dp),
    )
}


/**
 * Плашка «расписание застряло у нас».
 *
 * Без неё наш сбой выглядел ровно как «колледж ещё не выложил»: те же
 * слова, и человек спокойно ждал расписания, которого мы уже не принесём.
 * Поэтому здесь прямо сказано, чья это беда, и дана дорога в обход — та
 * самая таблица, ради которой всё и затевалось.
 *
 * Цвета берём из своих: `errorContainer` в схеме не задан, и Material
 * подставил бы туда чужой розовый.
 */
@Composable
private fun ServerBroken(
    sourceUrl: String?,
    since: String?,
    generatedAt: String?,
    now: LocalDateTime,
    unreachable: Boolean = false,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // Сервер не ответил телефону — не «не прочитал таблицу»: таблица
            // тут ни при чём, и уведомление в тот же момент говорит «не
            // отвечает» (третий аудит, М13 прогона 1).
            Text(
                if (unreachable) "Сервер расписания не отвечает" else "Сбой на нашем сервере",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                (if (unreachable) "Телефон не достучался до нашего сервера, "
                else "Не удалось прочитать таблицу, ") +
                    "приложение показывает последнее, что пришло. Пары могли поменяться.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Давность — отдельной строкой: двое суток сбоя не должны
            // выглядеть как минута рядом с честным «обновлено 5 минут назад».
            since?.let {
                Text(
                    // gen — не «получено», а последняя правка таблицы, которую
                    // сервер успел забрать (третий аудит, М74 прогона 2).
                    "Сбой с ${formatSince(it, now.atZone(ru.whensclass.widget.COLLEGE_ZONE).toInstant())}." +
                        (generatedAt?.let { g ->
                            " Последняя правка таблицы, которую сервер успел забрать, — ${formatReceived(g)}."
                        } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            sourceUrl?.let { url ->
                ActionButton(
                    label = "Открыть таблицу колледжа",
                    onClick = { openLink(context, url) },
                )
            }
        }
    }
}

/** «11 сентября в 10:40» — когда сервер в последний раз забрал из таблицы новое. */
private fun formatReceived(iso: String): String = runCatching {
    java.time.LocalDateTime.ofInstant(java.time.Instant.parse(iso), java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM 'в' HH:mm", java.util.Locale("ru")))
}.getOrDefault(iso)

/**
 * Плашка «группы в таблице больше нет».
 *
 * Сервер отвечает 404 при здоровом состоянии — группу переименовали,
 * разделили или убрали, и старый идентификатор в настройках телефона
 * больше ни на что не указывает. Раньше это выглядело как отвалившаяся
 * сеть: молча прежнее расписание, потом «ещё не опубликовано». Единственный
 * честный ответ — выбрать себя заново из нынешнего списка.
 */
@Composable
private fun Gone(groupName: String, teacherMode: Boolean, onRepick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                if (teacherMode) "Вас больше нет в таблице" else "Группы больше нет в таблице",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                (if (teacherMode) "Имени «$groupName» в таблице колледжа больше нет: "
                 else "Группы «$groupName» в таблице колледжа больше нет: ") +
                    "переименовали, разделили или убрали. На экране — последнее, " +
                    "что было. Выберите заново из нынешнего списка.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionButton(label = "Выбрать заново", onClick = onRepick)
        }
    }
}

/** За сколько грузится таблица колледжа. Перемерено 8 сентября 2026 года. */
private const val SHEET_SECONDS = 8

/**
 * Счёт ответов: сколько раз таблицу открывать не пришлось.
 *
 * Ровно то, ради чего всё затевалось, только числом. Прячется под долгим
 * нажатием на название группы: ничто на неё не указывает, и не должно.
 *
 * Два счётчика показаны отдельно, а не сложены в одно красивое число:
 * приложение человек открывает сам, а виджет отвечает и без него. Выдать
 * второе за первое было бы враньём ради красоты.
 */
@Composable
private fun TallyDialog(loadTally: suspend () -> ScheduleStore.Tally, onDismiss: () -> Unit) {
    var tally by remember { mutableStateOf<ScheduleStore.Tally?>(null) }
    LaunchedEffect(Unit) { tally = loadTally() }
    // Пока считается — не показываем ничего: окно, мигнувшее нулями и
    // тут же переписавшее себя, выглядит поломкой.
    val counted = tally ?: return

    val total = counted.opens + counted.draws
    val seconds = total * SHEET_SECONDS
    val spent = if (seconds < 60) {
        plural(seconds.toInt(), "секунду", "секунды", "секунд")
    } else {
        formatDurationLong((seconds / 60).toInt())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Таблицу вы не открывали") },
        text = {
            Column {
                Text(
                    plural(total.toInt(), "раз", "раза", "раз"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // «3 раза», а не «3 раз» (третий аудит, М25 прогона 2).
                    plural(counted.opens.toInt(), "раз", "раза", "раз") + " ответило приложение, " +
                        "${counted.draws} — виджеты. Каждый раз это было вместо " +
                        "таблицы колледжа.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Она грузится $SHEET_SECONDS секунд. Считайте, что $spent " +
                        "вы потратили на что-то другое.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (counted.since > 0L) {
                    Spacer(Modifier.height(8.dp))
                    val day = Instant.ofEpochMilli(counted.since)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    Text(
                        "Счёт идёт ${tallySince(day, ru.whensclass.widget.collegeToday())}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ладно") } },
    )
}

/**
 * «с 23 сентября», «с сегодняшнего дня»: после «с» — родительный падеж. Было
 * «с сегодня, 25 сентября, пятница» (третий аудит, М25 прогона 2).
 */
internal fun tallySince(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "с сегодняшнего дня"
    today.minusDays(1) -> "со вчерашнего дня"
    else -> "с " + day.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale("ru")))
}

/**
 * Ссылка на онлайн-занятие: две кнопки и ничего больше.
 *
 * Сама ссылка строкой не показывается. Она всё равно не влезала и обрывалась
 * после домена, а строка и ряд кнопок под ней делали идущую пару выше соседних
 * почти в полтора раза. Что занятие онлайн, сказано строкой выше.
 *
 * Открыть нужно чаще, чем скопировать, поэтому «Открыть» стоит первой. Но обе
 * на виду: ссылку иногда надо переслать, а не открыть.
 */
@Composable
private fun OnlineLink(url: String) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        // Куда ведёт кнопка. Полный адрес занимал строку и всё равно обрывался
        // после домена, но совсем без него проверить ссылку нечем: она приходит
        // из таблицы, которую заполняют руками.
        // Хост не с площадки вебинаров колледжа — предупреждаем прямо: ссылку
        // мог вписать кто угодно, кто правит таблицу (второй аудит, М37).
        val known = remember(url) { ru.whensclass.data.isKnownWebinar(url) }
        val color = if (known) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
        // Хост ужимается, хвост — нет: ради последних цифр хвост и показан, а
        // одной строкой с многоточием в конце они пропадали первыми на узком
        // телефоне (третий аудит, М20 прогона 2).
        Row {
            Text(
                if (known) host(url) else "чужой адрес: " + host(url),
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            linkEnd(url)?.let {
                Text(
                    " · …/$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        // Волосок между кнопками: вплотную их рамки сливались в одну рамку с
        // перегородкой, а зазор пошире разносил пару в две разные кнопки.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LinkButton("Открыть") { openLink(context, url) }
            LinkButton("Копировать") { copyLink(context, url) }
        }
    }
}

/**
 * «https://my.mts-link.ru/j/100000001/20000000028» -> «20000000028»: хвост
 * к хосту, строкой «my.mts-link.ru · …/20000000028».
 *
 * И хост, и хвост. По хвосту отличают три пары подряд в одной комнате от трёх
 * разных: расходятся последние цифры. А по хосту видно, куда ведёт ссылка:
 * её пишет в ячейку любой, кто правит таблицу, и без хоста фишинговая ссылка
 * неотличима от настоящей. Хост возвращали ещё 8 сентября, и он снова пропал
 * (второй аудит, М37).
 */
private fun linkEnd(url: String): String? =
    runCatching { Uri.parse(url).pathSegments }
        .getOrNull()
        ?.lastOrNull { it.isNotBlank() }
        ?.takeLast(16)

/** «https://my.mts-link.ru/j/144...» -> «my.mts-link.ru»: запасной вид без пути. */
private fun host(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        // Кнопки идут парой внутри карточки, поэтому и поля, и высота у них
        // меньше обычных: иначе пара с вебинаром распухает. Область нажатия
        // от этого не страдает — Compose держит её не меньше 48 dp сам,
        // добирая невидимыми полями вокруг.
        modifier = Modifier.height(28.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        // Тонкая рамка: без неё текст кнопки неотличим от подписи рядом, и
        // непонятно, куда именно нажимать.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

internal fun openLink(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "Нечем открыть ссылку", Toast.LENGTH_SHORT).show()
    }
}

private fun copyLink(context: android.content.Context, url: String) =
    copyToClipboard(context, "Ссылка на занятие", url, "Ссылка скопирована")


/**
 * Объяснение вместо пустого экрана, когда расписания нет. true — объяснили.
 * [loading] — оно как раз загружается: сразу после выбора группы экран писал
 * «Проверьте интернет», хотя запрос только ушёл (третий аудит, М2 прогона 2).
 */
@Composable
internal fun explainMissing(
    schedule: ScheduleDto?,
    today: java.time.LocalDate,
    sourceUrl: String?,
    loading: Boolean = false,
): Boolean {
    if (schedule == null && loading) {
        Explanation(
            title = "Загружаю расписание",
            text = "Обычно это несколько секунд.",
            busy = true,
        )
        return true
    }
    if (schedule == null) {
        Explanation(
            title = "Расписание ещё не загружено",
            text = "Проверьте интернет и нажмите обновление вверху. " +
                "Если не помогает, напишите @toomonn.",
        )
        return true
    }
    if (schedule.days.isEmpty()) {
        // Сервер ответил, но дней в ответе нет: так бывает в воскресенье,
        // когда следующий лист ещё не выложен. Раньше проверка была только
        // на «расписания нет вовсе», и экран оставался пустым без слов.
        Explanation(
            title = "На эти дни расписания нет",
            text = "Колледж выкладывает его на неделю-полторы вперёд. " +
                "Но если пары сегодня идут, значит расписание застряло " +
                "у нас — тогда смотрите первоисточник.",
            sourceUrl = sheetLink(schedule, today, sourceUrl),
        )
        return true
    }
    return false
}
