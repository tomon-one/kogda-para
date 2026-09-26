package ru.whensclass.widget

import java.time.Duration
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.ScheduleDto

/**
 * Почему дня нет в расписании — разные ответы, и путать их нельзя.
 *
 * Один раз уже спутали: «пар нет» и «сервер сломался» выглядели одинаково, и
 * человек спокойно ждал расписания, которого мы уже не принесли. Здесь каждый
 * случай закреплён отдельно — вместе с тем, зовём ли мы в таблицу колледжа.
 *
 * До 26.09.2026 все случаи гонялись на 13.09 — воскресенье; будний день не
 * проверялся вовсе (третий аудит, В20 прогона 1).
 */
class MissingDayTest {

    private val monday = LocalDate.of(2026, 9, 14)
    private val sunday = LocalDate.of(2026, 9, 13)

    /** Окно на телефоне — дни [first]..[last], покрытие листа — [from]..[to]. */
    private fun schedule(from: String, to: String, first: String? = null, last: String? = null) =
        ScheduleDto(
            groupId = "isp-924-1",
            groupName = "ИСП-924/1",
            generatedAt = "2026-09-08T15:40:02Z",
            coverage = listOf(from, to),
            days = listOfNotNull(first, last).map { DayDto(date = it) },
        )

    private val fresh = System.currentTimeMillis()
    private val old = System.currentTimeMillis() - Duration.ofHours(13).toMillis()

    @Test
    fun `будень внутри окна и листа — выходной, и таблица не нужна`() {
        val answer = missingDay(
            schedule("2026-09-02", "2026-09-19", "2026-09-12", "2026-09-19"), monday, fresh, false,
        )
        assertEquals("Выходной", answer.text)
        assertFalse("колледж уже ответил про этот день", answer.toSource)
    }

    @Test
    fun `воскресенье — выходной, даже за краем листа`() {
        // cov кончается субботой — так каждое воскресенье до новой недели
        // (третий аудит, М17 прогона 1).
        val answer = missingDay(schedule("2026-09-07", "2026-09-12"), sunday, fresh, false)
        assertEquals("Выходной", answer.text)
    }

    @Test
    fun `будень новой недели при окне прошлой — не выходной`() {
        // Окно 07–14.09 на телефоне, cov листа — до 19.09, сегодня вторник 15.09:
        // окно осталось от прошлой недели (нет сети, наш сервер лёг).
        val tuesday = LocalDate.of(2026, 9, 15)
        val window = schedule("2026-09-02", "2026-09-19", "2026-09-07", "2026-09-14")
        assertEquals(
            "Данные устарели",
            missingDay(window, tuesday, old, false).text,
        )
        assertEquals(
            "Сбой: расписание не обновляется",
            missingDay(window, tuesday, fresh, true).text,
        )
        // Свежие данные, сервер цел, а дня всё равно нет на телефоне (быстрое
        // «›» за край окна, М13 прогона 2) — «не загружено», не «выходной».
        assertEquals(
            "Расписание на этот день не загружено",
            missingDay(window, tuesday, fresh, false).text,
        )
    }

    @Test
    fun `день за краем листа — зовём в таблицу`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), monday, fresh, false)
        assertEquals("Расписание на этот день ещё не опубликовано", answer.text)
        // Утверждение о колледже, которое приложение проверить не может: так же
        // выглядит и наш промах с поиском листа. Значит — выход к источнику.
        assertTrue(answer.toSource)
    }

    @Test
    fun `сбой сервера называем сбоем, а не отсутствием расписания`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), monday, fresh, true)
        assertEquals("Сбой: расписание не обновляется", answer.text)
        assertTrue(answer.toSource)
    }

    @Test
    fun `при сбое не советуем обновиться, даже если данные успели постареть`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), monday, old, true)
        // «Нажмите на время в шапке» здесь было бы отправкой к кнопке,
        // которая при сбое сервера ничего не изменит.
        assertEquals("Сбой: расписание не обновляется", answer.text)
    }

    @Test
    fun `старые данные без сбоя — предлагаем обновиться`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), monday, old, false)
        assertEquals("Данные устарели", answer.text)
        assertFalse("сначала стоит просто обновиться", answer.toSource)
    }

    @Test
    fun `неделя в воскресенье без выложенной следующей — не «выходной», а к таблице`() {
        // Раньше воскресенье отвечало «выходной» первой проверкой и для
        // недели: «Выходной: пар в эти дни нет» без выхода к таблице (разбор
        // текстов 27.09).
        val answer = missingDay(schedule("2026-09-07", "2026-09-12"), sunday, fresh, false, week = true)
        assertEquals("Расписание на эти дни ещё не опубликовано", answer.text)
        assertTrue(answer.toSource)
    }
}
