package ru.whensclass.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDiffTest {

    private fun lesson(
        number: Int,
        subject: String,
        room: String? = null,
        url: String? = null,
        cancelled: Boolean = false,
        groups: String? = null,
    ) = LessonDto(
        number = number,
        subject = subject,
        room = room,
        url = url,
        cancelled = if (cancelled) 1 else 0,
        groups = groups,
    )

    private fun schedule(vararg lessons: LessonDto) = ScheduleDto(
        groupId = "isp-924-1",
        groupName = "ИСП-924/1",
        generatedAt = "2026-09-08T00:00:00Z",
        days = listOf(DayDto(date = "2026-09-08", lessons = lessons.toList())),
    )

    @Test
    fun `без изменений молчим`() {
        val same = schedule(lesson(3, "Численные методы", room = "272"))
        assertEquals(emptyList<ScheduleDiff.Change>(), ScheduleDiff.compare(same, same))
    }

    @Test
    fun `отмена, переезд и онлайн названы словами`() {
        val was = schedule(
            lesson(3, "Численные методы", room = "272"),
            lesson(4, "Физика", room = "101"),
        )
        val now = schedule(
            lesson(3, "Численные методы", room = "355"),
            lesson(4, "Физика", room = "101", cancelled = true),
        )
        val texts = ScheduleDiff.compare(was, now).map { it.text }
        assertTrue(texts.contains("3 пара переехала в 355"))
        assertTrue(texts.contains("отменили 4 пару: Физика"))
    }

    @Test
    fun `две подгруппы на один номер не выглядят как пропажа пары`() {
        // Своя пара и пара соседней подгруппы стоят на одном номере. Раньше
        // они затирали друг друга, и обновление сообщало «убрали пару».
        val was = schedule(
            lesson(3, "Иностранный язык", room = "454", groups = "ИСП-924/1"),
            lesson(3, "Английский", room = "455", groups = "ИСП-924/2"),
        )
        val now = schedule(
            lesson(3, "Иностранный язык", room = "454", groups = "ИСП-924/1"),
            lesson(3, "Английский", room = "455", groups = "ИСП-924/2"),
        )
        assertEquals(emptyList<ScheduleDiff.Change>(), ScheduleDiff.compare(was, now))
    }

    @Test
    fun `появившаяся подпись группы не считается изменением`() {
        // Подпись появляется, когда в тот же номер приходит пара соседей:
        // сама пара при этом не менялась, и говорить о ней нечего.
        val was = schedule(lesson(3, "Иностранный язык", room = "454"))
        val now = schedule(
            lesson(3, "Иностранный язык", room = "454", groups = "ИСП-924/1"),
            lesson(3, "Английский", room = "455", groups = "ИСП-924/2"),
        )
        val texts = ScheduleDiff.compare(was, now).map { it.text }
        assertEquals(listOf("добавилась 3 пара: Английский"), texts)
    }

    @Test
    fun `исчезнувшая пара названа своим именем`() {
        val was = schedule(lesson(5, "Основы алгоритмизации", room = "269"))
        val texts = ScheduleDiff.compare(was, schedule()).map { it.text }
        assertEquals(listOf("убрали 5 пару: Основы алгоритмизации"), texts)
    }

    @Test
    fun `чужое расписание не сравниваем`() {
        val other = ScheduleDto(
            groupId = "isp-924-2",
            groupName = "ИСП-924/2",
            generatedAt = "2026-09-08T00:00:00Z",
            days = listOf(DayDto(date = "2026-09-08", lessons = listOf(lesson(1, "Химия")))),
        )
        assertEquals(emptyList<ScheduleDiff.Change>(), ScheduleDiff.compare(other, schedule()))
    }
}
