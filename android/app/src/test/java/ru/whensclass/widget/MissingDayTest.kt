package ru.whensclass.widget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.whensclass.data.DayDto
import ru.whensclass.data.ScheduleDto

/**
 * Почему дня нет в расписании — разные ответы, и путать их нельзя: «пар нет»
 * и «сервер сломался» не должны выглядеть одинаково. Каждый случай закреплён
 * отдельно — вместе с тем, зовёт ли виджет в таблицу колледжа, — и на
 * воскресенье, и на будний день.
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

    @Test
    fun `будень внутри окна и листа — выходной, и таблица не нужна`() {
        val answer = missingDay(
            schedule("2026-09-02", "2026-09-19", "2026-09-12", "2026-09-19"), monday, false,
        )
        assertEquals("Выходной", answer.text)
        assertFalse("колледж уже ответил про этот день", answer.toSource)
    }

    @Test
    fun `воскресенье — выходной, даже за краем листа`() {
        // cov кончается субботой — так каждое воскресенье до новой недели.
        val answer = missingDay(schedule("2026-09-07", "2026-09-12"), sunday, false)
        assertEquals("Выходной", answer.text)
    }

    @Test
    fun `будень новой недели при окне прошлой — не выходной`() {
        // Окно 07–14.09 на телефоне, cov листа — до 19.09, сегодня вторник 15.09:
        // окно осталось от прошлой недели (нет сети, сервер лёг).
        val tuesday = LocalDate.of(2026, 9, 15)
        val window = schedule("2026-09-02", "2026-09-19", "2026-09-07", "2026-09-14")
        assertEquals(
            "Сбой: расписание не обновляется",
            missingDay(window, tuesday, true).text,
        )
        // Сервер цел, а дня нет на телефоне — «не загружено», не «выходной».
        // Давность данных сама по себе не «устарели»: красное — только сбой.
        assertEquals(
            "Расписание на этот день не загружено",
            missingDay(window, tuesday, false).text,
        )
    }

    @Test
    fun `день за краем листа — зовём в таблицу`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), monday, false)
        assertEquals("Расписание на этот день ещё не опубликовано", answer.text)
        // Утверждение о колледже, которое приложение проверить не может: так же
        // выглядит и промах сервера с поиском листа. Значит — выход к источнику.
        assertTrue(answer.toSource)
    }

    @Test
    fun `телефон сегодня сервер не проверял — «не загружено», а не «не опубликовано»`() {
        // Телефон, который не будят в фоне, с четверга не видел недели,
        // выложенной в пятницу.
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), monday, false, checked = false)
        assertEquals("Расписание на этот день не загружено", answer.text)
        assertTrue(!answer.toSource)
        val start = monday.atStartOfDay(COLLEGE_ZONE).toInstant().toEpochMilli()
        assertTrue(checkedToday(start, monday))
        assertTrue(!checkedToday(start - 1, monday))
    }

    @Test
    fun `сбой сервера называем сбоем, а не отсутствием расписания`() {
        val answer = missingDay(schedule("2026-09-02", "2026-09-12"), monday, true)
        assertEquals("Сбой: расписание не обновляется", answer.text)
        assertTrue(answer.toSource)
    }

    @Test
    fun `неделя в воскресенье без выложенной следующей — не «выходной», а к таблице`() {
        // Для недели воскресенье — не «выходной» без выхода к таблице.
        val answer = missingDay(schedule("2026-09-07", "2026-09-12"), sunday, false, week = true)
        assertEquals("Расписание на эти дни ещё не опубликовано", answer.text)
        assertTrue(answer.toSource)
    }
}
