package ru.whensclass.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Поиск по группам и преподавателям. */
class SearchTest {

    @Test
    fun `дефис и пробел не мешают найти группу`() {
        assertTrue(matchesQuery("ИСП-924/1", "исп 924"))
        assertTrue(matchesQuery("ИСП-924/1", "исп924"))
        assertTrue(matchesQuery("ИСП-924/1", "924/1"))
        assertTrue(matchesQuery("ИСП-924/1", "  "))
        assertFalse(matchesQuery("ИСП-924/1", "925"))
    }

    @Test
    fun `е находит ё`() {
        assertTrue(matchesQuery("Чернышёва Валерия Дмитриевна", "Чернышева"))
        assertTrue(matchesQuery("Чернышёва Валерия Дмитриевна", "чернышёва в"))
    }
}
