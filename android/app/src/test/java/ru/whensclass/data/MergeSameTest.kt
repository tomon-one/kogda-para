package ru.whensclass.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Когда колонки двух групп описывают одну и ту же пару: такие не задваиваются,
 * иначе совмещённая пара выглядит двумя.
 *
 * Что считать «той же парой» — решение, а не очевидность: сверяются номер,
 * предмет, аудитория, ссылка, онлайн и отмена, а вид занятия и преподаватель
 * — нет.
 */
class MergeSameTest {

    private fun lesson(
        number: Int = 3,
        subject: String = "Физика",
        kind: String? = "Пр",
        room: String? = "272",
        teachers: List<String> = listOf("Трухачев Д. Д."),
    ) = LessonDto(
        number = number,
        subject = subject,
        kind = kind,
        room = room,
        teachers = teachers,
    )

    private fun merged(mine: List<LessonDto>, theirs: List<LessonDto>): List<LessonDto> {
        val day = DayDto(date = "2026-09-09", lessons = mine)
        val other = DayDto(date = "2026-09-09", lessons = theirs)
        val schedule = ScheduleDto(
            groupId = "isp-924-1",
            groupName = "ИСП-924/1",
            generatedAt = "2026-09-09T00:00:00Z",
            days = listOf(day),
        )
        val second = schedule.copy(groupId = "isp-924-2", groupName = "ИСП-924/2", days = listOf(other))
        return combineGroups(schedule, listOf(second.groupName to second)).days.first().lessons
    }

    @Test
    fun `одинаковая пара из двух колонок не задваивается`() {
        assertEquals(1, merged(listOf(lesson()), listOf(lesson())).size)
    }

    @Test
    fun `разные кабинеты — разные пары`() {
        // Подгруппы разошлись по кабинетам: человеку надо видеть оба, иначе
        // он не поймёт, куда идти именно ему.
        val both = merged(listOf(lesson(room = "272")), listOf(lesson(room = "254")))

        assertEquals(2, both.size)
    }

    @Test
    fun `разные преподаватели при том же кабинете считаются одной парой`() {
        // Решение, а не недосмотр: тот же номер, предмет и кабинет — одно
        // занятие, а расхождение в фамилии обычно значит, что в одной колонке
        // её просто не дописали.
        val both = merged(
            listOf(lesson(teachers = listOf("Трухачев Д. Д."))),
            listOf(lesson(teachers = listOf("Одарюк И. А."))),
        )

        assertEquals(1, both.size)
    }

    @Test
    fun `разный вид занятия при том же кабинете — тоже одна пара`() {
        val both = merged(listOf(lesson(kind = "Пр")), listOf(lesson(kind = "Лаб")))

        assertEquals(1, both.size)
    }

    @Test
    fun `пара, которой нет у себя, добавляется от соседей`() {
        // Ради этого всё и заведено: общая пара записана только в одной колонке.
        val both = merged(emptyList(), listOf(lesson()))

        assertEquals(1, both.size)
    }
}
