package ru.whensclass.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebinarLinkTest {

    @Test
    fun `площадки колледжа — свои`() {
        assertTrue(isKnownWebinar("https://my.mts-link.ru/j/100000001/20000000028"))
        assertTrue(isKnownWebinar("https://us06web.zoom.us/j/123"))
        assertTrue(isKnownWebinar("https://mts-link.ru/j/1"))
    }

    @Test
    fun `похожие и чужие — чужие`() {
        assertFalse(isKnownWebinar("https://my.mts-link.ru.evil.example/j/1"))
        assertFalse(isKnownWebinar("https://evilmts-link.ru/j/1"))
        assertFalse(isKnownWebinar("http://my.mts-link.ru/j/1"))
        assertFalse(isKnownWebinar("https://example.com/j/1"))
        assertFalse(isKnownWebinar("не ссылка"))
    }
}
