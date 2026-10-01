package ru.whensclass.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Своё расписание вместе с парами остальных выбранных групп: у
 * каждой строки — какие группы её делят, своя — место 0.
 */
class ScheduleMergeTest {

    private fun lesson(
        number: Int,
        subject: String,
        room: String? = null,
        url: String? = null,
    ) = LessonDto(number = number, subject = subject, room = room, url = url)

    private fun schedule(name: String, vararg lessons: LessonDto, date: String = "2026-09-08") = ScheduleDto(
        groupId = name.lowercase(),
        groupName = name,
        generatedAt = "2026-09-08T00:00:00Z",
        days = listOf(DayDto(date = date, lessons = lessons.toList())),
    )

    private fun combine(main: ScheduleDto, vararg extras: ScheduleDto?) =
        combineGroups(main, extras.mapIndexed { i, it -> (it?.groupName ?: "Группа ${i + 2}") to it })

    @Test
    fun `общая пара — одна строка, отмечены все группы`() {
        val merged = combine(
            schedule("ИСП-924/1", lesson(3, "Иностранный язык", room = "454")),
            schedule("ИСП-924/2", lesson(3, "Иностранный язык", room = "454")),
            schedule("ИСП-924/3", lesson(3, "Иностранный язык", room = "454")),
        )
        val lessons = merged.days.single().lessons
        assertEquals(1, lessons.size)
        assertEquals(listOf(0, 1, 2), lessons.single().slots)
        assertEquals(listOf("ИСП-924/1", "ИСП-924/2", "ИСП-924/3"), merged.groupNames)
    }

    @Test
    fun `пара, которой нет у своей, добавляется без своей отметки`() {
        val merged = combine(
            schedule("ИСП-924/1", lesson(3, "Иностранный язык", room = "454")),
            schedule("ИСП-924/2", lesson(4, "Основы алгоритмизации", room = "272")),
        )
        val lessons = merged.days.single().lessons
        assertEquals(listOf(3, 4), lessons.map { it.number })
        assertEquals(listOf(listOf(0), listOf(1)), lessons.map { it.slots })
    }

    @Test
    fun `разные кабинеты в один номер — разные строки, своя выше`() {
        val merged = combine(
            schedule("ИСП-924/1", lesson(3, "Иностранный язык", room = "454")),
            schedule("ИСП-924/2", lesson(3, "Иностранный язык", room = "455")),
            schedule("ИСП-924/3", lesson(3, "Иностранный язык", room = "455")),
        )
        val lessons = merged.days.single().lessons
        assertEquals(listOf("454", "455"), lessons.map { it.room })
        // Вторая и третья сидят вместе — это одна их пара.
        assertEquals(listOf(listOf(0), listOf(1, 2)), lessons.map { it.slots })
    }

    @Test
    fun `подписи группы у пар нет — чья пара, видно по значкам`() {
        val merged = combine(
            schedule("ИСП-924/1", lesson(3, "Физика", room = "101")),
            schedule("ИСП-924/2", lesson(3, "Физика", room = "102")),
        )
        assertEquals(listOf<String?>(null, null), merged.days.single().lessons.map { it.groups })
    }

    @Test
    fun `одной группы — ничего не склеивается и значков нет`() {
        val main = schedule("ИСП-924/1", lesson(1, "Физика"))
        assertSame(main, combineGroups(main, emptyList()))
    }

    @Test
    fun `расписания группы ещё нет — место в значках остаётся`() {
        val merged = combine(schedule("ИСП-924/1", lesson(1, "Физика")), null)
        assertEquals(listOf("ИСП-924/1", "Группа 2"), merged.groupNames)
        assertEquals(listOf(listOf(0)), merged.days.single().lessons.map { it.slots })
    }

    @Test
    fun `чужой день внутри своего окна появляется, за краем — нет`() {
        val main = ScheduleDto(
            groupId = "isp-924-1",
            groupName = "ИСП-924/1",
            generatedAt = "2026-09-08T00:00:00Z",
            days = listOf(
                DayDto(date = "2026-09-08", lessons = listOf(lesson(1, "Физика"))),
                DayDto(date = "2026-09-10", lessons = listOf(lesson(1, "Физика"))),
            ),
        )
        val other = ScheduleDto(
            groupId = "isp-924-2",
            groupName = "ИСП-924/2",
            generatedAt = "2026-09-08T00:00:00Z",
            days = listOf(
                DayDto(date = "2026-09-09", lessons = listOf(lesson(2, "Химия"))),
                DayDto(date = "2026-09-11", lessons = listOf(lesson(2, "Химия"))),
            ),
        )
        val merged = combine(main, other)
        assertEquals(listOf("2026-09-08", "2026-09-09", "2026-09-10"), merged.days.map { it.date })
        assertEquals(listOf(listOf(1)), merged.days[1].lessons.map { it.slots })
    }

    @Test
    fun `у преподавателя групп не бывает`() {
        val teacher = schedule("Трухачев Д. Д.", lesson(1, "Физика")).copy(kind = "teacher")
        assertSame(teacher, combine(teacher, schedule("ИСП-924/2", lesson(2, "Химия"))))
    }

    @Test
    fun `свои пары из снимка сборок до 0_1_4 — и подписанная своя тоже`() {
        // Там снимок лежал склеенным: на общем номере подписывалась и своя
        // пара, и «свои = без подписи» выбрасывал её вместе с соседской.
        val old = schedule(
            "ИСП-924/1",
            lesson(3, "Физика", room = "101").copy(groups = "ИСП-924/1"),
            lesson(3, "Физика", room = "102").copy(groups = "ИСП-924/2"),
            lesson(4, "Химия"),
            lesson(5, "Право").copy(groups = "ИСП-924/2"),
        )
        val own = old.ownOnly().days.single().lessons
        assertEquals(listOf(3 to "101", 4 to null), own.map { it.number to it.room })
        assertEquals(listOf<String?>(null, null), own.map { it.groups })
    }

    @Test
    fun `подгруппы своей группы — по номеру, без себя и без чужих`() {
        val groups = listOf("ИСП-924/2", "ИСП-924/1", "ИСП-9241/1", "ИСП-924", "ИСП-924/10", "ИСП-925/1")
            .map { GroupDto(id = it.lowercase(), name = it) }
        assertEquals(
            listOf("ИСП-924/2", "ИСП-924/10"),
            subgroupsOf("ИСП-924/1", groups).map { it.name },
        )
        assertEquals(emptyList<GroupDto>(), subgroupsOf("ИСП-924", groups))
        assertNull(subgroupsOf("ДИ-926", groups).firstOrNull())
    }
}
