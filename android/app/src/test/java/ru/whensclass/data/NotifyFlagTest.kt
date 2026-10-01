package ru.whensclass.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выключатели сбоя сервера и пропажи подгруппы отделены от «изменений», но
 * пока человек их не трогал, повторяют их.
 */
class NotifyFlagTest {

    @Test
    fun `не трогали — как у изменений`() {
        assertTrue(notifyFlag(own = null, changes = null))
        assertTrue(notifyFlag(own = null, changes = "1"))
        assertFalse(notifyFlag(own = null, changes = "0"))
    }

    @Test
    fun `тронули — своё`() {
        assertTrue(notifyFlag(own = "1", changes = "0"))
        assertFalse(notifyFlag(own = "0", changes = "1"))
    }
}
