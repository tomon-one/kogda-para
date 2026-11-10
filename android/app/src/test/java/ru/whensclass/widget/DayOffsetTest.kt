package ru.whensclass.widget

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Листание «Дня» не переживает смену суток, даже без будильника. */
class DayOffsetTest {
    private val tuesday = LocalDate.of(2026, 11, 10)

    @Test
    fun `в тот же день сдвиг остаётся`() {
        assertEquals(2, dayOffset(tuesday, 2, tuesday.atTime(23, 50)))
    }

    @Test
    fun `на следующий день сдвиг сброшен`() {
        assertEquals(0, dayOffset(tuesday, 2, LocalDateTime.of(2026, 11, 11, 8, 0)))
    }

    @Test
    fun `не листали — сдвига нет`() {
        assertEquals(0, dayOffset(null, 0, tuesday.atTime(9, 0)))
    }
}
