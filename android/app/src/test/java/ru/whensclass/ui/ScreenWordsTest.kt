package ru.whensclass.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.HttpFailure

/** Слова и позиция экрана расписания. */
class ScreenWordsTest {

    @Test
    fun `503 — расписания ещё нет, а не «сервер занят»`() {
        assertEquals("Сервер занят, попробуйте через минуту", refreshFailure(HttpFailure(429, "/v1/meta")))
        assertEquals(
            "Сервер сейчас не отдаёт это расписание, на экране прежнее. Не пройдёт за час — " +
                "напишите автору в Telegram: @toomonn",
            refreshFailure(HttpFailure(503, "/v1/meta")),
        )
        assertEquals("Не удалось обновить: нет связи с сервером", refreshFailure(java.io.IOException()))
    }

    @Test
    fun `счёт идёт с — в родительном падеже`() {
        val today = LocalDate.of(2026, 9, 25)
        assertEquals("с сегодняшнего дня", tallySince(today, today))
        assertEquals("со вчерашнего дня", tallySince(today.minusDays(1), today))
        assertEquals("с 23 сентября", tallySince(today.minusDays(2), today))
    }

    @Test
    fun `дня нет в окне — ближайший к нему, а не понедельник`() {
        val days = listOf("2026-09-14", "2026-09-15", "2026-09-21").map { DayDto(date = it) }
        assertEquals(1, dayIndex(days, "2026-09-15"))
        // Воскресенья нет — открываемся на следующем дне.
        assertEquals(2, dayIndex(days, "2026-09-20"))
        // Окно прошлой недели, а сегодня четверг: конец окна, не начало.
        assertEquals(2, dayIndex(days, "2026-09-24"))
        assertEquals(0, dayIndex(emptyList(), "2026-09-24"))
    }

    @Test
    fun `плашки — строкой над днём, на котором список открылся`() {
        // Дни 0..4, плашки перед днём 2: строки 0, 1, плашки, 2, 3, 4.
        val rows = DayRows(2)
        assertEquals(listOf(0, 1, 3, 4, 5), (0..4).map(rows::of))
        assertEquals(listOf(0, 1, null, 2, 3, 4), (0..5).map(rows::day))
        // К дню 2 — вместе с плашками над ним, к остальным — к самому дню.
        assertEquals(2, rows.toward(2))
        assertEquals(4, rows.toward(3))
        assertEquals(1, rows.toward(1))
        // Плашек нет — строка и есть день.
        val plain = DayRows(-1)
        assertEquals(listOf(0, 1, 2), (0..2).map(plain::of))
        assertEquals(listOf(0, 1, 2), (0..2).map(plain::day))
        assertEquals(2, plain.toward(2))
    }
}
