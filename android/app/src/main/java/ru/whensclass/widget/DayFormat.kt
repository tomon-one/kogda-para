package ru.whensclass.widget

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale("ru")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMMM, EEEE", RU)

fun formatDayTitle(day: LocalDate): String {
    val today = LocalDate.now()
    val prefix = when (day) {
        today -> "сегодня"
        today.plusDays(1) -> "завтра"
        today.plusDays(2) -> "послезавтра"
        else -> null
    }
    val date = day.format(DAY_FORMAT)
    return if (prefix != null) "$prefix, $date" else date
}

fun formatFetchedAt(millis: Long): String {
    if (millis <= 0) return "ещё не обновлялось"
    val moment = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    val time = moment.format(DateTimeFormatter.ofPattern("HH:mm", RU))
    return when (moment.toLocalDate()) {
        LocalDate.now() -> "обновлено в $time"
        LocalDate.now().minusDays(1) -> "обновлено вчера в $time"
        else -> "обновлено " + moment.format(DateTimeFormatter.ofPattern("d MMMM в HH:mm", RU))
    }
}

/**
 * То же время, но коротко — для шапки виджета, где на счету каждый пиксель.
 * «19:12», «вчера 21:40», «5 сен».
 */
fun formatFetchedShort(millis: Long): String {
    if (millis <= 0) return "—"
    val moment = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    val time = moment.format(DateTimeFormatter.ofPattern("HH:mm", RU))
    return when (moment.toLocalDate()) {
        LocalDate.now() -> time
        LocalDate.now().minusDays(1) -> "вчера $time"
        else -> moment.format(DateTimeFormatter.ofPattern("d MMM", RU))
    }
}

/** Данные считаем несвежими через полсуток — тогда виджет об этом говорит. */
fun isStale(millis: Long): Boolean =
    millis > 0 && System.currentTimeMillis() - millis > Duration.ofHours(12).toMillis()

fun parseTime(value: String?): LocalTime? =
    value?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

/**
 * Номер пары, которая идёт прямо сейчас, — по сетке звонков с сервера.
 * Пока сетка неизвестна, подсвечивать нечего.
 */
fun currentLessonNumber(bells: Map<String, List<String>>, day: LocalDate): Int? {
    if (bells.isEmpty() || day != LocalDate.now()) return null
    val now = LocalTime.now()
    return bells.entries.firstNotNullOfOrNull { (number, range) ->
        val start = parseTime(range.getOrNull(0))
        val end = parseTime(range.getOrNull(1))
        if (start != null && end != null && now >= start && now <= end) {
            number.toIntOrNull()
        } else {
            null
        }
    }
}

fun lessonTime(bells: Map<String, List<String>>, number: Int): String? {
    val range = bells[number.toString()] ?: return null
    val start = range.getOrNull(0) ?: return null
    val end = range.getOrNull(1) ?: return start
    return "$start–$end"
}

/** «Трухачев Даниил Дмитриевич» -> «Трухачев Д. Д.»: иначе не влезает в строку. */
fun shortenName(fullName: String): String {
    val parts = fullName.trim().split(" ").filter { it.isNotEmpty() }
    if (parts.size < 2) return fullName
    val initials = parts.drop(1).joinToString(" ") { "${it.first()}." }
    return "${parts.first()} $initials"
}
