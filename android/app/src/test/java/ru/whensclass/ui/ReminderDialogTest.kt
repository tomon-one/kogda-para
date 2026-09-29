package ru.whensclass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Окно выбора минут: подписи плиток и границы своего времени — те же, что у сайта. */
class ReminderDialogTest {

    @Test
    fun `плитки — число крупно, единица мелко`() {
        assertEquals(
            listOf(
                "10" to "мин", "15" to "мин", "20" to "мин", "30" to "мин", "45" to "мин",
                "1" to "час", "1,5" to "часа", "2" to "часа", "3" to "часа", "4" to "часа",
            ),
            REMIND_QUICK.map(::tileLabel),
        )
    }

    @Test
    fun `быстрый выбор — в границах своего времени`() {
        assertTrue(REMIND_QUICK.all { it in REMIND_MIN..REMIND_MAX })
        assertEquals(10, REMIND_QUICK.size)
    }
}
