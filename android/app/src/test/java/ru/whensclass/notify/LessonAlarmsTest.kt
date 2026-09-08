package ru.whensclass.notify

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Заголовок напоминания.
 *
 * Проверяем не текст ради текста: раньше остаток брался из настройки, и
 * уведомление обещало «через 20 минут» независимо от того, когда система
 * соизволила разбудить телефон.
 */
class LessonAlarmsTest {

    private val start = LocalDateTime.of(2026, 9, 8, 12, 30)

    @Test
    fun `остаток считается от текущего времени, а не от настройки`() {
        assertEquals(
            "Через 20 минут — Физика",
            LessonAlarms.title("Физика", start, start.minusMinutes(20)),
        )
        // Будильник разбудил телефон с опозданием — текст обязан это заметить.
        assertEquals(
            "Через 3 минуты — Физика",
            LessonAlarms.title("Физика", start, start.minusMinutes(3)),
        )
    }

    @Test
    fun `часы называются часами`() {
        assertEquals(
            "Через 3 часа 5 минут — Английский",
            LessonAlarms.title("Английский", start, start.minusMinutes(185)),
        )
    }

    @Test
    fun `опоздавшее напоминание не обещает будущего`() {
        assertEquals(
            "Пара начинается — Физика",
            LessonAlarms.title("Физика", start, start.minusSeconds(10)),
        )
        assertEquals(
            "Пара уже идёт — Физика",
            LessonAlarms.title("Физика", start, start.plusMinutes(15)),
        )
    }
}
