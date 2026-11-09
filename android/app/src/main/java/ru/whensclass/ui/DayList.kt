package ru.whensclass.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.border
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import ru.whensclass.data.DayDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.UnreadDay
import ru.whensclass.widget.collegeToday
import ru.whensclass.widget.currentLessonNumber
import ru.whensclass.widget.formatDayTitle

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
    /** Строкой перед днём, на котором список открылся, — например, плашки, когда шапке тесно. */
    header: (@Composable () -> Unit)? = null,
    /** Подписи выбранных групп у пар: названиями или номерами. */
    groupsByName: Boolean = true,
    /** Полоска дней недели над списком ([WeekStrip]) — у своего расписания. */
    weekStrip: Boolean = false,
    /** «Стекло»: список заходит под шапку и вкладки, они его размывают. Над списком ничего своего. */
    underHead: Boolean = false,
) {
    val now = rememberNow(schedule.bellsOn(collegeToday()))
    val days = remember(schedule) { daysWithGaps(schedule) }

    // Открываемся на сегодняшнем дне, а не на понедельнике. Позиция задаётся
    // при создании списка, а не прокруткой после первого кадра: прокрутка
    // успевает показать понедельник и уехать с него на глазах. Дня нет —
    // ближайший к нему ([dayIndex]).
    // День из виджета или напоминания остаётся у экрана и после того, как
    // прошёл: прошедший — уже не повод открываться на нём.
    val target = startDay?.takeIf { it >= today.toString() } ?: openingDay(schedule.bellsOn(today), now).toString()
    val rows = remember(days, target, header != null) {
        DayRows(if (header != null && days.isNotEmpty()) dayIndex(days, target) else -1)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = rows.toward(dayIndex(days, target)))

    // Поворот: в альбомной плашки уходят в список строкой ([header]), и
    // номер строки, сохранённый в книжной, показал бы другой день. Помним день.
    var topDay by rememberSaveable { mutableStateOf<String?>(null) }
    var topOffset by rememberSaveable { mutableIntStateOf(0) }
    val restored = remember { topDay to topOffset }
    val daysNow by rememberUpdatedState(days)
    val rowsNow by rememberUpdatedState(rows)
    LaunchedEffect(listState) {
        val (day, offset) = restored
        if (day != null && daysNow.isNotEmpty()) {
            val index = dayIndex(daysNow, day)
            // День стоял вровень с верхом — с плашками над ним, если они там.
            if (offset == 0) listState.scrollToItem(rowsNow.toward(index))
            else listState.scrollToItem(rowsNow.of(index), offset)
        }
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (row, offset) ->
                // Наверху плашки — помним день под ними, вровень с верхом.
                val index = rowsNow.day(row)
                if (index == null) daysNow.getOrNull(row)?.let { topDay = it.date; topOffset = 0 }
                else daysNow.getOrNull(index)?.let { topDay = it.date; topOffset = offset }
            }
    }

    // Какой день ещё надо показать: экран живёт и через ночь, а начальная
    // позиция задаётся один раз. Новые сутки — снова сегодняшний день; нужного
    // дня пока нет в окне — ждём, пока обновление его принесёт. Переживает
    // поворот, чтобы тот не отменял пролистанное человеком.
    var wanted by rememberSaveable { mutableStateOf<String?>(target) }
    // День, к которому список уже уводили: поворот после полуночи не уводит снова.
    var seenToday by rememberSaveable { mutableStateOf(today.toString()) }
    LaunchedEffect(today) {
        if (today.toString() != seenToday) {
            seenToday = today.toString()
            wanted = today.toString()
        }
    }
    // Нажали на день в виджете, а экран жив: к этому дню.
    var handledStart by rememberSaveable { mutableIntStateOf(startKey) }
    LaunchedEffect(startKey) {
        if (startKey != handledStart) {
            handledStart = startKey
            wanted = startDay ?: openingDay(schedule.bellsOn(today), now).toString()
        }
    }
    // Человек сам повёл список — ждать обещанного дня больше не надо, иначе
    // следующее обновление утащит его обратно.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is androidx.compose.foundation.interaction.DragInteraction.Start) wanted = null
        }
    }
    LaunchedEffect(wanted, days) {
        val want = wanted ?: return@LaunchedEffect
        if (days.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(rows.toward(dayIndex(days, want)))
        if (days.any { it.date >= want }) wanted = null
    }

    // Экран открыт и никто его не трогал [IDLE_MILLIS] — в пустой сегодняшний
    // день другая строка ([freeDay]). Счёт — пока экран на виду; касание или
    // прокрутка — сначала.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val visible = lifecycle.currentStateFlow.collectAsState().value
        .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    var touches by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(touches, visible) {
        idle = false
        if (visible) {
            kotlinx.coroutines.delay(IDLE_MILLIS)
            idle = true
        }
    }

    // Какой день наверху — для выделения в полоске: первый, который виден хотя бы
    // наполовину; край прошлого дня, торчащий сверху, выделение не перехватывает.
    val shownDay by remember(days, rows) {
        derivedStateOf {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull {
                rows.day(it.index) != null && it.offset + it.size - maxOf(it.offset, 0) >= it.size / 2
            } ?: info.visibleItemsInfo.lastOrNull { rows.day(it.index) != null }
            item?.let { rows.day(it.index)?.let(days::getOrNull)?.date }
        }
    }

    // Нажатый в полоске день держит выделение, пока человек сам не поведёт список,
    // а у конца списка — пока список стоит в самом низу: последние дни не докрутить
    // до верха, и выделение иначе уходило к дню, который наверху.
    var picked by remember { mutableStateOf<String?>(null) }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is androidx.compose.foundation.interaction.DragInteraction.Start) {
                touched = true
                if (listState.canScrollForward) picked = null
            }
        }
    }
    // Список повели к дню не пальцем (полночь, день из виджета) — выделение за ним.
    LaunchedEffect(wanted) {
        if (wanted != null) picked = null
    }
    LaunchedEffect(listState) {
        // Сам повёл список от низа — нажатый день больше не держит.
        androidx.compose.runtime.snapshotFlow { listState.canScrollForward }.collect { away ->
            if (away && touched) picked = null
        }
    }
    val scope = rememberCoroutineScope()
    // Дни всплывают по очереди один раз — когда список только появился (как на сайте).
    // Только при первом открытии приложения: при возврате на вкладку список
    // показывается сразу, иначе переключение вкладок подтормаживало.
    val appearing = remember { mutableStateOf(!appearedOnce) }
    // Номер первой видимой строки при появлении — один раз: следить за ним при
    // листании значило бы перерисовывать каждую карточку на каждом шаге.
    val firstAtStart = remember { listState.firstVisibleItemIndex }
    LaunchedEffect(Unit) {
        appearedOnce = true
        kotlinx.coroutines.delay(APPEAR_MILLIS)
        appearing.value = false
    }

    // «Стекло»: список уходит под полоску дней, и она размывает то, что под ней.
    val glass = weekStrip && MaterialTheme.colorScheme.background.alpha < 1f && !rememberPowerSave()
    val blurLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val darkGlass = MaterialTheme.colorScheme.isDark
    val screen = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val tabsBottom = if (underHead) LocalTabsBottom.current else null
    // Сколько от верха списка до верха экрана: на столько список вытянут вверх,
    // под вкладки и шапку. Только растёт — когда ряд вкладок уезжает, верх списка
    // поднимается, и вытянутый край не должен отставать на кадр.
    var reach by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val source = remember(blurLayer, listState, darkGlass, screen, tabsBottom) {
        GlassSource(blurLayer, darkGlass, androidx.compose.ui.geometry.Size(screen.width.toFloat(), screen.height.toFloat()), tabsBottom) {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }
    var stripHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val strip: @Composable (Modifier) -> Unit = { stripModifier ->
        WeekStrip(
            modifier = stripModifier,
            glass = if (glass) source else null,
            // Лямбдой: день читается внутри полоски, и при листании перерисовывается
            // только она, а не весь список.
            shownDay = { (picked ?: shownDay)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today },
            today = today,
            onPick = { date ->
                wanted = null
                val index = dayIndex(days, date.toString())
                picked = days.getOrNull(index)?.date
                touched = false
                scope.launch {
                    // Видимый день — мягкой прокруткой с замедлением в конце; далёкий — как умеет список.
                    val seen = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == rows.toward(index) }
                    if (seen != null) {
                        listState.animateScrollBy(
                            seen.offset.toFloat(),
                            androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        )
                    } else {
                        listState.animateScrollToItem(rows.toward(index))
                    }
                }
            },
            onDrag = { delta ->
                touched = true
                if (listState.canScrollForward) picked = null
                listState.dispatchRawDelta(-delta)
            },
        )
    }
    val list: @Composable (Modifier, androidx.compose.ui.unit.Dp) -> Unit = { listModifier, top ->
    LazyColumn(
        state = listState,
        modifier = listModifier.fillMaxWidth().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    touches++
                }
            }
        },
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        days.forEachIndexed { index, day ->
            if (header != null && index == rows.headerAt) item(key = "header") { header() }
            item(key = day.date) {
            Box(modifier = Modifier.appear(appearing.value, rows.of(index) - firstAtStart)) {
            DayCard(
                day, schedule.bellsOf(day), now, teacher = schedule.isTeacher,
                groups = schedule.groupNames, groupsByName = groupsByName,
                nextFree = days.getOrNull(index + 1)?.let { freeOwnDay(it, schedule.groupNames) } == true,
                idle = idle,
            )
            }
            }
        }
    }
    }
    if (glass) {
        Box(modifier = modifier.fillMaxSize()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val under = if (tabsBottom == null) {
                Modifier
            } else {
                // Строки стоят там же, где без этого: на сколько список вытянут
                // вверх, на столько больше отступ над первой строкой.
                Modifier
                    .onGloballyPositioned { reach = maxOf(reach, it.positionInRoot().y.roundToInt()) }
                    .layout { measurable, constraints ->
                        val height = constraints.maxHeight + reach
                        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                        layout(placeable.width, constraints.maxHeight) { placeable.place(0, -reach) }
                    }
            }
            list(Modifier.fillMaxSize().then(under).glassSource(source), with(density) { (stripHeight + reach).toDp() })
            strip(Modifier.onSizeChanged { stripHeight = it.height })
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            if (weekStrip) strip(Modifier)
            list(Modifier.weight(1f), 0.dp)
        }
    }
}

