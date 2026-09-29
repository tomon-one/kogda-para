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

/** Уведомления и состояние телефона. */
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

    @Test
    fun `повтор строки встаёт на своё последнее место`() {
        // «вернули → отменили → вернули» читалось последней строкой «отменили»
        // (контроль №1 прогона 1 аудита 4).
        val today = LocalDate.of(2026, 9, 29)
        val back = "2026-09-29" to "вернули 2 пару: Физика"
        val off = "2026-09-29" to "отменили 2 пару: Физика"
        assertEquals(listOf(off, back), mergeChanges(listOf(back, off), listOf(back), today))
    }

    @Test
    fun `свежая правка длиннее шторки — сначала сегодня`() {
        // Строки идут по дням: «последние восемь» выбрасывали сегодняшнее ради
        // завтрашнего (М33).
        val today = LocalDate.of(2026, 9, 29)
        val fresh = (1..3).map { "2026-09-29" to "сегодня $it" } + (1..7).map { "2026-09-30" to "завтра $it" }
        val merged = mergeChanges(listOf("2026-09-29" to "висело"), fresh, today)
        assertEquals(MAX_CHANGE_LINES, merged.size)
        assertEquals("сегодня 1", merged.first().second)
    }

    @Test
    fun `из висящих строк сначала уходит завтрашнее, а не сегодняшнее`() {
        // Утром висело «отменили 1 пару» и шесть строк о завтра, днём пришли ещё
        // две о завтра: сегодняшняя пропадала (прогон 2 аудита 4).
        val today = LocalDate.of(2026, 9, 29)
        val pending = listOf("2026-09-29" to "отменили 1 пару") + (1..6).map { "2026-09-30" to "завтра $it" }
        val fresh = (7..8).map { "2026-09-30" to "завтра $it" }
        val merged = mergeChanges(pending, fresh, today)
        assertEquals(MAX_CHANGE_LINES, merged.size)
        assertEquals("отменили 1 пару", merged.first().second)
        assertEquals(fresh, merged.takeLast(2))
    }
}
