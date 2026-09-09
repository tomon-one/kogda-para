package ru.whensclass.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * С какого дня просим расписание.
 *
 * Раньше в воскресенье окно сдвигалось на следующий понедельник — и любое
 * обновление в этот день затирало прожитую неделю данными следующей. Понедельник
 * с субботой исчезали и с экрана, и из виджета, при том что приложение обещает
 * обратное: прошедшие дни остаются видны.
 *
 * Проверять это на живом телефоне пришлось бы ждать воскресенья.
 */
class WeekStartTest {

    // 7 сентября 2026 — понедельник.
    private val monday = LocalDate.of(2026, 9, 7)

    @Test
    fun `неделя всегда начинается с понедельника`() {
        for (shift in 0..6) {
            val day = monday.plusDays(shift.toLong())
            assertEquals("день $day", monday, weekStart(day))
        }
    }

    @Test
    fun `воскресенье не выбрасывает прожитую неделю`() {
        val sunday = monday.plusDays(6)

        assertEquals(monday, weekStart(sunday))
    }

    @Test
    fun `окно захватывает и завтрашний понедельник`() {
        val sunday = monday.plusDays(6)
        val last = weekStart(sunday).plusDays((DAYS - 1).toLong())

        // Восьмой день — не запас: в воскресенье человек смотрит именно на
        // завтра, и раньше ради этого жертвовали всей прошедшей неделей.
        assertEquals(monday.plusDays(7), last)
    }

    @Test
    fun `следующая неделя начинается в понедельник, а не раньше`() {
        assertEquals(monday.plusDays(7), weekStart(monday.plusDays(7)))
    }
}
