package ru.whensclass.widget

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.glance.layout.size
import java.time.LocalDate
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto

/** «Неделя 7–12 сент.» по крайним дням расписания; без дат — просто «Неделя». */
internal fun weekTitle(days: List<DayDto>, today: LocalDate): String {
    // Считаем по тем дням, что видны: прожитые виджет не показывает, и «7–12»
    // над списком, который начинается со вторника, сбивает с толку.
    val dates = days
        .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        .filterNot { it.isBefore(today) || it.isAfter(today.plusDays(WEEK_AHEAD)) }
    val from = dates.minOrNull()
    val to = dates.maxOrNull()
    return if (from == null || to == null) "Неделя" else formatWeekRange(from, to)
}

/** Недельный виджет — сегодня и шесть дней вперёд. */
internal const val WEEK_AHEAD = 6L

/** Шапка с логотипом и группой плюс отступы — то, что списку не достаётся. */
internal val HEADER_SPACE = 62.dp

/** Высоты строк — сняты с живого экрана: о размерах разметки виджет не сообщает. */
private val TITLE_ROW = 21.dp
private val LESSON_ROW = 19.dp
private val EMPTY_ROW = 17.dp
internal val SUMMARY_ROW = 21.dp

/** Раскладка недели: [open] ближайших дней парами, [shown] дней всего, остальные — «и ещё N дней». */
internal data class WeekFit(val open: Int, val shown: Int)

/**
 * Сколько ближайших дней показать парами и сколько дней — вообще. [heights] —
 * высота каждого дня парами.
 *
 * День не пропадает молча: не хватило места на пары — остаётся строка со
 * сводкой, не хватило и на сводки — «и ещё N дней» (у загруженного
 * преподавателя бывает 31 пара за шесть дней). Разворачиваются ближайшие дни:
 * дальние всё равно уточняют в приложении.
 *
 * Сегодняшний разворачивается даже ценой хвоста недели в «и ещё N дней»,
 * иначе в тяжёлые дни не было бы ни пар, ни подсветки идущей. Пустой
 * сегодняшний — нет: сводка скажет то же.
 */
internal fun fitWeek(
    heights: List<Float>,
    free: Float,
    summary: Float,
    firstHasLessons: Boolean = true,
): WeekFit {
    val n = heights.size
    // Сколько дней всего поместится, если первые [open] развернуть: остальные
    // — сводками, а не влезли все — со строкой «и ещё N дней». null — не
    // влезают и развёрнутые.
    fun shown(open: Int): Int? {
        val used = heights.take(open).sum()
        if (used + (n - open) * summary <= free) return n
        val room = ((free - used - summary) / summary).toInt()
        return if (room < 0) null else open + room.coerceAtMost(n - open - 1)
    }
    val whole = (n downTo 0).firstOrNull { shown(it) == n }
    return when {
        whole != null && whole > 0 -> WeekFit(whole, n)
        n > 0 && firstHasLessons && shown(1) != null -> WeekFit(1, shown(1)!!)
        whole != null -> WeekFit(0, n)
        else -> WeekFit(0, (shown(0) ?: 0).coerceAtLeast(1))
    }
}

internal fun dayHeight(day: WeekDay): Dp =
    TITLE_ROW + if (day.lessons.isEmpty()) EMPTY_ROW else LESSON_ROW * day.lessons.size


/** День недели в виджете: заголовок и пары, если они есть. */
internal class WeekDay(
    val date: LocalDate,
    val title: String,
    val isToday: Boolean,
    val lessons: List<LessonDto>,
    val absent: Boolean = false,
    /** Сервер день не прочитал: пары прежние или их нет вовсе. */
    val unread: ru.whensclass.data.UnreadDay? = null,
)

internal fun weekDays(days: List<DayDto>, today: LocalDate): List<WeekDay> {
    return days.mapNotNull { day ->
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
        // Прожитые дни в недельном виджете не показываем: места мало.
        if (date.isBefore(today)) return@mapNotNull null
        // И не дальше недели вперёд: на телефоне две недели, а виджет — «Неделя».
        if (date.isAfter(today.plusDays(WEEK_AHEAD))) return@mapNotNull null
        WeekDay(date, formatWeekDay(date), date == today, day.lessons, day.absent, unread = day.unread)
    }
}
