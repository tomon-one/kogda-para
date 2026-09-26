package ru.whensclass.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Онлайн ли пара — по признаку `o`, а без него по ссылке без аудитории.
 *
 * «Преподаватель на онлайн, студенты в кабинете 269»: у пары и ссылка, и
 * кабинет, а студентам — в кабинет. Раньше любая ссылка делала пару онлайн, и
 * приложение говорило остаться дома.
 */
class LessonOnlineTest {

    private fun lesson(online: Int = 0, url: String? = null, room: String? = null) =
        LessonDto(number = 1, subject = "Русский язык", online = online, url = url, room = room)

    @Test
    fun `ссылка при кабинете — очная пара`() {
        assertFalse(lesson(url = "https://my.mts-link.ru/j/1", room = "269").isOnline)
    }

    @Test
    fun `ссылка без кабинета — онлайн даже без признака`() {
        // Снимок, скачанный до появления поля «o».
        assertTrue(lesson(url = "https://my.mts-link.ru/j/1").isOnline)
    }

    @Test
    fun `признак без ссылки — онлайн`() {
        assertTrue(lesson(online = 1, room = "12").isOnline)
    }

    @Test
    fun `ни признака, ни ссылки — очная`() {
        assertFalse(lesson(room = "272").isOnline)
    }
}
