package ru.whensclass.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.UnreadDay
import ru.whensclass.data.combineGroups

/**
 * Дни, которых в ответе нет, а по листу колледжа они есть: воскресений в листе
 * не бывает вовсе, и так же молча пропал бы любой другой день без строки.
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
        // Вставленный день помечен: экран пишет «Выходной», а не «Пар нет. Это
        // не ошибка» — дня в листе нет вовсе.
        assertEquals(listOf(false, true, false), filled.map { it.absent })
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
        // Лист кончился 12-го, а окно просило до 14-го. За краем листа
        // расписания может не быть вовсе.
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

    @Test
    fun `пометка непрочитанного дня — из ответа, с именем группы, если она не своя`() {
        val body = """{"g":"isp-924-1","gn":"ИСП-924/1","gen":"G","cov":["2026-09-07","2026-09-09"],"days":[
            {"d":"2026-09-07","l":[]},
            {"d":"2026-09-08","l":[{"n":1,"s":"Физика"}],"un":"kept"},
            {"d":"2026-09-09","l":[],"un":"missing"},
            {"d":"2026-09-10","l":[],"un":"вздор"}]}"""
        val days = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<ScheduleDto>(body).days
        assertEquals(listOf(null, UnreadDay.KEPT, UnreadDay.MISSING, null), days.map { it.unread })
        assertEquals(listOf("Сервер не смог прочитать этот день в таблице: пары — какими были до этого."),
            unreadNotes(days[1]))
        assertEquals(listOf("Сервер не смог прочитать этот день в таблице."), unreadNotes(days[2]))
        // Непрочитанный будний — не «повезло дважды» накануне.
        assertEquals(false, freeOwnDay(days[2], emptyList()))
        // Преподавателю — по группам, из-за которых пометка (тексты — как у сайта).
        assertEquals(listOf("Сервер не смог прочитать этот день у ПХД-923/1: пар с ней может не хватать."),
            unreadNotes(DayDto(date = "2026-09-09", unreadMark = "missing", unreadGroups = listOf("ПХД-923/1"))))
        assertEquals(listOf("Сервер не смог прочитать этот день у А-1, Б-1 и В-1: пары с ними — какими были до этого."),
            unreadNotes(DayDto(date = "2026-09-08", unreadMark = "kept", unreadGroups = listOf("А-1", "Б-1", "В-1"))))
        assertEquals(listOf("Сервер не смог прочитать этот день у 26 групп: пар с ними может не хватать."),
            unreadNotes(DayDto(date = "2026-09-09", unreadMark = "missing", unreadGroups = List(26) { "Г-$it" })))
        // Не прочитан у одной группы, у других пары прежние — обе строки.
        assertEquals(
            listOf(
                "Сервер не смог прочитать этот день у Э-1125: пар с ней может не хватать.",
                "Сервер не смог прочитать этот день у МФ-926/1 и СКД-926: пары с ними — какими были до этого.",
            ),
            unreadNotes(DayDto(date = "2026-09-21", unreadMark = "missing", unreadGroups = listOf("Э-1125"),
                unreadKept = listOf("МФ-926/1", "СКД-926"))),
        )
        // Другая выбранная группа: свой день без пометки, её — с её именем; за
        // краем своего окна её дни не добавляются.
        val other = ScheduleDto(
            groupId = "isp-924-2", groupName = "ИСП-924/2", generatedAt = "G",
            days = listOf(DayDto(date = "2026-09-07", unreadMark = "missing"), DayDto(date = "2026-09-12", unreadMark = "missing")),
        )
        val merged = combineGroups(schedule(listOf(), "2026-09-07"), listOf("Б" to other))
        assertEquals(listOf("2026-09-07"), dates(merged.days))
        assertEquals(null, merged.days[0].unread)
        assertEquals(listOf("Сервер не смог прочитать этот день у Б: её пар здесь нет."), unreadNotes(merged.days[0]))
    }
}
