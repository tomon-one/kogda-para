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
 * Время в тестах не подделать — `windowStart` смотрит на системные часы, —
 * поэтому сегодняшний день проверяется через сетку звонков: пара, кончившаяся
 * в 23:59, ещё идёт при любом запуске тестов, а кончившаяся в 00:01 прошла.
 */
class WindowStartTest {

    private val today = LocalDate.now()

    private fun lessons(count: Int) =
        (1..count).map { LessonDto(number = it, subject = "Пара $it") }

    /** Все пары кончились минуту после полуночи — значит к моменту теста прошли. */
    private fun allPassed(count: Int) =
        (1..count).associate { it.toString() to listOf("00:00", "00:01") }

    /** Все пары кончаются в конце суток — значит к моменту теста ещё идут. */
    private fun noneStarted(count: Int) =
        (1..count).associate { it.toString() to listOf("00:00", "23:59") }

    @Test
    fun `будущий день показываем с начала`() {
        val start = windowStart(lessons(6), allPassed(6), today.plusDays(1), fits = 2)

        assertEquals("завтра ничего не прошло, начинать надо с первой пары", 0, start)
    }

    @Test
    fun `прожитый день показываем с конца`() {
        // Раньше здесь стоял ноль, и вчерашний виджет показывал утро, пряча
        // вечер без единого слова.
        val start = windowStart(lessons(4), allPassed(4), today.minusDays(1), fits = 2)

        assertEquals(2, start)
    }

    @Test
    fun `сегодня, все пары кончились — окно в конце дня`() {
        val start = windowStart(lessons(4), allPassed(4), today, fits = 2)

        assertEquals(2, start)
    }

    @Test
    fun `сегодня, ни одна не кончилась — окно в начале`() {
        val start = windowStart(lessons(4), noneStarted(4), today, fits = 2)

        assertEquals(0, start)
    }

    @Test
    fun `окно не уезжает за последнюю пару`() {
        // Даже если кончились все, снизу не должно остаться пустоты: окно
        // упирается в конец списка, а не встаёт на несуществующую пару.
        val start = windowStart(lessons(3), allPassed(3), today, fits = 5)

        assertEquals(0, start)
    }

    @Test
    fun `без сетки звонков окно остаётся в начале`() {
        // Времена приходят с сервера и могут не прийти. Гадать, что уже
        // прошло, тогда не на чем.
        val start = windowStart(lessons(4), emptyMap(), today, fits = 2)

        assertEquals(0, start)
    }
}
