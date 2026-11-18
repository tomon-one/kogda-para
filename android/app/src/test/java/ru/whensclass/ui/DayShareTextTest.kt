package ru.whensclass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto

/** Подпись к картинке дня: строки для чата — то же, что видно в карточке дня. */
class DayShareTextTest {

    private val bells = mapOf(
        "1" to listOf("09:00", "10:30"),
        "3" to listOf("12:10", "13:40"),
    )

    @Test
    fun `первая строка — звонки дня, дальше пары со ссылкой`() {
        val day = DayDto(
            date = "2026-11-18",
            lessons = listOf(
                LessonDto(number = 1, subject = "Математика"),
                LessonDto(number = 3, subject = "Управление проектами", url = "https://example.test/p"),
            ),
        )
        assertEquals(
            "Пары с 09:00 до 13:40\n3 Пара: Управление проектами, https://example.test/p",
            dayShareText(day, bells),
        )
    }

    @Test
    fun `название — короткое из словаря службы, как на картинке`() {
        val day = DayDto(
            date = "2026-11-18",
            lessons = listOf(
                LessonDto(
                    number = 3,
                    subject = "Управление проектами",
                    shortSubject = "проекты",
                    url = "https://example.test/p",
                ),
            ),
        )
        assertEquals(
            "Пары с 12:10 до 13:40\n3 Пара: Проекты, https://example.test/p",
            dayShareText(day, bells),
        )
    }

    @Test
    fun `без ссылок — только звонки, без пар — подписи нет`() {
        val one = DayDto(date = "2026-11-18", lessons = listOf(LessonDto(number = 1, subject = "Физика")))
        assertEquals("Пары с 09:00 до 10:30", dayShareText(one, bells))
        assertNull(dayShareText(DayDto(date = "2026-11-18"), bells))
        // Всё отменили — пар нет, и подписи тоже.
        assertNull(dayShareText(DayDto(date = "2026-11-18", lessons = listOf(LessonDto(1, "Физика", cancelled = 1))), bells))
    }
}
