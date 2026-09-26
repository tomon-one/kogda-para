package ru.whensclass.widget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.whensclass.data.LessonDto

/**
 * Где стоит окно списка пар в дневном виджете.
 *
 * Пар в дне бывает шесть, а в виджет влезает две-три, и вопрос «какие именно»
 * решался неверно уже дважды. Правило простое: окно стоит там, где день
 * граничит с «сейчас». У будущего дня — в начале, у прожитого — в конце,
 * у сегодняшнего — на ближайшей паре, которая ещё не кончилась.
 *
 * Момент «сейчас» функция получает снаружи, поэтому в тестах он назначен:
 * полдень 14 сентября 2026. Пары, кончившиеся к полудню, прошли; кончающиеся
 * вечером — ещё идут.
 */
class WindowStartTest {

    private val now = LocalDate.of(2026, 9, 14).atTime(12, 0)
    private val today = now.toLocalDate()

    private fun lessons(count: Int) =
        (1..count).map { LessonDto(number = it, subject = "Пара $it") }

    /** Все пары кончились к девяти утра — к полудню прошли. */
    private fun allPassed(count: Int) =
        (1..count).associate { it.toString() to listOf("08:00", "09:00") }

    /** Все пары кончаются вечером — в полдень ещё идут. */
    private fun noneStarted(count: Int) =
        (1..count).associate { it.toString() to listOf("08:00", "20:00") }

    @Test
    fun `будущий день показываем с начала`() {
        val start = windowStart(lessons(6), allPassed(6), today.plusDays(1), fits = 2, now = now)

        assertEquals("завтра ничего не прошло, начинать надо с первой пары", 0, start)
    }

    @Test
    fun `прожитый день показываем с конца`() {
        // Раньше здесь стоял ноль, и вчерашний виджет показывал утро, пряча
        // вечер без единого слова.
        val start = windowStart(lessons(4), allPassed(4), today.minusDays(1), fits = 2, now = now)

        assertEquals(2, start)
    }

    @Test
    fun `сегодня, все пары кончились — окно в конце дня`() {
        val start = windowStart(lessons(4), allPassed(4), today, fits = 2, now = now)

        assertEquals(2, start)
    }

    @Test
    fun `сегодня, ни одна не кончилась — окно в начале`() {
        val start = windowStart(lessons(4), noneStarted(4), today, fits = 2, now = now)

        assertEquals(0, start)
    }

    @Test
    fun `окно не уезжает за последнюю пару`() {
        // Даже если кончились все, снизу не должно остаться пустоты: окно
        // упирается в конец списка, а не встаёт на несуществующую пару.
        val start = windowStart(lessons(3), allPassed(3), today, fits = 5, now = now)

        assertEquals(0, start)
    }

    @Test
    fun `без сетки звонков окно остаётся в начале`() {
        // Времена приходят с сервера и могут не прийти. Гадать, что уже
        // прошло, тогда не на чем.
        val start = windowStart(lessons(4), emptyMap(), today, fits = 2, now = now)

        assertEquals(0, start)
    }

    /** Настоящий день: две пары утром, четыре после полудня. */
    private val realDay = mapOf(
        "1" to listOf("08:00", "09:00"),
        "2" to listOf("09:10", "10:10"),
        "3" to listOf("12:30", "13:30"),
        "4" to listOf("13:40", "14:40"),
        "5" to listOf("14:50", "15:50"),
        "6" to listOf("16:00", "17:00"),
    )

    @Test
    fun `сегодня в середине дня — окно с первой некончившейся`() {
        // Ради этого случая функция и написана; раньше тестов на него не было,
        // и «return 0» или «return index» проходили все (третий аудит, М62
        // прогона 2).
        assertEquals(2, windowStart(lessons(6), realDay, today, fits = 2, now = now))
        assertEquals(2, windowStart(lessons(6), realDay, today, fits = 3, now = now))
    }

    @Test
    fun `сегодня под вечер — окно упирается в последнюю пару, без пустоты снизу`() {
        val evening = today.atTime(16, 30)
        // Кончились пять из шести, влезает три: 4-я, 5-я, 6-я, а не одна 6-я.
        assertEquals(3, windowStart(lessons(6), realDay, today, fits = 3, now = evening))
    }
}
