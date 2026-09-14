package ru.whensclass.widget

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import ru.whensclass.data.LessonDto
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

    @Test
    fun `длинный формат склоняет слова`() {
        assertEquals("20 минут", formatDurationLong(20))
        assertEquals("минуту", formatDurationLong(1))
        assertEquals("2 минуты", formatDurationLong(2))
        // Одиннадцать — не «11 минута», хотя оканчивается на единицу.
        assertEquals("11 минут", formatDurationLong(11))
        assertEquals("час", formatDurationLong(60))
        assertEquals("2 часа", formatDurationLong(120))
        assertEquals("3 часа 5 минут", formatDurationLong(185))
        assertEquals("час 1 минуту", formatDurationLong(61))
    }

    @Test
    fun `короткий формат остаётся коротким`() {
        assertEquals("20 мин", formatDurationShort(20))
        assertEquals("3 ч", formatDurationShort(180))
        assertEquals("3 ч 5 мин", formatDurationShort(185))
    }

    @Test
    fun `онлайн-комната с номером называется без «каб»`() {
        val plain = LessonDto(number = 1, subject = "Информатика", online = 1)
        assertEquals("онлайн", onlineLabel(plain))
        val numbered = plain.copy(room = "12")
        assertEquals("онлайн · 12", onlineLabel(numbered))
        // «каб.» — про кабинеты, а комната внутри онлайна не кабинет.
        assertTrue(!onlineLabel(numbered).contains("каб"))
    }

    @Test
    fun `глагол в «прошло N пар» согласуется с числом`() {
        assertEquals("прошла 1 пара", passedPairs(1))
        assertEquals("прошли 2 пары", passedPairs(2))
        assertEquals("прошло 5 пар", passedPairs(5))
        assertEquals("прошло 11 пар", passedPairs(11))
        assertEquals("прошла 21 пара", passedPairs(21))
    }
}
