package ru.whensclass.widget

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto

/**
 * Маленький виджет «Ближайшая пара»: какую пару он называет и что говорит,
 * когда пары нет.
 */
class NextLessonTest {

    private val bells = mapOf(
        "1" to listOf("09:00", "10:30"),
        "2" to listOf("10:40", "12:10"),
        "3" to listOf("12:30", "14:00"),
    )
    private val tuesday = LocalDate.of(2026, 9, 15)
    private val wednesday = tuesday.plusDays(1)

    private fun lesson(number: Int, subject: String, cancelled: Boolean = false, groups: String? = null) =
        LessonDto(
            number = number, subject = subject, cancelled = if (cancelled) 1 else 0, groups = groups,
        )

    private fun schedule(vararg days: Pair<LocalDate, List<LessonDto>>, kind: String? = null, cov: String = "2026-09-26") =
        ScheduleDto(
            groupId = "isp-924-1", groupName = "ИСП-924/1", generatedAt = "2026-09-15T00:00:00Z",
            coverage = listOf("2026-09-14", cov), bells = bells, kind = kind,
            days = days.map { (d, l) -> DayDto(date = d.toString(), lessons = l) },
        )

    @Test
    fun `отменённая пара не ближайшая — ни идущая, ни завтрашняя`() {
        val s = schedule(
            tuesday to listOf(lesson(1, "Физика", cancelled = true), lesson(2, "Химия")),
            wednesday to listOf(lesson(1, "История", cancelled = true), lesson(3, "Право")),
        )
        // Во время отменённой первой — не «идёт сейчас», а вторая.
        assertEquals("Химия", nextLesson(s, tuesday.atTime(9, 30))?.lesson?.subject)
        // Вечером — не зачёркнутая первая завтра, а настоящая.
        assertEquals("Право", nextLesson(s, tuesday.atTime(19, 0))?.lesson?.subject)
    }

    @Test
    fun `пара соседней подгруппы не выдаётся за свою`() {
        val s = schedule(
            tuesday to listOf(lesson(1, "Физика", groups = "ИСП-924/2"), lesson(3, "Химия")),
        )
        assertEquals("Химия", nextLesson(s, tuesday.atTime(8, 0))?.lesson?.subject)
    }

    @Test
    fun `своя пара с подписью своей группы — своя`() {
        // На общем номере склейка подписывает и свою пару.
        val s = schedule(
            tuesday to listOf(lesson(1, "Физика", groups = "ИСП-924/1"), lesson(1, "Химия", groups = "ИСП-924/2")),
        )
        assertEquals("Физика", nextLesson(s, tuesday.atTime(8, 0))?.lesson?.subject)
    }

    @Test
    fun `у преподавателя подпись группы у каждой пары — все свои`() {
        val s = schedule(
            tuesday to listOf(lesson(1, "Физика", groups = "ИСП-924/1")),
            kind = "teacher",
        )
        assertEquals("Физика", nextLesson(s, tuesday.atTime(8, 0))?.lesson?.subject)
    }

    @Test
    fun `пар нет дальше — но почему`() {
        val now = LocalDateTime.of(2026, 9, 26, 19, 0).toLocalDate()
        val endOfSheet = schedule(cov = "2026-09-26")
        assertNull(nextLesson(endOfSheet, now.atTime(19, 0)))
        assertEquals("Дальше расписание ещё не опубликовано", noNextLesson("ИСП-924/1", endOfSheet, now, false))
        assertEquals("Сбой: расписание не обновляется", noNextLesson("ИСП-924/1", endOfSheet, now, true))
        assertEquals("Нужно обновить приложение", noNextLesson("ИСП-924/1", endOfSheet, now, true, unsupported = true))
        assertEquals("Дальше расписание не загружено", noNextLesson("ИСП-924/1", endOfSheet, now, false, checked = false))
        // Лист идёт дальше скачанной недели, а в ней пар больше нет: не «дальше
        // пар нет» (за краем недели они есть), а до какого дня их нет.
        val window = schedule(now to emptyList(), now.plusDays(2) to emptyList(), cov = "2026-10-10")
        assertEquals("Пар нет по пн, 28 сент.", noNextLesson("ИСП-924/1", window, now, false))
        assertEquals(
            "Откройте приложение и выберите группу или себя",
            noNextLesson(null, null, now, false),
        )
    }

    @Test
    fun `в шапке время первым — многоточие съедает дату, а не время`() {
        assertEquals("14:20 · пн, 28 сентября", nextLessonHead("14:20", "пн, 28 сентября", false, null))
        assertEquals("идёт сейчас · 14:20", nextLessonHead("14:20", "идёт сейчас", true, null))
        assertEquals("09:00 · завтра · ИСП-924/2", nextLessonHead("09:00", "завтра", false, "ИСП-924/2"))
    }

    @Test
    fun `в тесной клетке место сразу за временем, без «сегодня»`() {
        assertEquals(
            "14:20 · каб. 275 · ИСП-924/2",
            nextLessonHead("14:20", "сегодня", false, "ИСП-924/2", place = "каб. 275"),
        )
        assertEquals("09:00 · каб. 275 · завтра", nextLessonHead("09:00", "завтра", false, null, place = "каб. 275"))
        assertEquals("идёт · онлайн", nextLessonHead("14:20", "идёт сейчас", true, null, place = "онлайн"))
    }

    @Test
    fun `блок пополам — ближайшая называет обе пары номера`() {
        val german = LessonDto(number = 2, subject = "Немецкий", room = "55/1")
        val english = LessonDto(number = 2, subject = "Английский", room = "467")
        val s = schedule(
            tuesday to listOf(lesson(1, "Физика"), german, english),
            wednesday to listOf(german, english),
        )

        val now = nextLesson(s, tuesday.atTime(10, 35))
        assertEquals(listOf("Немецкий", "Английский"), now?.lessons?.map { it.subject })
        assertEquals("каб. 55/1 / каб. 467", now?.let(::places))
        assertEquals(2, nextLesson(s, tuesday.atTime(19, 0))?.lessons?.size)
        assertEquals(1, nextLesson(s, tuesday.atTime(8, 0))?.lessons?.size)

        // Немецкую половинку отменили — английская названа, и отмена тоже.
        val off = schedule(tuesday to listOf(german.copy(cancelled = 1), english))
        val left = nextLesson(off, tuesday.atTime(8, 0))
        assertEquals(listOf("Английский"), left?.lessons?.map { it.subject })
        assertEquals("каб. 467 · отменена: Немецкий", left?.let(::places))
    }
}
