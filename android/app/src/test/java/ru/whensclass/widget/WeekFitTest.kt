package ru.whensclass.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Раскладка недельного виджета по высоте. Числа — константы виджета: сводка
 * 21 dp, заголовок дня 21, пара 19, шапка 62.
 */
class WeekFitTest {

    private fun day(lessons: Int) = 21f + 19f * lessons

    @Test
    fun `понедельник, объявленная высота — сегодняшний день парами`() {
        // 260 dp, семь дней, сегодня 4 пары: сводки всех дней съедали место,
        // и не разворачивался ни один.
        val week = listOf(day(4), day(4), day(3), day(5), day(4), day(3), day(4))
        val fit = fitWeek(week, free = 260f - 62f, summary = 21f)
        assertEquals(1, fit.open)
        // Сегодня 97 dp, три сводки и строка «и ещё 3 дня».
        assertEquals(4, fit.shown)
    }

    @Test
    fun `места хватает — всё сводками не прячется`() {
        val week = listOf(day(2), day(3), day(2))
        assertEquals(WeekFit(open = 3, shown = 3), fitWeek(week, free = 400f, summary = 21f))
    }

    @Test
    fun `развернуть второй день, если хвост при этом не прячется`() {
        val week = listOf(day(2), day(2), day(3), day(3))
        // 59 + 59 + 21 + 21 = 160.
        assertEquals(WeekFit(open = 2, shown = 4), fitWeek(week, free = 170f, summary = 21f))
    }

    @Test
    fun `пустой сегодняшний день хвост не сворачивает`() {
        val week = listOf(38f, day(4), day(4), day(4), day(4), day(4), day(4))
        assertEquals(WeekFit(open = 0, shown = 7), fitWeek(week, 150f, 21f, firstHasLessons = false))
    }

    @Test
    fun `совсем низко — хоть один день и строка остатка`() {
        val week = List(7) { day(4) }
        val fit = fitWeek(week, free = 30f, summary = 21f)
        assertEquals(0, fit.open)
        assertEquals(1, fit.shown)
    }
}
