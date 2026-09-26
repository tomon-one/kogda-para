package ru.whensclass.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleMergeTest {

    private fun lesson(
        number: Int,
        subject: String,
        room: String? = null,
        url: String? = null,
    ) = LessonDto(number = number, subject = subject, room = room, url = url)

    private fun schedule(name: String, vararg lessons: LessonDto) = ScheduleDto(
        groupId = name.lowercase(),
        groupName = name,
        generatedAt = "2026-09-08T00:00:00Z",
        days = listOf(DayDto(date = "2026-09-08", lessons = lessons.toList())),
    )

    @Test
    fun `общая пара остаётся одной строкой и без подписи`() {
        val merged = mergeSecondGroup(
            schedule("ИСП-924/1", lesson(3, "Иностранный язык", room = "454")),
            schedule("ИСП-924/2", lesson(3, "Иностранный язык", room = "454")),
        )
        val lessons = merged.days.single().lessons
        assertEquals(1, lessons.size)
        assertNull(lessons.single().groups)
    }

    @Test
    fun `пара, которой нет в своей колонке, добавляется с подписью`() {
        val merged = mergeSecondGroup(
            schedule("ИСП-924/1", lesson(3, "Иностранный язык", room = "454")),
            schedule("ИСП-924/2", lesson(4, "Основы алгоритмизации", room = "272")),
        )
        val lessons = merged.days.single().lessons
        assertEquals(listOf(3, 4), lessons.map { it.number })
        assertNull(lessons.first().groups)
        assertEquals("ИСП-924/2", lessons.last().groups)
    }

    @Test
    fun `разные кабинеты в один номер — это разные пары, подписаны обе`() {
        val merged = mergeSecondGroup(
            schedule("ИСП-924/1", lesson(3, "Иностранный язык", room = "454")),
            schedule("ИСП-924/2", lesson(3, "Иностранный язык", room = "455")),
        )
        val lessons = merged.days.single().lessons
        assertEquals(2, lessons.size)
        assertEquals("ИСП-924/1", lessons.first().groups)
        assertEquals("ИСП-924/2", lessons.last().groups)
        // Своя пара стоит выше чужой.
        assertEquals("454", lessons.first().room)
    }

    @Test
    fun `день без второй колонки остаётся как был`() {
        val primary = schedule("ИСП-924/1", lesson(1, "Физика"))
        val merged = mergeSecondGroup(primary, schedule("ИСП-924/2"))
        assertEquals(primary.days, merged.days)
    }

    @Test
    fun `чужих дней в расписании не появляется`() {
        val secondary = ScheduleDto(
            groupId = "isp-924-2",
            groupName = "ИСП-924/2",
            generatedAt = "2026-09-08T00:00:00Z",
            days = listOf(DayDto(date = "2026-09-09", lessons = listOf(lesson(1, "Химия")))),
        )
        val merged = mergeSecondGroup(schedule("ИСП-924/1", lesson(1, "Физика")), secondary)
        assertEquals(listOf("2026-09-08"), merged.days.map { it.date })
    }

    @Test
    fun `свои пары из склейки — и подписанная своя тоже`() {
        // На общем номере склейка подписывает и свою пару; «свои = без
        // подписи» выбрасывал её вместе с соседской.
        val merged = mergeSecondGroup(
            schedule("ИСП-924/1", lesson(3, "Физика", room = "101"), lesson(4, "Химия")),
            schedule("ИСП-924/2", lesson(3, "Физика", room = "102"), lesson(5, "Право")),
        )
        val own = merged.ownOnly().days.single().lessons
        assertEquals(listOf(3 to "101", 4 to null), own.map { it.number to it.room })
        assertEquals(listOf<String?>(null, null), own.map { it.groups })
    }
}
