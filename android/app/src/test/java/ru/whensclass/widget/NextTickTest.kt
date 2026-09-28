package ru.whensclass.widget

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Часы экрана и будильник перерисовки ждут ближайшего звонка или полуночи. */
class NextTickTest {

    private val bells = mapOf("1" to listOf("09:00", "10:30"), "2" to listOf("10:40", "12:10"))

    @Test
    fun `ближайший — конец идущей пары`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 8, 10, 30),
            nextTick(bells, LocalDateTime.of(2026, 9, 8, 9, 15)),
        )
    }

    @Test
    fun `после последнего звонка — полночь с минутой`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 9, 0, 1),
            nextTick(bells, LocalDateTime.of(2026, 9, 8, 12, 10)),
        )
    }

    @Test
    fun `без сетки звонков — только полночь`() {
        assertEquals(
            LocalDateTime.of(2026, 9, 9, 0, 1),
            nextTick(emptyMap(), LocalDateTime.of(2026, 9, 8, 9, 15)),
        )
    }

    @Test
    fun `в первую минуту суток — сегодняшние 00_01, а не первый звонок`() {
        // Будильник, заведённый в 00:00:30, перешагивал сегодняшнюю полночь и
        // вставал на 09:00: листание «на завтра» держалось всё утро.
        assertEquals(
            LocalDateTime.of(2026, 9, 28, 0, 1),
            nextTick(bells, LocalDateTime.of(2026, 9, 28, 0, 0, 30)),
        )
    }

    @Test
    fun `вчера считается от переданного момента`() {
        val fetched = java.time.ZonedDateTime.of(2026, 9, 8, 7, 50, 0, 0, java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val nextDay = fetched + java.time.Duration.ofHours(18).toMillis()
        assertEquals("07:50", formatFetchedShort(fetched, fetched + 60_000))
        assertEquals("вчера 07:50", formatFetchedShort(fetched, nextDay))
    }
}
