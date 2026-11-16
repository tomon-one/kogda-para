package ru.whensclass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.DayState

/** Календарный график: поле `st` в дне и слова для человека. */
class DayStateTest {

    @Test
    fun `st в дне читается как состояние`() {
        assertEquals(DayState.SESSION, DayDto(date = "2026-12-21", stateMark = "session").state)
        assertEquals(DayState.HOLIDAYS, DayDto(date = "2026-12-28", stateMark = "holidays").state)
        assertEquals(DayState.PRACTICE, DayDto(date = "2026-11-16", stateMark = "practice").state)
        // Пусто и незнакомое — обычный учебный день: старые сборки службы поля не шлют.
        assertNull(DayDto(date = "2026-11-17").state)
        assertNull(DayDto(date = "2026-11-17", stateMark = "что-то").state)
    }

    @Test
    fun `слова состояния`() {
        assertEquals("Сессия", stateDayText(DayState.SESSION))
        assertEquals("Каникулы", stateDayText(DayState.HOLIDAYS))
        assertEquals("Практика", stateDayText(DayState.PRACTICE))
    }
}
