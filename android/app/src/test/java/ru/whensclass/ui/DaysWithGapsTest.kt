package ru.whensclass.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto

/**
 * Дни, которых в ответе нет, а по листу колледжа они есть.
 *
 * Воскресений в листе не бывает вовсе, поэтому сервер такой день не присылает.
 * В списке суббота сменялась понедельником без единого слова — и так же молча
 * пропал бы любой другой день, которого в данных не оказалось.
 *
 * Дневной виджет различает это с первого аудита. Главный экран — с 9 сентября.
 */
class DaysWithGapsTest {

    private fun schedule(cov: List<String>, vararg dates: String) = ScheduleDto(
        groupId = "isp-924-1",
        groupName = "ИСП-924/1",
        generatedAt = "2026-09-09T00:00:00Z",
        coverage = cov,
        days = dates.map {
            DayDto(date = it, lessons = listOf(LessonDto(number = 1, subject = "Пара")))
        },
    )

    private fun dates(days: List<DayDto>) = days.map { it.date }

    @Test
    fun `воскресенье внутри листа появляется пустым днём`() {
        val week = schedule(
            listOf("2026-09-02", "2026-09-12"),
            "2026-09-05", "2026-09-07",
        )

        val filled = daysWithGaps(week)

        assertEquals(listOf("2026-09-05", "2026-09-06", "2026-09-07"), dates(filled))
        assertEquals(emptyList<LessonDto>(), filled[1].lessons)
    }

    @Test
    fun `пришедшие дни не трогаем`() {
        val week = schedule(
            listOf("2026-09-02", "2026-09-12"),
            "2026-09-07", "2026-09-08",
        )

        val filled = daysWithGaps(week)

        assertEquals(dates(week.days), dates(filled))
        assertEquals(1, filled.first().lessons.size)
    }

    @Test
    fun `за краем листа дни не выдумываем`() {
        // Лист кончился 12-го, а окно просило до 14-го. Чего в листе нет, о том
        // мы ничего не знаем: там расписания может не быть вовсе.
        val week = schedule(
            listOf("2026-09-02", "2026-09-06"),
            "2026-09-05", "2026-09-09",
        )

        assertEquals(listOf("2026-09-05", "2026-09-06", "2026-09-09"), dates(daysWithGaps(week)))
    }

    @Test
    fun `без покрытия ничего не достраиваем`() {
        // Снимок со старого сервера или битый ответ: сверять не с чем.
        val week = schedule(emptyList(), "2026-09-05", "2026-09-07")

        assertEquals(dates(week.days), dates(daysWithGaps(week)))
    }

    @Test
    fun `один день остаётся одним днём`() {
        val week = schedule(listOf("2026-09-02", "2026-09-12"), "2026-09-07")

        assertEquals(listOf("2026-09-07"), dates(daysWithGaps(week)))
    }

    @Test
    fun `дыра в несколько дней заполняется целиком`() {
        val week = schedule(
            listOf("2026-09-02", "2026-09-12"),
            "2026-09-07", "2026-09-11",
        )

        assertEquals(
            listOf("2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11"),
            dates(daysWithGaps(week)),
        )
    }
}
