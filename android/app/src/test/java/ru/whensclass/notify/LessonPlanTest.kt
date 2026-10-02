package ru.whensclass.notify

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto

/**
 * О чём вообще стоит напоминать: о том, к чему надо прийти, — о первой паре
 * дня и о паре после окна, а не о каждой подряд.
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
        // об этом надо.
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

    // --- Соседняя подгруппа --------------------------------------------------

    private fun neighbour(number: Int) =
        LessonDto(number = number, subject = "Соседская $number", groups = "ИСП-924/2")

    @Test
    fun `пара соседки не глушит напоминание о своей первой и сама не напоминает`() {
        // У соседки 1–2, у своей группы только 3: напоминание — о своей 3-й,
        // а не о чужой первой.
        val plan = LessonAlarms.plan(
            day(neighbour(1), neighbour(2), lesson(3)), minutes = 20, now = night,
        )
        assertEquals(listOf(3), plan.map { it.lesson.number })
    }

    @Test
    fun `своя пара на общем номере подписана своим именем — и напоминает`() {
        // Склейка подписывает свою пару, когда у соседки на том же номере
        // другое: «своя = без подписи» заглушила бы напоминание о ней.
        val own = LessonDto(number = 1, subject = "Физика", groups = "ИСП-924/1")
        val plan = LessonAlarms.plan(day(own, neighbour(1), lesson(2)), minutes = 20, now = night)
        assertEquals(listOf("Физика"), plan.map { it.lesson.subject })
    }

    @Test
    fun `у преподавателя подпись группы у каждой пары — все свои`() {
        val teacher = day(
            LessonDto(number = 1, subject = "Физика", groups = "ИСП-924/1"),
            LessonDto(number = 3, subject = "Физика", groups = "ИСП-924/2"),
        ).copy(kind = "teacher")
        assertEquals(listOf(1, 3), LessonAlarms.plan(teacher, minutes = 20, now = night).map { it.lesson.number })
    }

    @Test
    fun `посреди дня прошедшее не напоминает, а пара после окна — да`() {
        // 11:00, идёт вторая пара: в 06:00 не видно, где стоит отсев прошедших.
        val noon = LocalDateTime.of(2026, 9, 8, 11, 0)
        // 1-я и 2-я — в прошлом; 4-я — после окна в третьей, напоминание в 14:00.
        val plan = LessonAlarms.plan(day(lesson(1), lesson(2), lesson(4)), minutes = 20, now = noon)
        assertEquals(listOf(4), plan.map { it.lesson.number })
        // 3-я идёт сразу за 2-й: её напоминание в 12:10 ещё впереди, но
        // пришло бы посреди пары. Отсев прошедших стоит после учёта занятости:
        // стоял бы раньше — прошедшие пары не помечали бы занятость, и
        // напоминание посреди пары вернулось бы.
        val during = LessonAlarms.plan(day(lesson(1), lesson(2), lesson(3)), minutes = 20, now = noon)
        assertEquals(emptyList<Int>(), during.map { it.lesson.number })
    }

    @Test
    fun `напоминание о паре, которую отменили, снимается, о прежней — нет`() {
        // Рядом с «отменили 2 пару» не должно висеть напоминание о ней.
        val key = "2026-09-08T10:40|Пара 2"
        assertEquals(true, LessonAlarms.stillOn(day(lesson(1), lesson(2)), key))
        assertEquals(false, LessonAlarms.stillOn(day(lesson(1), lesson(2, cancelled = true)), key))
        assertEquals(false, LessonAlarms.stillOn(day(lesson(1)), key))
    }

    @Test
    fun `блок пополам — одно напоминание об обеих парах номера`() {
        val german = LessonDto(number = 4, subject = "Немецкий", room = "55/1")
        val english = LessonDto(number = 4, subject = "Английский", room = "467")
        val plan = LessonAlarms.plan(day(german, english), minutes = 15, now = night)

        assertEquals(1, plan.size)
        assertEquals(listOf("Немецкий", "Английский"), plan.single().lessons.map { it.subject })
        assertEquals("Немецкий / Английский", plan.single().subject)
        // Отменили одну из двух — напоминание о второй не снимается.
        val key = "2026-09-08T14:20|Немецкий\u001fАнглийский"
        assertEquals(true, LessonAlarms.stillOn(day(german.copy(cancelled = 1), english), key))
        assertEquals(false, LessonAlarms.stillOn(day(german.copy(cancelled = 1), english.copy(cancelled = 1)), key))
    }

    @Test
    fun `отменённая половинка названа в напоминании о другой`() {
        val german = LessonDto(number = 4, subject = "Немецкий", room = "55/1", teachers = listOf("Миллер Д. Х."), cancelled = 1)
        val english = LessonDto(number = 4, subject = "Английский", room = "467", teachers = listOf("Уэллс Д. Р."))
        val alarm = LessonAlarms.plan(day(german, english), minutes = 15, now = night).single()

        assertEquals("Английский", alarm.subject)
        assertEquals("4 пара\nКаб. 467 — Английский. Уэллс Д. Р.\nОтменена — Немецкий. Миллер Д. Х.", LessonAlarms.text(alarm))
    }
}
