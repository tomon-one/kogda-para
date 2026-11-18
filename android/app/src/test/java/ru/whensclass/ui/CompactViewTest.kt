package ru.whensclass.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Компактный вид и стык недель — правила, не зависящие от вёрстки. */
class CompactViewTest {

    @Test
    fun `не выбирали — решает ширина экрана`() {
        assertTrue(compactView(choice = null, widthDp = 320f))
        assertTrue(compactView(choice = null, widthDp = 359f))
        assertFalse(compactView(choice = null, widthDp = 360f))
        assertFalse(compactView(choice = null, widthDp = 411f))
    }

    @Test
    fun `выбор сильнее ширины`() {
        assertTrue(compactView(choice = true, widthDp = 411f))
        assertTrue(compactView(choice = true, widthDp = 320f))
        // Выключили на тесном экране — так и показываем: как на сайте.
        assertFalse(compactView(choice = false, widthDp = 320f))
        assertFalse(compactView(choice = false, widthDp = 411f))
    }

    @Test
    fun `разделитель стоит только на стыке воскресенья и понедельника`() {
        assertTrue(startsNextWeek("2026-11-22", "2026-11-23"))
        assertFalse(startsNextWeek("2026-11-23", "2026-11-24"))
        assertFalse(startsNextWeek("2026-11-21", "2026-11-23"))
        assertFalse(startsNextWeek(null, "2026-11-23"))
        assertFalse(startsNextWeek("2026-11-22", null))
        // С пропуском дней (воскресенья в листе не бывает) разделитель не выдумываем.
        assertFalse(startsNextWeek("2026-11-21", "2026-11-24"))
    }
}
