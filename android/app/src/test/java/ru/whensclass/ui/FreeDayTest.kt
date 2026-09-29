package ru.whensclass.ui

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto

/** Что пишет день без пар: преподавателю — только прежние фразы. */
class FreeDayTest {

    private val tuesday = "2026-09-29"

    @Test
    fun `следующий будний тоже пуст — студенту одно, преподавателю прежнее`() {
        assertEquals("Пар нет. Повезло дважды", freeDay(tuesday, nextFree = true))
        assertTrue(freeDay(tuesday, teacher = true, nextFree = true) != "Пар нет. Повезло дважды")
    }

    @Test
    fun `сегодня до полудня — про шторы, после — по дате`() {
        val morning = LocalDateTime.of(2026, 9, 29, 9, 0)
        assertEquals("Пар нет. Можно открыть шторы", freeDay(tuesday, now = morning))
        assertTrue(freeDay(tuesday, now = morning.withHour(13)) != "Пар нет. Можно открыть шторы")
        assertTrue(freeDay(tuesday, teacher = true, now = morning) != "Пар нет. Можно открыть шторы")
    }

    @Test
    fun `воскресенье — выходной всегда`() {
        assertEquals("Выходной", freeDay("2026-10-04", nextFree = true))
    }

    @Test
    fun `свободный следующий — будний, пришедший и без своих пар`() {
        val empty = DayDto(date = "2026-09-30")
        assertTrue(freeOwnDay(empty, emptyList()))
        assertFalse(freeOwnDay(empty.copy(absent = true), emptyList()))
        assertFalse(freeOwnDay(DayDto(date = "2026-10-04"), emptyList()))
        val foreign = DayDto(date = "2026-09-30", lessons = listOf(LessonDto(number = 1, subject = "Физика", slots = listOf(1))))
        assertTrue(freeOwnDay(foreign, listOf("ИСП-924/1", "ИСП-924/2")))
        assertFalse(freeOwnDay(foreign.copy(lessons = foreign.lessons.map { it.copy(slots = listOf(0)) }), listOf("ИСП-924/1", "ИСП-924/2")))
        assertEquals(LocalDate.parse("2026-09-30").dayOfWeek.value, 3)
    }

    @Test
    fun `экран давно не трогали — в пустой сегодняшний день другая строка, только студенту`() {
        val now = LocalDateTime.of(2026, 9, 29, 15, 0)
        val idle = "Непросто решить, чем занять свободный день, да?"
        assertEquals(idle, freeDay(tuesday, now = now, idle = true))
        assertEquals(idle, freeDay(tuesday, nextFree = true, now = now, idle = true))
        assertTrue(freeDay(tuesday, teacher = true, now = now, idle = true) != idle)
        assertTrue(freeDay("2026-09-30", now = now, idle = true) != idle)
        assertTrue(freeDay(tuesday, now = now) != idle)
    }
}
