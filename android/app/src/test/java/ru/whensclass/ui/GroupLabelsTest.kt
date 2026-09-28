package ru.whensclass.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Подписи выбранных групп у пар: короткие — чтобы влезали в колонку времени. */
class GroupLabelsTest {

    @Test
    fun `подгруппы той же группы, что своя, — коротко, остальные — полностью`() {
        assertEquals(
            listOf("/1", "/2", "ИСП-925/1", "/10"),
            shortLabels(listOf("ИСП-924/1", "ИСП-924/2", "ИСП-925/1", "ИСП-924/10")),
        )
    }

    @Test
    fun `у своей без подгрупп — все полным названием`() {
        assertEquals(
            listOf("ДИ-926", "ИСП-924/2"),
            shortLabels(listOf("ДИ-926", "ИСП-924/2")),
        )
    }

    @Test
    fun `выбор следующей группы называется по счёту`() {
        assertEquals("Вторая группа", ordinalGroup(2))
        assertEquals("Шестая группа", ordinalGroup(6))
    }
}
