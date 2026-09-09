package ru.whensclass.notify

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto

/**
 * О чём вообще стоит напоминать.
 *
 * Раньше будильник ставился на каждую пару подряд, и человек, сидя на третьей,
 * получал напоминание о четвёртой. Сообщать ему было нечего: он уже в колледже,
 * а что дальше — видно в приложении и на виджете.
 *
 * Напоминают о том, к чему надо прийти: о первой паре дня и о паре после окна.
 */
class LessonPlanTest {

    // Настоящая сетка колледжа: перемены по 10 и 20 минут. Придуманная ровная
    // сетка спрятала бы как раз пограничный случай — напоминание за 20 минут
    // при двадцатиминутной перемене.
    private val bells = mapOf(
        "1" to listOf("09:00", "10:30"),
        "2" to listOf("10:40", "12:10"),
        "3" to listOf("12:30", "14:00"),
        "4" to listOf("14:20", "15:50"),
    )

    private val night = LocalDateTime.of(2026, 9, 8, 6, 0)

    private fun lesson(number: Int, cancelled: Boolean = false) =
        LessonDto(number = number, subject = "Пара $number", cancelled = if (cancelled) 1 else 0)

    private fun day(vararg lessons: LessonDto) = ScheduleDto(
        groupId = "isp-924-1",
        groupName = "ИСП-924/1",
        generatedAt = "2026-09-08T00:00:00Z",
        bells = bells,
        days = listOf(DayDto(date = "2026-09-08", lessons = lessons.toList())),
    )

    @Test
    fun `подряд идущие пары дают одно напоминание`() {
        val plan = LessonAlarms.plan(day(lesson(1), lesson(2), lesson(3)), minutes = 20, now = night)

        assertEquals(listOf(1), plan.map { it.lesson.number })
    }

    @Test
    fun `пара после окна напоминает о себе отдельно`() {
        // Второй пары нет вовсе: между первой и третьей полтора часа улицы,
        // и вернуться к третьей — как прийти к первой.
        val plan = LessonAlarms.plan(day(lesson(1), lesson(3), lesson(4)), minutes = 20, now = night)

        assertEquals(listOf(1, 3), plan.map { it.lesson.number })
    }

    @Test
    fun `отменённая пара делает окно, а не тишину`() {
        val plan = LessonAlarms.plan(
            day(lesson(1), lesson(2, cancelled = true), lesson(3)),
            minutes = 20,
            now = night,
        )

        // Вторую отменили — на третью человек приходит с улицы, и сказать ему
        // об этом надо. Раньше про неё молчали бы, считая пары подряд.
        assertEquals(listOf(1, 3), plan.map { it.lesson.number })
    }

    @Test
    fun `короткое напоминание попадает в перемену и остаётся`() {
        // Перемена десять минут. Напоминание за пять приходит на перемене —
        // человек как раз решает, куда идти, и это ему полезно.
        val plan = LessonAlarms.plan(day(lesson(1), lesson(2)), minutes = 5, now = night)

        assertEquals(listOf(1, 2), plan.map { it.lesson.number })
    }

    @Test
    fun `день, начинающийся не с первой пары`() {
        val plan = LessonAlarms.plan(day(lesson(3), lesson(4)), minutes = 20, now = night)

        assertEquals(listOf(3), plan.map { it.lesson.number })
    }

    @Test
    fun `без времени окончания напоминание остаётся`() {
        // Сетка звонков приходит с сервера и может оказаться неполной.
        // Промолчать из-за неполноты хуже, чем напомнить лишний раз.
        val half = mapOf("1" to listOf("09:00"), "2" to listOf("10:40", "12:10"))
        val schedule = ScheduleDto(
            groupId = "isp-924-1",
            groupName = "ИСП-924/1",
            generatedAt = "2026-09-08T00:00:00Z",
            bells = half,
            days = listOf(DayDto(date = "2026-09-08", lessons = listOf(lesson(1), lesson(2)))),
        )

        val plan = LessonAlarms.plan(schedule, minutes = 20, now = night)

        assertEquals(listOf(1, 2), plan.map { it.lesson.number })
    }
}
