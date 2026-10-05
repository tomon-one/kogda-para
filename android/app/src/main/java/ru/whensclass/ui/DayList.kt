package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    /** Первой строкой списка — например, плашки, когда шапке тесно. */
    header: (@Composable () -> Unit)? = null,
    /** Подписи выбранных групп у пар: названиями или номерами. */
    groupsByName: Boolean = true,
) {
    val now = rememberNow(schedule.bellsOn(collegeToday()))
    val days = remember(schedule) { daysWithGaps(schedule) }
    val shift = if (header != null) 1 else 0

    // Открываемся на сегодняшнем дне, а не на понедельнике. Позиция задаётся
    // при создании списка, а не прокруткой после первого кадра: прокрутка
    // успевает показать понедельник и уехать с него на глазах. Дня нет —
    // ближайший к нему ([dayIndex]).
    val target = startDay ?: today.toString()
    val opening = remember(days, target) { dayIndex(days, target) + shift }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = opening)

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
    LaunchedEffect(startKey) {
        if (startKey > 0) wanted = startDay ?: today.toString()
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

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    touches++
                }
            }
        },
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header?.let { item(key = "header") { it() } }
        itemsIndexed(days, key = { _, day -> day.date }) { index, day ->
            DayCard(
                day, schedule.bellsOf(day), now, teacher = schedule.isTeacher,
                groups = schedule.groupNames, groupsByName = groupsByName,
                nextFree = days.getOrNull(index + 1)?.let { freeOwnDay(it, schedule.groupNames) } == true,
                idle = idle,
            )
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        // Прошедший день не выбрасываем, но приглушаем — цветом, а не
        // прозрачностью: при прозрачности контраст падает так, что вчерашнюю
        // аудиторию не прочесть.
        Column {
            DayHeader(date?.let(::formatDayTitle) ?: day.date, isToday, past)

            val notes = unreadNotes(day)
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            } else {
                // Своих пар нет, а у выбранных групп есть: «пар нет» — над их
                // серыми строками, как пишет виджет, иначе беглый взгляд видит
                // свои пары.
                if (!unknown && groups.isNotEmpty() && day.lessons.none { 0 in it.slots }) {
                    Text(
                        freeDay(day.date, teacher, nextFree, now, idle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                    HorizontalDivider()
                }
                day.lessons.forEachIndexed { index, lesson ->
                    // Линия во всю ширину карточки — расписание, а не плитки.
                    if (index > 0) HorizontalDivider()
                    // Отменённая пара в своё время — не «идёт сейчас». Пара
                    // только у других групп — тоже: человек на ней не сидит.
                    val foreign = groups.isNotEmpty() && 0 !in lesson.slots
                    LessonRow(
                        lesson, bells,
                        isNow = lesson.number == current && !lesson.isCancelled && !foreign,
                        past = past,
                        groups = groups,
                        groupsByName = groupsByName,
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
