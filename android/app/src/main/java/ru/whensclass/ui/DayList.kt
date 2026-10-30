package ru.whensclass.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.UnreadDay
import ru.whensclass.widget.collegeToday
import ru.whensclass.widget.currentLessonNumber
import ru.whensclass.widget.formatDayTitle
import ru.whensclass.widget.parseTime
import ru.whensclass.widget.plural

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
    /** Подписи выбранных групп у пар: названиями или номерами. */
    groupsByName: Boolean = true,
    /** Полоска дней недели над списком ([WeekStrip]) — у своего расписания. */
    weekStrip: Boolean = false,
    /** «Стекло»: список заходит под шапку и вкладки, они его размывают. Над списком ничего своего. */
    underHead: Boolean = false,
) {
    val now = rememberNow(schedule.bellsOn(collegeToday()))
    val days = remember(schedule) { daysWithGaps(schedule) }
    val shift = if (header != null) 1 else 0

    // Открываемся на сегодняшнем дне, а не на понедельнике. Позиция задаётся
    // при создании списка, а не прокруткой после первого кадра: прокрутка
    // успевает показать понедельник и уехать с него на глазах. Дня нет —
    // ближайший к нему ([dayIndex]).
    val target = startDay ?: openingDay(schedule.bellsOn(today), now).toString()
    val opening = remember(days, target) { dayIndex(days, target) + shift }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = opening)

    // Поворот: в альбомной плашки уходят в список первой строкой ([header]), и
    // номер строки, сохранённый в книжной, показал бы день раньше. Помним день.
    var topDay by rememberSaveable { mutableStateOf<String?>(null) }
    var topOffset by rememberSaveable { mutableIntStateOf(0) }
    val restored = remember { topDay to topOffset }
    val daysNow by rememberUpdatedState(days)
    val shiftNow by rememberUpdatedState(shift)
    LaunchedEffect(listState) {
        val (day, offset) = restored
        if (day != null && daysNow.isNotEmpty()) {
            listState.scrollToItem(dayIndex(daysNow, day) + shiftNow, offset)
        }
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                daysNow.getOrNull(index - shiftNow)?.let { topDay = it.date; topOffset = offset }
            }
    }

    // Какой день ещё надо показать: экран живёт и через ночь, а начальная
    // позиция задаётся один раз. Новые сутки — снова сегодняшний день; нужного
    // дня пока нет в окне — ждём, пока обновление его принесёт. Переживает
    // поворот, чтобы тот не отменял пролистанное человеком.
    var wanted by rememberSaveable { mutableStateOf<String?>(target) }
    val firstToday = rememberSaveable { today.toString() }
    LaunchedEffect(today) {
        if (today.toString() != firstToday) wanted = today.toString()
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
        listState.scrollToItem(dayIndex(days, want) + shift)
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
    val shownDay by remember(days, shift) {
        derivedStateOf {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull {
                it.index >= shift && it.offset + it.size - maxOf(it.offset, 0) >= it.size / 2
            } ?: info.visibleItemsInfo.lastOrNull { it.index >= shift }
            item?.let { days.getOrNull(it.index - shift)?.date }
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
                    val seen = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index + shift }
                    if (seen != null) {
                        listState.animateScrollBy(
                            seen.offset.toFloat(),
                            androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        )
                    } else {
                        listState.animateScrollToItem(index + shift)
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
        header?.let { item(key = "header") { it() } }
        itemsIndexed(days, key = { _, day -> day.date }) { index, day ->
            Box(modifier = Modifier.appear(appearing.value, index + shift - firstAtStart)) {
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

/** Поле над полоской дней. */
private val STRIP_TOP = 6.dp

private val WEEK_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Полоска дней недели (week.js сайта): неделя дня, который наверху списка, он же
 * выделен, точка — сегодня. Выделение — одна плашка, она переезжает от дня к дню.
 */
@Composable
private fun WeekStrip(
    modifier: Modifier = Modifier,
    /** «Стекло»: размывать список под полоской. */
    glass: GlassSource? = null,
    shownDay: () -> LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    /** Палец повёл полоску вверх-вниз — листать список под ней. */
    onDrag: (Float) -> Unit,
) {
    val shown = shownDay()
    val monday = shown.minusDays((shown.dayOfWeek.value - 1).toLong())
    val index = shown.dayOfWeek.value - 1
    // Плашка едет только в отрисовке: при листании полоска не пересчитывается на
    // каждом кадре, а цвет подписи меняется сразу — под плашкой это не видно.
    val pill = remember { androidx.compose.animation.core.Animatable(index.toFloat()) }
    LaunchedEffect(index) {
        pill.animateTo(
            index.toFloat(),
            androidx.compose.animation.core.tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        )
    }
    val pillColor = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.isDark
    // У «Стекла» полоска висит над списком: размыто только под ней самой,
    // вокруг список виден как есть — без отдельной полосы во всю ширину.
    Box(modifier = modifier.fillMaxWidth().holdTouches().padding(start = 12.dp, end = 12.dp, top = STRIP_TOP, bottom = 8.dp)) {
    if (glass != null) {
        Box(
            Modifier
                .matchParentSize()
                // Само стекло рисует список под полоской (GlassSource); здесь — только где оно.
                .onGloballyPositioned { glass.window = it.boundsInRoot() },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .then(if (glass != null) Modifier.border(1.dp, if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(14.dp)) else Modifier)
            .padding(4.dp)
            // Одно касание на всю полоску вместо семи кнопок: при листании
            // полоска перестраивается дешевле. Чтецу экрана — каждый день отдельно (ниже).
            .draggable(
                rememberDraggableState { onDrag(it) },
                androidx.compose.foundation.gestures.Orientation.Vertical,
            )
            .pointerInput(monday) {
                detectTapGestures { offset ->
                    val i = (offset.x / (size.width / 7f)).toInt().coerceIn(0, 6)
                    onPick(monday.plusDays(i.toLong()))
                }
            }
            .drawBehind {
                val cell = size.width / 7
                drawRoundRect(
                    pillColor,
                    topLeft = androidx.compose.ui.geometry.Offset(cell * pill.value, 0f),
                    size = androidx.compose.ui.geometry.Size(cell, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                )
            },
    ) {
        for (i in 0 until 7) {
            val date = monday.plusDays(i.toLong())
            val on = i == index
            val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "${WEEK_NAMES[i]}, ${date.dayOfMonth}"
                        onClick(label = "Перейти к дню") { onPick(date); true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 4.dp, bottom = 7.dp),
                ) {
                    Text(
                        WEEK_NAMES[i],
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) fg else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                    )
                }
                if (date == today) {
                    // Точка — сегодня.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 2.dp)
                            .size(4.dp)
                            .background(if (on) fg else MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
    }
}

/** Первый день не раньше [date]; все раньше — последний. */
internal fun dayIndex(days: List<DayDto>, date: String): Int {
    val index = days.indexOfFirst { it.date >= date }
    return if (index >= 0) index else days.lastIndex.coerceAtLeast(0)
}

/**
 * Дни, которых в ответе нет, а по листу колледжа они есть: воскресений в
 * листе не бывает вовсе, сервер их не присылает, и так же молча пропал бы
 * любой будний день без строки. Такой день становится пустой карточкой
 * «выходной» — как у дневного виджета, сверяющего дату с `cov`.
 *
 * Дыры заполняются только между первым и последним пришедшим днём и только
 * внутри `cov`: за краем листа расписания может и не быть.
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

/**
 * Пометки дня, который сервер не прочитал (unreadNotes в schedule.js сайта):
 * своего — без имени, у преподавателя — с группами из `ug` (и `uk`, где пары
 * прежние), у других выбранных групп — с их именами.
 */
/** Строка карточки «Сегодня» 2 апреля (как на сайте, `ui/days.js`). */
private const val BIRTHDAY = "🎂 Сегодня у автора день рождения. Он не вправе отменить пары, но очень хотел бы"

internal fun unreadNotes(day: DayDto): List<String> {
    val base = "Сервер не смог прочитать этот день"
    val out = mutableListOf<String>()
    val own = day.unread
    if (own != null && day.unreadGroups.isNotEmpty()) {
        val with = if (day.unreadGroups.size > 1) "ними" else "ней"
        out += "$base у ${joinNames(day.unreadGroups)}: " +
            if (own == UnreadDay.KEPT) "пары с $with — какими были до этого." else "пар с $with может не хватать."
        if (own == UnreadDay.MISSING && day.unreadKept.isNotEmpty()) {
            val kept = if (day.unreadKept.size > 1) "ними" else "ней"
            out += "$base у ${joinNames(day.unreadKept)}: пары с $kept — какими были до этого."
        }
    } else if (own != null) {
        out += base + if (own == UnreadDay.KEPT) " в таблице: пары — какими были до этого." else " в таблице."
    }
    for (kind in listOf(UnreadDay.KEPT, UnreadDay.MISSING)) {
        val names = day.unreadOthers.filter { it.kind == kind }.map { it.name }
        if (names.isEmpty()) continue
        val whose = if (names.size > 1) "их" else "её"
        out += "$base у ${joinNames(names)}: " +
            if (kind == UnreadDay.KEPT) "$whose пары — какими были до этого." else "$whose пар здесь нет."
    }
    return out
}

// Больше трёх групп (день без даты задевает все) — числом: список занял бы экран.
private fun joinNames(names: List<String>): String {
    val n = names.size
    if (n > 3) return "$n " + if (n % 10 == 1 && n % 100 != 11) "группы" else "групп"
    return if (n > 1) names.dropLast(1).joinToString(", ") + " и " + names.last() else names.first()
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
            DayHeader(date?.let(::formatDayTitle) ?: day.date, isToday, past, daySummary(day.lessons, groups, bells))

            val notes = unreadNotes(day) + listOfNotNull(
                BIRTHDAY.takeIf { isToday && !teacher && date?.monthValue == 4 && date.dayOfMonth == 2 },
            )
            // Свой день не прочитан — «пар нет» тут было бы неправдой.
            val unknown = day.unread == UnreadDay.MISSING
            notes.forEachIndexed { index, note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (index == 0) 12.dp else 4.dp,
                        bottom = if (index == notes.lastIndex && unknown && day.lessons.isEmpty()) 16.dp else 4.dp),
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

/**
 * Что написать в свободный день. Новость хорошая, поэтому не теми же словами,
 * что об отсутствии данных. Строка выбирается по дате, чтобы у одного дня она
 * не менялась.
 */
internal fun freeDay(
    date: String,
    teacher: Boolean = false,
    /** Следующий день — будний, пришёл с сервера и тоже без своих пар. */
    nextFree: Boolean = false,
    now: java.time.LocalDateTime? = null,
    /** Экран открыт [IDLE_MILLIS] без касаний. */
    idle: Boolean = false,
): String {
    val day = runCatching { LocalDate.parse(date) }.getOrNull()
        ?: return "Пар нет"
    // В воскресенье пар не бывает никогда — радоваться нечему.
    if (day.dayOfWeek == DayOfWeek.SUNDAY) return "Выходной"
    // Преподавателю — только прежние фразы: новые пишутся студенту.
    if (!teacher) {
        if (idle && now != null && now.toLocalDate() == day) return "Непросто решить, чем занять свободный день, да?"
        if (nextFree) return "Пар нет. Повезло дважды"
        if (now != null && now.toLocalDate() == day && now.hour < 12) return "Пар нет. Можно открыть шторы"
    }
    val phrases = if (teacher) FREE else FREE + FREE_STUDENT
    return phrases[day.dayOfYear % phrases.size]
}

/**
 * С какого дня открыть список (openingDay в schedule.js сайта): сегодня, а когда
 * последний звонок сегодня уже прозвенел — завтра, как «Аудитории».
 * Сегодняшние пары остаются выше.
 */
internal fun openingDay(todayBells: Map<String, List<String>>, now: LocalDateTime): LocalDate {
    val today = now.toLocalDate()
    val last = todayBells.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: return today
    val end = parseTime(todayBells[last.toString()]?.getOrNull(1)) ?: return today
    return if (!now.toLocalTime().isBefore(end)) today.plusDays(1) else today
}

/**
 * Под названием дня: «2 пары · до 12:10» (daySummary в schedule.js сайта). Свои и
 * не отменённые; считаются номера — две подгруппы в одну пару дают одну.
 */
internal fun daySummary(lessons: List<LessonDto>, groups: List<String>, bells: Map<String, List<String>>): String? {
    val numbers = lessons
        .filter { !it.isCancelled && (groups.isEmpty() || 0 in it.slots) }
        .map { it.number }
        .distinct()
    if (numbers.isEmpty()) {
        // Своих пар нет, у других выбранных есть: «Пары только у /2».
        if (groups.isEmpty()) return null
        val slots = lessons.filter { !it.isCancelled }.flatMap { it.slots }.filter { it != 0 }.distinct()
        return if (slots.isEmpty()) null else "Пары т" + onlyAt(slots, groups).drop(1)
    }
    val end = bells[numbers.max().toString()]?.getOrNull(1)?.takeIf { it.isNotEmpty() }
    val count = plural(numbers.size, "пара", "пары", "пар")
    return if (end != null) "$count · до $end" else count
}

/** День будний, пришёл с сервера и без своих пар — для «повезло дважды». */
internal fun freeOwnDay(day: DayDto, groups: List<String>): Boolean {
    val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return false
    if (day.absent || day.unread == UnreadDay.MISSING || date.dayOfWeek == DayOfWeek.SUNDAY) return false
    return if (groups.isEmpty()) day.lessons.isEmpty() else day.lessons.none { 0 in it.slots }
}

/**
 * День, которого в ответе нет, а по листу он есть: воскресенье или будень без
 * строки (праздник). Не фраза свободного дня — та утверждала бы, что колледж
 * выложил день пустым, — а то же, что пишет виджет.
 */
private fun absentDay(date: String): String {
    val day = runCatching { LocalDate.parse(date) }.getOrNull()
    // Просто «Выходной»: «пар в этот день нет» повторяло бы его же.
    return "Выходной"
}

/** Про будни, у которых пар не оказалось. Редкая новость, и хорошая. */
private val FREE = listOf(
    "Пар нет. Повезло",
    "Пар нет. Это не ошибка",
    "Пар нет. Совсем",
    "Пусто. Так тоже бывает",
)

/** Ещё одна — только студенту. */
private val FREE_STUDENT = listOf("Пар нет. Можно одичать")

/** Сколько экран стоит нетронутым, пока строка свободного дня не сменится. */
internal const val IDLE_MILLIS = (5 * 60 + 8) * 1000L

@Composable
private fun DayHeader(title: String, isToday: Boolean, past: Boolean = false, summary: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
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

/**
 * Матовое стекло под полоской дней. Рисует его сам список, поверх себя, в
 * прямоугольнике полоски ([window]): лист со светом того же места экрана (так
 * стекло не отделяется от фона и закрывает чёткие строки под собой) и свою же
 * копию, размытую. Копию, нарисованную полоской из чужого слоя, Android при
 * листании не обновлял: под полоской застывал первый кадр.
 */
internal class GlassSource(
    val blur: androidx.compose.ui.graphics.layer.GraphicsLayer,
    val dark: Boolean,
    val screen: androidx.compose.ui.geometry.Size,
    /** Низ вкладок на экране: от верха экрана до него — тоже стекло; null — только полоска. */
    val head: androidx.compose.runtime.State<Float>?,
    /** Читается при отрисовке: список перерисовывает стекло на каждом шаге листания. */
    val moved: () -> Any?,
) {
    /** Где список на экране. */
    var origin = androidx.compose.ui.geometry.Offset.Zero

    var flip = false

    /** Где полоска на экране; null — ещё не разложена. */
    var window by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
}

/** Во сколько раз уменьшена копия, которую размывает стекло. */
private const val GLASS_SCALE = 0.25f

internal fun Modifier.glassSource(source: GlassSource): Modifier =
    onGloballyPositioned { source.origin = it.positionInRoot() }
        .drawWithContent {
            source.moved()
            val strip = source.window?.translate(-source.origin)
            if (strip == null) {
                drawContent()
                return@drawWithContent
            }
            if ((source.head?.value ?: 0f) > 0f) {
                // Шапка, вкладки и полоска — прямоугольник во всю ширину от верха
                // экрана до низа полоски (в щели между ними список иначе виден
                // чётким). Чёткий список — только ниже, здесь только размытая
                // копия, а сквозь неё — свет за экраном: закрывать чёткий список
                // листом на всю шапку стоило рывка на каждом кадре листания.
                val window = androidx.compose.ui.geometry.Rect(0f, -source.origin.y, size.width, strip.bottom)
                clipRect(top = window.bottom) { this@drawWithContent.drawContent() }
                // Под шапкой лежат плашки и подписи: там список смазан сильнее и
                // проступает слабее, иначе текст поверх него плохо читается.
                drawBlurred(source, window, 18.dp.toPx(), 0.45f)
                return@drawWithContent
            }
            drawContent()
            val outline = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(strip, androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())))
            }
            clipPath(outline) {
                drawRect(glassBase(source.dark), strip.topLeft, strip.size)
                drawRect(glowTopBrush(source.dark, source.screen, source.origin), strip.topLeft, strip.size)
                this@drawWithContent.drawBlurred(source, strip, 10.dp.toPx(), 1f)
            }
        }

/** Размытая копия списка в [window]. До Android 12 размытия нет — тогда ничего. */
private fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.drawBlurred(
    source: GlassSource,
    window: androidx.compose.ui.geometry.Rect,
    radius: Float,
    alpha: Float,
) {
    if (android.os.Build.VERSION.SDK_INT < 31) return
    // Радиус каждый раз чуть другой: иначе Android считает размытый слой
    // прежним и при листании показывает его старую картинку.
    source.flip = !source.flip
    // Размывается копия вчетверо меньше, потом растягивается.
    val k = GLASS_SCALE
    val r = radius * k + if (source.flip) 0.01f else 0f
    source.blur.alpha = alpha
    // Размытие растекается за края слоя: без обрезки под полоской оставалась
    // мутная полоса шириной в радиус размытия.
    source.blur.clip = true
    source.blur.renderEffect = androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Clamp)
    source.blur.record(size = androidx.compose.ui.unit.IntSize((window.width * k).toInt().coerceAtLeast(1), (window.height * k).toInt().coerceAtLeast(1))) {
        scale(k, androidx.compose.ui.geometry.Offset.Zero) {
            translate(-window.left, -window.top) { this@drawBlurred.drawContent() }
        }
    }
    translate(window.left, window.top) {
        scale(1f / k, androidx.compose.ui.geometry.Offset.Zero) { drawLayer(source.blur) }
    }
}
