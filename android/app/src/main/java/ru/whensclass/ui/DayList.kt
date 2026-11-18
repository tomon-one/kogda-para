package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import ru.whensclass.data.ScheduleDto
import ru.whensclass.widget.collegeToday

/** Дни расписания списком. Общий для «Студентам» и «Преподавателям»: у преподавателя вместо его имени стоит группа. */
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
    /** Как показывать названия предметов: короткие от службы или свои. */
    subjects: ru.whensclass.data.SubjectNames = ru.whensclass.data.SubjectNames(),
    /** Полоска дней недели над списком ([WeekStrip]) — у своего расписания. */
    weekStrip: Boolean = false,
    /** «Стекло»: список заходит под шапку и вкладки, они его размывают. Над списком ничего своего. */
    underHead: Boolean = false,
    /**
     * Часы экрана, если идут по звонкам этого же расписания (своё): вторые часы перекомпоновывали
     * бы экран на каждом звонке.
     */
    clock: LocalDateTime? = null,
    /** Сюда список кладёт, чем листать его извне: строка над полоской (имя преподавателя) листает как сама полоска. */
    dragSink: androidx.compose.runtime.MutableState<((Float) -> Unit)?>? = null,
    /** Картинка дня в чат: null — кнопки нет (расписание преподавателя, чужой день). */
    onImage: ((String) -> Unit)? = null,
    /** Компактный вид: карточки теснее ([compactView]). */
    compact: Boolean? = null,
) {
    val now = clock ?: rememberNow(schedule.bellsOn(collegeToday()))
    val days = remember(schedule) { daysWithGaps(schedule) }
    val tight = compactView(
        choice = compact,
        widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp /
            androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f),
    )

    // Открываемся на сегодняшнем дне, а не на понедельнике: позиция задаётся при создании списка,
    // иначе прокрутка успела бы показать понедельник. Дня нет — ближайший ([dayIndex]). День из
    // виджета или напоминания остаётся и после того, как прошёл: прошедший не повод открываться на
    // нём.
    val target = startDay?.takeIf { it >= today.toString() } ?: openingDay(schedule.bellsOn(today), now).toString()
    val rows = remember(days, target, header != null) {
        DayRows(if (header != null && days.isNotEmpty()) dayIndex(days, target) else -1)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = rows.toward(dayIndex(days, target)))

    // Поворот: в альбомной плашки уходят в список строкой ([header]), и номер строки из книжной
    // показал бы другой день. Помним день.
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

    // Какой день ещё надо показать: экран живёт и через ночь, а начальная позиция одна. Новые сутки
    // — снова сегодняшний; нужного дня нет в окне — ждём обновление. Переживает поворот.
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
    LaunchedEffect(wanted, days) {
        val want = wanted ?: return@LaunchedEffect
        if (days.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(rows.toward(dayIndex(days, want)))
        if (days.any { it.date >= want }) wanted = null
    }

    // Экран открыт и нетронут [IDLE_MILLIS] — в пустой сегодняшний день другая строка ([freeDay]).
    // Счёт — пока экран на виду; касание или прокрутка — сначала.
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

    // Какой день наверху — для выделения в полоске: первый, видимый хотя бы наполовину.
    val shownDay by remember(days, rows) {
        derivedStateOf {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull {
                rows.day(it.index) != null && it.offset + it.size - maxOf(it.offset, 0) >= it.size / 2
            } ?: info.visibleItemsInfo.lastOrNull { rows.day(it.index) != null }
            // Видны одни плашки (альбомная, окно низкое) — выделен день под ними, а не сегодня.
            val index = item?.let { rows.day(it.index) }
                ?: rows.headerAt.takeIf { at -> at >= 0 && info.visibleItemsInfo.any { it.index == at } }
            index?.let(days::getOrNull)?.date
        }
    }

    // Нажатый в полоске день держит выделение, пока человек сам не поведёт список; у конца списка —
    // пока он стоит внизу: последние дни не докрутить до верха.
    var picked by remember { mutableStateOf<String?>(null) }
    var touched by remember { mutableStateOf(false) }
    // Человек сам повёл список: ждать обещанного дня не надо (обновление утащило бы обратно), и
    // нажатый в полоске день больше не держит выделение.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is androidx.compose.foundation.interaction.DragInteraction.Start) {
                wanted = null
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
    // Дни всплывают по очереди один раз, когда список только появился (как на сайте), и только при
    // первом открытии: иначе переключение вкладок подтормаживало.
    val appearing = remember { mutableStateOf(!appearedOnce) }
    // Номер первой видимой строки при появлении — один раз: слежение при листании перерисовывало бы каждую карточку.
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
    // Сколько от верха списка до верха экрана: на столько список вытянут под вкладки и шапку.
    // Только растёт: когда ряд вкладок уезжает, вытянутый край не должен отставать на кадр.
    var reach by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val source = remember(blurLayer, listState, darkGlass, screen, tabsBottom) {
        GlassSource(blurLayer, darkGlass, androidx.compose.ui.geometry.Size(screen.width.toFloat(), screen.height.toFloat()), tabsBottom) {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }
    var stripHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Палец на полоске листает список той же цепочкой, что и сам список: иначе шапка и ряды вкладок
    // в альбомной не уезжают.
    val stripScroll = remember { androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher() }
    val stripConnection = remember { object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {} }
    val drag: (Float) -> Unit = { delta ->
        touched = true
        if (listState.canScrollForward) picked = null
        val source = androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput
        val left = delta - stripScroll.dispatchPreScroll(Offset(0f, delta), source).y
        val used = -listState.dispatchRawDelta(-left)
        stripScroll.dispatchPostScroll(Offset(0f, used), Offset(0f, left - used), source)
    }
    if (dragSink != null) androidx.compose.runtime.SideEffect { dragSink.value = drag }
    val strip: @Composable (Modifier) -> Unit = { stripModifier ->
        WeekStrip(
            modifier = stripModifier.nestedScroll(stripConnection, stripScroll),
            glass = if (glass) source else null,
            // Лямбдой: день читается внутри полоски, и при листании перерисовывается только она.
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
            onDrag = drag,
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
        verticalArrangement = Arrangement.spacedBy(if (tight) 8.dp else 12.dp),
    ) {
        days.forEachIndexed { index, day ->
            if (header != null && index == rows.headerAt) item(key = "header") { header() }
            item(key = day.date) {
            // На стыке недель — тихая строка: искать понедельник глазами не надо.
            val weekBreak = startsNextWeek(days.getOrNull(index - 1)?.date, day.date)
            Column {
            if (weekBreak) NextWeekMark()
            Box(modifier = Modifier.appear(appearing.value, rows.of(index) - firstAtStart)) {
            DayCard(
                day, schedule.bellsOf(day), now, teacher = schedule.isTeacher,
                groups = schedule.groupNames, groupsByName = groupsByName, subjects = subjects,
                nextFree = days.getOrNull(index + 1)?.let { freeOwnDay(it, schedule.groupNames) } == true,
                idle = idle,
                onImage = onImage,
                compact = tight,
            )
            }
            }
            }
        }
    }
    }
    if (glass) {
        Box(modifier = modifier.fillMaxSize()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val under = if (tabsBottom == null) {
                // Не под шапкой (открытое расписание): над полоской отступ, и в нём уходящая под
                // полоску пара была бы видна чётко.
                Modifier.drawWithContent {
                    clipRect(top = STRIP_TOP.toPx()) { this@drawWithContent.drawContent() }
                }
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
 * Всплытие карточки: снизу и из прозрачного, по очереди с шагом 45 мс (motion.css сайта,
 * `.days.appear`). Только видимые при появлении.
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

/** На стыке воскресенья и понедельника: тихая строка между линиями вместо заголовка, без карточки. */
@Composable
private fun NextWeekMark() {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        // Линии сверху и снизу: стык недель видно, не всматриваясь в подписи дней.
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "Следующая неделя",
            // Тише заголовка дня: это не день, а стык.
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Сколько экран стоит нетронутым, пока строка свободного дня не сменится. */
internal const val IDLE_MILLIS = (5 * 60 + 8) * 1000L
