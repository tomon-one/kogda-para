package ru.whensclass.widget

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayFormatTest {

    @Test
    fun `ближайшие дни называются словами`() {
        val today = LocalDate.now()
        assertTrue(formatDayTitle(today).startsWith("сегодня"))
        assertTrue(formatDayTitle(today.plusDays(1)).startsWith("завтра"))
        // Прошедшие дни остаются в списке, поэтому вчерашний назван словом.
        assertTrue(formatDayTitle(today.minusDays(1)).startsWith("вчера"))
        // А послезавтра человек посчитает и сам — там только дата.
        assertTrue(!formatDayTitle(today.plusDays(2)).startsWith("послезавтра"))
    }

    @Test
    fun `дальняя дата показывается числом и днём недели`() {
        val title = formatDayTitle(LocalDate.of(2026, 9, 12))
        assertEquals("12 сентября, суббота", title)
    }

    @Test
    fun `фамилия остаётся, имя и отчество сокращаются`() {
        assertEquals("Трухачев Д. Д.", shortenName("Трухачев Даниил Дмитриевич"))
        assertEquals("Фокина Я. Е.", shortenName("Фокина Яна Евгеньевна"))
    }

    @Test
    fun `одно слово не трогаем`() {
        assertEquals("Иванов", shortenName("Иванов"))
    }

    @Test
    fun `без сетки звонков подсвечивать нечего`() {
        assertNull(currentLessonNumber(emptyMap(), LocalDate.now()))
    }

    @Test
    fun `текущая пара ищется только на сегодня`() {
        val bells = mapOf("1" to listOf("00:00", "23:59"))
        assertEquals(1, currentLessonNumber(bells, LocalDate.now()))
        assertNull(currentLessonNumber(bells, LocalDate.now().plusDays(1)))
    }

    @Test
    fun `время пары собирается из сетки`() {
        val bells = mapOf("2" to listOf("10:10", "11:40"))
        assertEquals("10:10–11:40", lessonTime(bells, 2))
        assertNull(lessonTime(bells, 3))
    }

    @Test
    fun `битое время не роняет разбор`() {
        assertNull(parseTime("не время"))
        assertEquals(LocalTime.of(8, 30), parseTime("08:30"))
    }

    @Test
    fun `свежесть данных считается от полусуток`() {
        val now = System.currentTimeMillis()
        assertTrue(!isStale(now))
        assertTrue(isStale(now - 13 * 60 * 60 * 1000L))
        // Данных нет вовсе — это не «устарели».
        assertTrue(!isStale(0))
    }

    @Test
    fun `короткое время для шапки виджета`() {
        assertEquals("—", formatFetchedShort(0))
        val now = formatFetchedShort(System.currentTimeMillis())
        assertTrue("ожидал ЧЧ:ММ, получил $now", now.matches(Regex("""\d{2}:\d{2}""")))
        val yesterday = formatFetchedShort(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        assertTrue(yesterday.startsWith("вчера"))
    }

    @Test
    fun `время обновления показывается по-человечески`() {
        assertEquals("ещё не обновлялось", formatFetchedAt(0))
        assertTrue(formatFetchedAt(System.currentTimeMillis()).startsWith("обновлено в"))
    }

    @Test
    fun `сокращения типов занятий расшифровываются`() {
        assertEquals("Лекция", kindName("Лек"))
        assertEquals("Практика", kindName("Пр"))
        assertEquals("Курсовая", kindName("Курс.р."))
        assertNull(kindName(null))
        // Незнакомое оставляем как есть: колледж заводит новые пометки.
        assertEquals("Вебинар", kindName("Вебинар"))
    }

    @Test
    fun `номер аудитории подписывается, название — нет`() {
        assertEquals("каб. 272", roomLabel("272"))
        assertEquals("каб. 171/3", roomLabel("171/3"))
        assertEquals(
            "Спортзал Б.Хмельницкого 2",
            roomLabel("Спортзал Б.Хмельницкого 2"),
        )
        assertNull(roomLabel(null))
        assertNull(roomLabel("  "))
    }
}
