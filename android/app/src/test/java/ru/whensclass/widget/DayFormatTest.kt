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
        // «Сегодня» — колледжа, а не машины, где гоняют тесты (М61 прогона 2).
        val today = collegeToday()
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
        val noon = LocalDate.of(2026, 9, 14).atTime(12, 0)
        assertNull(currentLessonNumber(emptyMap(), noon.toLocalDate(), noon))
    }

    @Test
    fun `текущая пара ищется только на сегодня`() {
        val bells = mapOf("1" to listOf("00:00", "23:59"))
        val noon = LocalDate.of(2026, 9, 14).atTime(12, 0)
        assertEquals(1, currentLessonNumber(bells, noon.toLocalDate(), noon))
        assertNull(currentLessonNumber(bells, noon.toLocalDate().plusDays(1), noon))
    }

    @Test
    fun `подсветка живёт от звонка до звонка`() {
        // 14 сентября 2026 недельный виджет с 15:50 до ночи подсвечивал
        // четвёртую пару: момент считался внутри пропускаемой функции.
        // Сам расчёт при этом верен — теперь момент приходит снаружи, и
        // проверить его можно на любом часе.
        val bells = mapOf("4" to listOf("14:20", "15:50"), "5" to listOf("16:00", "17:30"))
        val day = LocalDate.of(2026, 9, 14)
        assertEquals(4, currentLessonNumber(bells, day, day.atTime(14, 20)))
        assertEquals(4, currentLessonNumber(bells, day, day.atTime(15, 50)))
        assertNull(currentLessonNumber(bells, day, day.atTime(15, 55)))
        assertEquals(5, currentLessonNumber(bells, day, day.atTime(16, 0)))
        assertNull(currentLessonNumber(bells, day, day.atTime(20, 43)))
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

    @Test
    fun `давность сбоя — по-русски и по местному времени`() {
        // Тесты идут в UTC (build.gradle.kts): «местное» — часы телефона, а
        // не колледжа; ожидание — числом, а не той же формулой, что код.
        val since = java.time.Instant.parse("2026-09-11T04:00:00Z")
        val prefix = "11 сентября, 04:00"
        assertEquals("$prefix — уже 40 минут", formatSince("2026-09-11T04:00:00Z", since.plusSeconds(40 * 60)))
        assertEquals("$prefix — уже 3 часа", formatSince("2026-09-11T04:00:00Z", since.plusSeconds(3 * 3600 + 5)))
        assertEquals("$prefix — уже 2 дня", formatSince("2026-09-11T04:00:00Z", since.plusSeconds(50 * 3600)))
        assertEquals("не дата", formatSince("не дата"))
    }

    @Test
    fun `время колледжа — в абсолютное по Новосибирску, в любом поясе телефона`() {
        // Будильники напоминаний и звонков ставятся через millisOf: 09:00 по
        // колледжу — это 02:00 UTC, где бы ни шёл тест (М61 прогона 2).
        assertEquals(
            java.time.Instant.parse("2026-09-08T02:00:00Z").toEpochMilli(),
            millisOf(java.time.LocalDateTime.of(2026, 9, 8, 9, 0)),
        )
        assertEquals("UTC", java.util.TimeZone.getDefault().id)
    }
}
