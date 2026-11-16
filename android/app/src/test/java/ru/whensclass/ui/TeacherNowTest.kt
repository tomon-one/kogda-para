package ru.whensclass.ui

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto

/** «Где преподаватель сейчас»: пара, окно между парами, конец дня. */
class TeacherNowTest {

    private val bells = mapOf(
        "1" to listOf("09:00", "10:30"),
        "2" to listOf("10:45", "12:15"),
        "3" to listOf("12:45", "14:15"),
    )

    private fun lesson(number: Int, room: String? = "214", online: Int = 0, cancelled: Int = 0) = LessonDto(
        number = number,
        subject = "Алгоритмы",
        room = room,
        online = online,
        cancelled = cancelled,
        groups = "ИСП-924/1",
    )

    private fun schedule(
        lessons: List<LessonDto> = listOf(lesson(1), lesson(2), lesson(3)),
        unread: String? = null,
    ) = ScheduleDto(
        groupId = "alagyan",
        groupName = "Алагян Оник Левонович",
        generatedAt = "",
        kind = "teacher",
        bells = bells,
        days = listOf(DayDto(date = "2026-11-16", lessons = lessons, unreadMark = unread)),
    )

    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, 16, hour, minute)

    @Test
    fun `идёт пара — номер, место, группы и конец`() {
        assertEquals(
            "Сейчас 1-я пара, каб. 214, ИСП-924/1, до 10:30",
            teacherNow(schedule(), at(9, 30)),
        )
    }

    @Test
    fun `онлайн — без «каб», кабинет не указан — без места`() {
        assertEquals(
            "Сейчас 1-я пара, онлайн · 269, ИСП-924/1, до 10:30",
            teacherNow(schedule(listOf(lesson(1, room = "269", online = 1))), at(9, 30)),
        )
        assertEquals(
            "Сейчас 1-я пара, ИСП-924/1, до 10:30",
            teacherNow(schedule(listOf(lesson(1, room = null))), at(9, 30)),
        )
    }

    @Test
    fun `между парами, до первой и после последней`() {
        assertEquals("Свободен с 10:30 до 10:45", teacherNow(schedule(), at(10, 35)))
        assertEquals("Свободен до 09:00", teacherNow(schedule(), at(8)))
        assertEquals("На сегодня пар больше нет", teacherNow(schedule(), at(15)))
    }

    @Test
    fun `отменённая пара времени не занимает`() {
        assertEquals(
            "На сегодня пар больше нет",
            teacherNow(schedule(listOf(lesson(1), lesson(2, cancelled = 1))), at(10, 35)),
        )
    }

    @Test
    fun `пустой или непрочитанный день — без выдумок`() {
        assertEquals("Сегодня пар нет", teacherNow(schedule(emptyList()), at(9, 30)))
        assertNull(teacherNow(schedule(unread = "missing"), at(9, 30)))
        // Дня нет в ответе — тоже «пар нет», а не свободен.
        assertEquals("Сегодня пар нет", teacherNow(schedule(), at(9, 30).plusDays(1)))
    }

    @Test
    fun `выходной — словом, а не «пар нет»`() {
        val day = DayDto(date = "2026-11-16", absent = true)
        val rest = ScheduleDto(groupId = "g", groupName = "g", generatedAt = "", days = listOf(day))
        assertEquals("Сегодня выходной", teacherNow(rest, at(9, 30)))
    }
}