/** Сколько длится всплытие дней при появлении списка. */
private const val APPEAR_MILLIS = 700L

/** Дни уже всплывали в этом запуске приложения. */
private var appearedOnce = false

/**
 * Всплытие карточки: снизу и из прозрачного, по очереди с шагом 45 мс (motion.css
 * сайта, `.days.appear`). Только видимые при появлении; потом — как есть.
 */
@Composable
private fun Modifier.appear(active: Boolean, order: Int): Modifier {
    if (!active || order < 0 || order > 6) return this
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(order * 45L)
        progress.animateTo(1f, androidx.compose.animation.core.tween(340, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 16.dp.toPx()
    }
}

@Composable
private fun DayCard(
    day: DayDto,
    bells: Map<String, List<String>>,
    now: LocalDateTime,
    teacher: Boolean = false,
    /** Выбранные группы по порядку, первая — своя; пусто — группа одна. */
    groups: List<String> = emptyList(),
    groupsByName: Boolean = true,
    /** Следующий день — будний и тоже без своих пар ([freeOwnDay]). */
    nextFree: Boolean = false,
    /** Экран давно не трогали ([IDLE_MILLIS]). */
    idle: Boolean = false,
) {
    val today = now.toLocalDate()
    val date = remember(day.date) { runCatching { LocalDate.parse(day.date) }.getOrNull() }
    val isToday = date == today
    val past = date != null && date.isBefore(today)
    // Момент — параметром от часов со звонками (rememberNow в ScheduleDays),
    // иначе подсветка не двигается со звонком, пока экран открыт.
    val current = if (isToday) currentLessonNumber(bells, today, now) else null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        // Прошедший день не выбрасываем, но приглушаем — цветом, а не
        // прозрачностью: при прозрачности контраст падает так, что вчерашнюю
        // аудиторию не прочесть.
        Column {
            val notes = unreadNotes(day) + listOfNotNull(
                BIRTHDAY.takeIf { isToday && !teacher && date?.monthValue == 4 && date.dayOfMonth == 2 },
                date?.takeIf { isToday }?.let { holidayNote(it.monthValue, it.dayOfMonth) },
            )
            DayHeader(
                date?.let(::formatDayTitle) ?: day.date, isToday, past, daySummary(day.lessons, groups, bells),
                bottom = if (notes.isEmpty()) 8.dp else 4.dp,
            )

            // Свой день не прочитан — «пар нет» тут было бы неправдой.
            val unknown = day.unread == UnreadDay.MISSING
            notes.forEachIndexed { index, note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (index == 0) 0.dp else 4.dp,
                        bottom = when {
                            index != notes.lastIndex -> 4.dp
                            day.lessons.isNotEmpty() -> 8.dp
                            unknown -> 16.dp
                            else -> 4.dp
                        }),
                )
            }
            if (unknown && day.lessons.isEmpty()) {
                // Сказано выше.
            } else if (day.lessons.isEmpty()) {
                Text(
                    if (day.absent) absentDay(day.date) else freeDay(day.date, teacher, nextFree, now, idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            } else {
                // Своих пар нет, а у выбранных групп есть: это уже сказала сводка под
                // датой («Пары только у …»), у самих пар подпись не повторяем.
                // Отменённые сводка не считает — у них подпись остаётся.
                val allForeign = groups.isNotEmpty() && day.lessons.none { 0 in it.slots }
                day.lessons.forEach { lesson ->
                    // Отменённая пара в своё время — не «идёт сейчас». Пара
                    // только у других групп — тоже: человек на ней не сидит.
                    val foreign = groups.isNotEmpty() && 0 !in lesson.slots
                    LessonRow(
                        lesson, bells,
                        isNow = lesson.number == current && !lesson.isCancelled && !foreign,
                        past = past,
                        groups = groups,
                        groupsByName = groupsByName,
                        saidAbove = allForeign && !lesson.isCancelled,
                    )
                }
                // День, в котором отменили все свои пары.
                val own = if (groups.isEmpty()) day.lessons else day.lessons.filter { 0 in it.slots }
                if (own.isNotEmpty() && own.all { it.isCancelled }) {
                    Text(
                        // Преподавателю отмена всех пар — сорванные часы, не удача.
                        if (teacher) "Все пары отменены" else "Всё отменили. Повезло",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** Сколько экран стоит нетронутым, пока строка свободного дня не сменится. */
internal const val IDLE_MILLIS = (5 * 60 + 8) * 1000L

@Composable
private fun DayHeader(title: String, isToday: Boolean, past: Boolean = false, summary: String? = null, bottom: Dp = 8.dp) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom)) {
        Text(
            title.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                isToday -> MaterialTheme.colorScheme.primary
                past -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
