package ru.whensclass.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Непрочитанные дни одной-двух групп — не общий сбой: в ответе `refresh`. */
class MetaHealthTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `непрочитанные дни — сервер в порядке, сбой — сбой`() {
        val unread = json.decodeFromString<MetaDto>(
            """{"gen":"G","status":"stale","refresh":"ok","unread":["2026-10-05"],"since":"2026-10-02T11:00:00Z"}""",
        )
        assertEquals("ok", unread.health)
        val down = json.decodeFromString<MetaDto>("""{"gen":"G","status":"stale","refresh":"stale"}""")
        assertEquals("stale", down.health)
        // Сервер до этих полей.
        assertEquals("stale", json.decodeFromString<MetaDto>("""{"gen":"G","status":"stale"}""").health)
    }

    @Test
    fun `номер сборки — в основном счёте, у tested — номер основной`() {
        assertEquals(7, appBuild(7, "main"))
        assertEquals(7, appBuild(704, "tested"))
        assertEquals(8, json.decodeFromString<MetaDto>("""{"gen":"G","min":8}""").minBuild)
    }
}
