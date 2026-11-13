package ru.whensclass.ui

import androidx.compose.foundation.layout.size
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.UnreadDay
import ru.whensclass.widget.parseTime
import ru.whensclass.widget.plural

// Чистые правила дней: какие дни показать, подписи свободного и непрочитанного дня.
// Без Compose: их проверяют тесты.

/** Первый день не раньше [date]; все раньше — последний. */
internal fun dayIndex(days: List<DayDto>, date: String): Int {
    val index = days.indexOfFirst { it.date >= date }
    return if (index >= 0) index else days.lastIndex.coerceAtLeast(0)
}

/**
 * Строки списка дней, где перед днём [headerAt] стоят плашки (−1 — их нет): плашки над днём
 * открытия видны сразу, а не выше понедельника.
 */
internal class DayRows(val headerAt: Int) {
    /** Строка дня [index]. */
    fun of(index: Int): Int = if (headerAt in 0..index) index + 1 else index

    /** Куда листать к дню: к плашкам, если они над ним. */
    fun toward(index: Int): Int = if (index == headerAt) headerAt else of(index)

    /** День строки; null — строка плашек. */
    fun day(row: Int): Int? = when {
        headerAt < 0 || row < headerAt -> row
        row == headerAt -> null
        else -> row - 1
    }
}

/**
 * Дни, которых в ответе нет, а по листу они есть: воскресений в листе не бывает, сервер их не
 * присылает; так же молча пропал бы будний день без строки. Такой день — пустая карточка
 * «выходной», как у дневного виджета, сверяющего дату с `cov`. Дыры заполняются только между первым
 * и последним пришедшим днём и внутри `cov`: за краем листа расписания может не быть.
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

/** Строка карточки «Сегодня» 2 апреля (как на сайте, `ui/days.js`). */
internal const val BIRTHDAY = "🎂 Сегодня у автора день рождения. Он не вправе отменить пары, но очень хотел бы"

/** Поздравление карточки «Сегодня» в праздник (как на сайте, `ui/days.js`). */
internal fun holidayNote(month: Int, day: Int): String? = when (month to day) {
    1 to 7 -> "⭐ С Рождеством!"
    2 to 23 -> "🎖️ С Днём защитника Отечества!"
    3 to 8 -> "🌷 С 8 Марта!"
    5 to 1 -> "🌱 С Первомаем!"
    5 to 9 -> "🕊️ С Днём Победы!"
    6 to 12 -> "🇷🇺 С Днём России!"
    11 to 4 -> "🤝 С Днём народного единства!"
    else -> "🎄 С Новым годом!".takeIf { month == 1 && day <= 8 }
}

/**
 * Пометки дня, который сервер не прочитал (unreadNotes в schedule.js сайта): своего — без имени, у
 * преподавателя — с группами из `ug` (и `uk`, где пары прежние), у других групп — с их именами.
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

/**
 * Что написать в свободный день. Новость хорошая, поэтому не теми же словами, что об отсутствии
 * данных. Строка выбирается по дате, чтобы у дня не менялась.
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
 * С какого дня открыть список (openingDay в schedule.js сайта): сегодня, а когда последний звонок
 * прозвенел — завтра, как «Аудитории». Сегодняшние пары остаются выше.
 */
internal fun openingDay(todayBells: Map<String, List<String>>, now: LocalDateTime): LocalDate {
    val today = now.toLocalDate()
    val last = todayBells.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: return today
    val end = parseTime(todayBells[last.toString()]?.getOrNull(1)) ?: return today
    return if (!now.toLocalTime().isBefore(end)) today.plusDays(1) else today
}

/**
 * Под названием дня: «2 пары · до 12:10» (daySummary в schedule.js сайта). Свои и не отменённые;
 * считаются номера: две подгруппы дают одну пару.
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
 * День, которого в ответе нет, а по листу он есть (воскресенье, праздник). Не фраза свободного дня
 * — та утверждала бы, что колледж выложил день пустым, — а то же, что пишет виджет.
 */
internal fun absentDay(date: String): String {
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
