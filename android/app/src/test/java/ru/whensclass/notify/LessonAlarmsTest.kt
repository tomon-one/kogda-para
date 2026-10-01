package ru.whensclass.notify

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Заголовок напоминания: время начала пары, а не «через 20 минут» — висящее
 * уведомление с относительным временем быстро врёт.
 */
class LessonAlarmsTest {

    private val start = LocalDateTime.of(2026, 9, 8, 12, 30)

    @Test
    fun `в заголовке время начала — сколько бы ни висело уведомление`() {
        assertEquals("12:30 — Физика", LessonAlarms.title("Физика", start, start.minusMinutes(20)))
        assertEquals("12:30 — Английский", LessonAlarms.title("Английский", start, start.minusMinutes(185)))
        assertEquals("12:30 — Физика", LessonAlarms.title("Физика", start, start))
    }

    @Test
    fun `опоздавшее напоминание не обещает будущего`() {
        assertEquals("Пара уже идёт — Физика", LessonAlarms.title("Физика", start, start.plusMinutes(15)))
    }

    @Test
    fun `напоминание снимается к концу пары`() {
        val schedule = ru.whensclass.data.ScheduleDto(
            groupId = "g", groupName = "Г", generatedAt = "2026-09-08T00:00:00Z",
            bells = mapOf("3" to listOf("12:30", "14:00")),
            days = listOf(
                ru.whensclass.data.DayDto(
                    date = "2026-09-08",
                    lessons = listOf(ru.whensclass.data.LessonDto(number = 3, subject = "Физика")),
                ),
            ),
        )
        val alarm = LessonAlarms.plan(schedule, minutes = 20, now = start.minusHours(3)).single()
        assertEquals(LocalDateTime.of(2026, 9, 8, 14, 0), alarm.end)
    }
}
