package ru.whensclass.data

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.widget.currentLessonNumber
import ru.whensclass.widget.lessonTime
import ru.whensclass.widget.nextLesson
import ru.whensclass.widget.nextTick
import ru.whensclass.widget.weekDays

/**
 * День со своими звонками: колледж сокращает пары перед праздником и пишет
 * время в таблице. У такого дня время, подсветка и напоминания — по его
 * звонкам, у остальных — по обычным.
 */
class DayBellsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = monday.plusDays(1)

    private val body = """
        {"v":1,"g":"isp-924-1","gn":"ИСП-924/1","gen":"2026-10-05T00:00:00Z",
         "bells":{"1":["09:00","10:30"],"2":["10:40","12:10"],"5":["16:00","17:30"]},
         "days":[
          {"d":"2026-10-05","bl":{"1":["09:00","10:00"],"2":["10:10","11:10"],"5":["13:50","14:50"]},
           "l":[{"n":1,"s":"Физика"},{"n":2,"s":"История"},{"n":5,"s":"Право"}]},
          {"d":"2026-10-06","l":[{"n":2,"s":"Химия"},{"n":5,"s":"Право"}]}]}
    """.trimIndent()

    private val schedule = json.decodeFromString<ScheduleDto>(body)

    @Test
    fun `свои звонки — у своего дня, у остальных обычные`() {
        assertEquals("10:10–11:10", lessonTime(schedule.bellsOf(schedule.days[0]), 2))
        assertEquals("10:40–12:10", lessonTime(schedule.bellsOf(schedule.days[1]), 2))
        assertEquals(schedule.bells, schedule.bellsOn(tuesday))
        // Дня в расписании нет — обычные.
        assertEquals(schedule.bells, schedule.bellsOn(monday.minusDays(2)))
        assertEquals(schedule.bells, schedule.bellsOf(null))
    }

    @Test
    fun `идущая пара и следующий звонок — по звонкам дня`() {
        val now = monday.atTime(10, 20)
        assertEquals(2, currentLessonNumber(schedule.bellsOn(monday), monday, now))
        assertEquals(monday.atTime(11, 10), nextTick(schedule.bellsOn(monday), now))
        assertEquals("История", nextLesson(schedule, now)?.lesson?.subject)
        // По обычной сетке в 12:00 шла бы вторая, по своей следующая — пятая.
        assertEquals("Право", nextLesson(schedule, monday.atTime(12, 0))?.lesson?.subject)
    }

    @Test
    fun `напоминание — за минуты до звонка этого дня`() {
        val plan = LessonAlarms.plan(schedule, minutes = 5, now = LocalDateTime.of(2026, 10, 5, 6, 0))
        assertEquals(
            listOf("2026-10-05T08:55", "2026-10-05T10:05", "2026-10-05T13:45", "2026-10-06T10:35", "2026-10-06T15:55"),
            plan.map { it.at.toString() },
        )
        assertTrue(LessonAlarms.stillOn(schedule, "2026-10-05T13:50|Право"))
        assertFalse(LessonAlarms.stillOn(schedule, "2026-10-05T16:00|Право"))
        assertTrue(LessonAlarms.stillOn(schedule, "2026-10-06T16:00|Право"))
    }

    @Test
    fun `день от другой группы приходит со своими звонками`() {
        val mine = schedule.copy(days = listOf(DayDto(date = "2026-10-03"), schedule.days[1]))
        val merged = combineGroups(mine, listOf("ИСП-924/2" to schedule))
        assertEquals(listOf("2026-10-03", "2026-10-05", "2026-10-06"), merged.days.map { it.date })
        assertEquals("09:00–10:00", lessonTime(merged.bellsOn(monday), 1))
        assertEquals(schedule.bells, merged.bellsOn(tuesday))
    }

    @Test
    fun `в недельном виджете звонки едут вместе с днём`() {
        val week = weekDays(schedule.days, monday)
        assertEquals(listOf(true, false), week.map { it.ownBells.isNotEmpty() })
    }

    @Test
    fun `свои звонки переживают сохранение`() {
        val saved = json.decodeFromString<ScheduleDto>(json.encodeToString(ScheduleDto.serializer(), schedule))
        assertEquals(schedule.days[0].ownBells, saved.days[0].ownBells)
        assertTrue(saved.days[1].ownBells.isEmpty())
    }
}
