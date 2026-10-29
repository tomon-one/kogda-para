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
    fun `пара не у своей — у кого именно, длинный список — общими словами`() {
        val four = listOf("ИСП-924/1", "ИСП-924/2", "ИСП-924/3", "ИСП-924/4")
        assertEquals("Только у /4", onlyAt(listOf(3), four))
        assertEquals("Только у /2, /4", onlyAt(listOf(3, 1), four))
        assertEquals("Только у БД-1125", onlyAt(listOf(1), listOf("ДИ-926", "БД-1125")))
        assertEquals(
            "Только у других групп",
            onlyAt(listOf(1, 2, 3), listOf("ДИ-926", "БД-1125", "ИСП-924/2", "ПК-926")),
        )
    }

    @Test
    fun `выбор следующей группы называется по счёту`() {
        assertEquals("Вторая группа", ordinalGroup(2))
        assertEquals("Шестая группа", ordinalGroup(6))
    }
}
