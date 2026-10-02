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
        assertTrue(matchesQuery("Королёва Валерия Дмитриевна", "Королева"))
        assertTrue(matchesQuery("Королёва Валерия Дмитриевна", "королёва в"))
    }
}

class LetterTest {
    @org.junit.Test
    fun `разделы по первой букве`() {
        org.junit.Assert.assertEquals("И", letterOf("ИСП-924/1"))
        org.junit.Assert.assertEquals("0–9", letterOf("01.26.Р.ИИ.ГД.ОФ.9-НСК"))
        org.junit.Assert.assertEquals("Е", letterOf("Ёринобу А. А."))
        org.junit.Assert.assertEquals("#", letterOf(""))
    }
}
