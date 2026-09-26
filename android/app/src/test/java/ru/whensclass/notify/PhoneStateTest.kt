package ru.whensclass.notify

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.whensclass.data.MAX_CHANGE_LINES
import ru.whensclass.data.mergeChanges

/** Уведомления и состояние телефона. Третий аудит: В19, М12 прогона 2. */
class PhoneStateTest {

    private val now = Instant.parse("2026-09-26T05:00:00Z")

    @Test
    fun `пояс не колледжа и выставлен вручную — предупреждаем`() {
        assertNotNull(PhoneState.zoneWarning(false, ZoneId.of("Europe/Moscow"), now))
        // Сам определяется — часы и пояс от сети, съехать нечему.
        assertNull(PhoneState.zoneWarning(true, ZoneId.of("Europe/Moscow"), now))
        // Пояс колледжа — хоть и вручную, время то же.
        assertNull(PhoneState.zoneWarning(false, ZoneId.of("Asia/Novosibirsk"), now))
        assertNull(PhoneState.zoneWarning(false, ZoneId.of("+07:00"), now))
    }

    @Test
    fun `непрочитанная отмена не пропадает под новым изменением`() {
        val today = LocalDate.of(2026, 9, 26)
        val morning = listOf("2026-09-27" to "отменили 1 пару: Физика")
        val evening = listOf("2026-09-26" to "у 5 пары появилась ссылка")
        assertEquals(morning + evening, mergeChanges(morning, evening, today))
        // Повтор той же строки не дублируется, прошедший день уходит.
        val stale = listOf("2026-09-25" to "убрали 2 пару: Химия")
        assertEquals(morning, mergeChanges(stale + morning, morning, today))
    }

    @Test
    fun `строк не больше, чем развернёт шторка`() {
        val today = LocalDate.of(2026, 9, 26)
        val many = (1..12).map { "2026-09-26" to "изменение $it" }
        val merged = mergeChanges(many, emptyList(), today)
        assertEquals(MAX_CHANGE_LINES, merged.size)
        assertEquals("изменение 12", merged.last().second)
    }
}
