package ru.whensclass.notify

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.SubjectNames

/** Вечерняя сводка: что и когда показать. */
class DigestTest {

    private val tomorrow: LocalDate = LocalDate.of(2026, 11, 17)
    private val bells = mapOf(
        "1" to listOf("09:00", "10:30"),
        "3" to listOf("11:30", "13:00"),
    )

    private fun lesson(
        number: Int,
        subject: String = "Физика",
        short: String? = null,
        room: String? = "305",
        online: Int = 0,
        cancelled: Int = 0,
    ) = LessonDto(
        number = number, subject = subject, shortSubject = short, room = room,
        online = online, cancelled = cancelled,
    )

    private fun schedule(lessons: List<LessonDto>, unread: String? = null) = ScheduleDto(
        groupId = "isp-924-1", groupName = "ИСП-924/1", generatedAt = "", bells = bells,
        days = listOf(DayDto(date = tomorrow.toString(), lessons = lessons, unreadMark = unread)),
    )

    @Test
    fun `первая пара — по звонку, а не по номеру`() {
        val text = Digest.digestText(
            schedule(listOf(lesson(3, "Основы алгоритмизации", "алгоритмы", room = "214"), lesson(1))),
            tomorrow,
            SubjectNames(),
        )
        assertEquals("Завтра к 1-й паре" to "Физика, 09:00, ауд. 305", text)
    }

    @Test
    fun `короткое название и своё важнее него`() {
        val short = Digest.digestText(
            schedule(listOf(lesson(3, "Основы алгоритмизации", "алгоритмы", room = "214"))),
            tomorrow, SubjectNames(short = true),
        )
        assertEquals("Завтра к 3-й паре" to "алгоритмы, 11:30, ауд. 214", short)
        val own = Digest.digestText(
            schedule(listOf(lesson(3, "Основы алгоритмизации", "алгоритмы", room = "214"))),
            tomorrow, SubjectNames(custom = mapOf("Основы алгоритмизации" to "моё имя")),
        )
        assertEquals("Завтра к 3-й паре" to "моё имя, 11:30, ауд. 214", own)
    }

    @Test
    fun `онлайн и комната без номера`() {
        assertEquals(
            "Завтра к 1-й паре" to "Физика, 09:00, онлайн",
            Digest.digestText(schedule(listOf(lesson(1, room = null, online = 1))), tomorrow, SubjectNames()),
        )
        assertEquals(
            "Завтра к 1-й паре" to "Физика, 09:00",
            Digest.digestText(schedule(listOf(lesson(1, room = "0"))), tomorrow, SubjectNames()),
        )
    }

    @Test
    fun `нечего сказать — молчим`() {
        assertNull(Digest.digestText(schedule(emptyList()), tomorrow, SubjectNames()))
        assertNull(Digest.digestText(schedule(listOf(lesson(1)), unread = "missing"), tomorrow, SubjectNames()))
        assertNull(Digest.digestText(schedule(listOf(lesson(1, cancelled = 1))), tomorrow, SubjectNames()))
        assertNull(Digest.digestText(schedule(listOf(lesson(1))), tomorrow.plusDays(1), SubjectNames()))
    }

    @Test
    fun `вечер — перед учебным днём, в субботу не бывает`() {
        fun evening(day: Int, hour: Int) = Digest.nextEvening(
            LocalDateTime.of(2026, 11, day, hour, 0), Digest.DEFAULT,
        )
        // Пятница: до срока — сегодня, после — в воскресенье: субботний вечер пропущен.
        assertEquals(LocalDateTime.of(2026, 11, 13, 20, 0), evening(13, 19))
        assertEquals(LocalDateTime.of(2026, 11, 15, 20, 0), evening(13, 21))
        // Суббота после срока — тоже воскресенье.
        assertEquals(LocalDateTime.of(2026, 11, 15, 20, 0), evening(14, 21))
        // Воскресенье после срока — понедельник.
        assertEquals(LocalDateTime.of(2026, 11, 16, 20, 0), evening(15, 21))
    }
}
