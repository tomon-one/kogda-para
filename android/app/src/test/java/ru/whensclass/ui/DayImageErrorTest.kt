package ru.whensclass.ui

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.whensclass.data.HttpFailure

/** Неудачи картинки дня — словами: код ответа человеку ничего не говорит. */
class DayImageErrorTest {

    @Test
    fun `дня нет в таблице`() {
        assertEquals("Картинка за этот день недоступна", dayImageError(HttpFailure(404, "/v1/day-image/x.png")))
    }

    @Test
    fun `сервер не смог`() {
        assertEquals("Сервер не смог собрать картинку. Попробуйте позже", dayImageError(HttpFailure(503, "/v1/day-image/x.png")))
    }

    @Test
    fun `нет связи`() {
        assertEquals("Картинка не пришла: проверьте связь", dayImageError(IOException("timeout")))
    }
}
