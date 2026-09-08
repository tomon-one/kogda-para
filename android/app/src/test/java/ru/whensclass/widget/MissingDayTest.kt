package ru.whensclass.widget

import java.time.Duration
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.whensclass.data.ScheduleDto

/**
 * Почему дня нет в расписании — четыре разных ответа, и путать их нельзя.
 *
 * Один раз уже спутали: «пар нет» и «сервер сломался» выглядели одинаково, и
 * человек спокойно ждал расписания, которого мы уже не принесли. Здесь каждый
 * случай закреплён отдельно — вместе с тем, зовём ли мы в таблицу колледжа.
 */
class MissingDayTest {

    private val day = LocalDate.of(2026, 9, 13)

    private fun schedule(from: String, to: String) = ScheduleDto(
        groupId = "isp-924-1",
        groupName = "ИСП-924/1",
        generatedAt = "2026-09-08T15:40:02Z",
        coverage = listOf(from, to),
    )

    private val fresh = System.currentTimeMillis()
    private val old = System.currentTimeMillis() - Duration.ofHours(13).toMillis()

    @Test
    fun `день внутри листа — выходной, и таблица не нужна`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-19"), day, fresh, false)

        assertEquals("Выходной: пар в этот день нет", answer.text)
        assertFalse("колледж уже ответил про этот день", answer.toSource)
    }

    @Test
    fun `день за краем листа — зовём в таблицу`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), day, fresh, false)

        assertEquals("Расписание на этот день ещё не опубликовано", answer.text)
        // Утверждение о колледже, которое приложение проверить не может: так же
        // выглядит и наш промах с поиском листа. Значит — выход к источнику.
        assertTrue(answer.toSource)
    }

    @Test
    fun `сбой сервера называем сбоем, а не отсутствием расписания`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), day, fresh, true)

        assertEquals("Сбой у нас: расписание не обновляется", answer.text)
        assertTrue(answer.toSource)
    }

    @Test
    fun `при сбое не советуем обновиться, даже если данные успели постареть`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), day, old, true)

        // «Нажмите на время в шапке» здесь было бы отправкой к кнопке,
        // которая при сбое сервера ничего не изменит.
        assertEquals("Сбой у нас: расписание не обновляется", answer.text)
    }

    @Test
    fun `старые данные без сбоя — предлагаем обновиться`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), day, old, false)

        assertEquals("Данные устарели. Нажмите на время в шапке", answer.text)
        assertFalse("сначала стоит просто обновиться", answer.toSource)
    }
}
