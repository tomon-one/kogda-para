package ru.whensclass.widget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.whensclass.data.DayState
import ru.whensclass.data.LessonDto
import ru.whensclass.data.UnreadDay

/**
 * Календарный график в виджетах: у пустого дня вместо «пар нет», у дня с парами — тихой
 * меткой, не вытесняя сами пары.
 */
class WidgetDayStateTest {

    private val bells = mapOf(
        "1" to listOf("09:00", "10:30"),
        "2" to listOf("10:40", "12:10"),
    )
    private val lessons = listOf(
        LessonDto(number = 1, subject = "Физика"),
        LessonDto(number = 2, subject = "Химия"),
    )

    private fun day(
        lessons: List<LessonDto> = emptyList(),
        absent: Boolean = false,
        unread: UnreadDay? = null,
        state: DayState? = null,
    ) = WeekDay(
        date = LocalDate.of(2026, 12, 28),
        title = "пн, 28 дек.",
        isToday = false,
        lessons = lessons,
        absent = absent,
        unread = unread,
        state = state,
    )

    @Test
    fun `свёрнутый пустой день в каникулы говорит «каникулы», а не «пар нет»`() {
        // Тот же день, развёрнутый, уже говорил «каникулы» (EmptyLine); сводка расходилась с ним.
        assertEquals("каникулы", summaryText(day(state = DayState.HOLIDAYS), bells))
        assertEquals("сессия", summaryText(day(state = DayState.SESSION), bells))
        assertEquals("практика", summaryText(day(state = DayState.PRACTICE), bells))
        // Без графика и отметок — прежнее «пар нет».
        assertEquals("пар нет", summaryText(day(), bells))
    }

    @Test
    fun `непрочитанность и выходной важнее графика`() {
        assertEquals(
            "не прочитан",
            summaryText(day(unread = UnreadDay.MISSING, state = DayState.HOLIDAYS), bells),
        )
        assertEquals("выходной", summaryText(day(absent = true), bells))
    }

    @Test
    fun `у дня с парами метка графика не вытесняет пары`() {
        assertEquals("2 пары · 09:00–12:10 · сессия", summaryText(day(lessons = lessons, state = DayState.SESSION), bells))
        assertEquals(
            "2 пары · 09:00–12:10 · сессия · не прочитан",
            summaryText(day(lessons = lessons, unread = UnreadDay.KEPT, state = DayState.SESSION), bells),
        )
    }

    @Test
    fun `метка графика — только у дня с парами`() {
        assertEquals("сессия", stateNote(DayState.SESSION, hasLessons = true))
        // У пустого дня график говорит сама строка дня — второй раз не надо.
        assertNull(stateNote(DayState.SESSION, hasLessons = false))
        assertNull(stateNote(null, hasLessons = true))
    }
}
