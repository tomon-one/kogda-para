package ru.whensclass.widget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.whensclass.data.DayDto

/**
 * Заголовок недельного виджета.
 *
 * Комментарий в самом коде описывает уже случившуюся ошибку: «Неделя 7–12» над
 * списком, который начинается со вторника. Починка была, теста на неё не было —
 * а заголовок обязан называть те дни, что и правда показаны, иначе он врёт
 * в самом заметном месте виджета.
 */
class WeekTitleTest {

    private val today = LocalDate.now()

    private fun days(vararg shifts: Long) =
        shifts.map { DayDto(date = today.plusDays(it).toString()) }

    @Test
    fun `один оставшийся день не превращается в диапазон`() {
        // «12–12 сент.» читается как опечатка. В субботу и в последний день
        // листа виден ровно один день.
        val title = weekTitle(days(0), today)

        assertEquals(formatWeekRange(today, today), title)
        assertEquals(true, title.contains("Неделя, "))
    }

    @Test
    fun `прожитые дни в заголовок не попадают`() {
        // Виджет прожитое не показывает, значит и считать по нему нельзя:
        // ровно из-за этого заголовок когда-то и соврал.
        val title = weekTitle(days(-3, -1, 0, 2), today)

        assertEquals(formatWeekRange(today, today.plusDays(2)), title)
    }

    @Test
    fun `без дат остаётся просто Неделя`() {
        assertEquals("Неделя", weekTitle(emptyList(), today))
        assertEquals("Неделя", weekTitle(listOf(DayDto(date = "не дата")), today))
    }

    @Test
    fun `вся неделя позади — тоже просто Неделя`() {
        assertEquals("Неделя", weekTitle(days(-5, -2), today))
    }

    @Test
    fun `месяц называется один раз, пока он один`() {
        val from = LocalDate.of(2026, 9, 7)
        val to = LocalDate.of(2026, 9, 12)

        assertEquals("Неделя 7–12 сент.", formatWeekRange(from, to))
    }

    @Test
    fun `через границу месяца называются оба`() {
        val from = LocalDate.of(2026, 9, 28)
        val to = LocalDate.of(2026, 10, 3)

        assertEquals("Неделя 28 сент. – 3 окт.", formatWeekRange(from, to))
    }
}
