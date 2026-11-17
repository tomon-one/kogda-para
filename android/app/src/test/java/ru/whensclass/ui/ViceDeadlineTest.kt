package ru.whensclass.ui

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Срок временной темы «Vice»: до 1 января 2027 по часам колледжа. */
class ViceDeadlineTest {

    @Test
    fun `31 декабря — ещё работает`() {
        assertTrue(viceOffered(LocalDate.of(2026, 12, 31)))
        assertTrue(viceActive(true, LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `с 1 января не действует`() {
        assertFalse(viceOffered(LocalDate.of(2027, 1, 1)))
        assertFalse(viceActive(true, LocalDate.of(2027, 1, 1)))
        assertFalse(viceActive(true, LocalDate.of(2027, 6, 1)))
    }

    @Test
    fun `выключенный флаг — никогда`() {
        assertFalse(viceActive(false, LocalDate.of(2026, 11, 17)))
        assertFalse(viceActive(false, LocalDate.of(2026, 12, 31)))
        assertFalse(viceActive(false, LocalDate.of(2027, 1, 1)))
    }
}
