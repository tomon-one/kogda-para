package ru.whensclass.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Чьё расписание мы просили — и то ли пришло.
 *
 * Запрос идёт по сети секунды, и за это время человек успевает сменить группу
 * или роль. Пришедший ответ тогда чужой, и записывать его нельзя: однажды он
 * молча возвращал на экран прежние пары поверх только что выбранных, а позже
 * то же самое повторилось с подгруппой — теперь с остальными группами.
 *
 * Сравнение целиком, а не по одному полю, — вся защита и держится на нём.
 * Здесь закреплено, что в сравнении участвуют все три части, и что запись
 * идёт через writeIfStillAsked. До 26.09.2026 тест проверял только equals
 * data class, а саму сверку не исполнял: её удаление или перенос за запись
 * оставляли всё зелёным. Вызов из refresh()
 * unit-тест по-прежнему не видит — зато записи вне сверки там больше нет.
 */
class SubjectTest {

    private val asked = Subject(teacher = false, id = "isp-924-1", extras = listOf("isp-924-2", "isp-924-3"))

    @Test
    fun `сменилась группа — ответ уже чужой`() {
        assertNotEquals(asked, asked.copy(id = "isp-924-2"))
    }

    @Test
    fun `сменилась роль — ответ уже чужой`() {
        // Идентификатор при этом может и совпасть: у преподавателя он свой,
        // но однажды совпадёт по случайности, и полагаться на это нельзя.
        assertNotEquals(asked, asked.copy(teacher = true))
    }

    @Test
    fun `сменилась другая группа — ответ уже чужой`() {
        // Их пары идут своими запросами, и с прежними группами это не то.
        assertNotEquals(asked, asked.copy(extras = listOf("isp-924-2", "isp-924-4")))
    }

    @Test
    fun `группу убрали или добавили — тоже смена`() {
        assertNotEquals(asked, asked.copy(extras = listOf("isp-924-2")))
        assertNotEquals(asked, asked.copy(extras = asked.extras + "isp-924-4"))
    }

    @Test
    fun `порядок групп — тоже часть выбора`() {
        // По порядку раздаются номера значков у пар.
        assertNotEquals(asked, asked.copy(extras = asked.extras.reversed()))
    }

    @Test
    fun `группу не выбрали вовсе — это не то же самое, что выбрали`() {
        assertNotEquals(asked, asked.copy(id = null))
    }

    @Test
    fun `чужой ответ не записывается, свой — записывается`() = runBlocking {
        val written = mutableListOf<String>()
        val stale = writeIfStillAsked(asked, { asked.copy(extras = emptyList()) }) {
            written += "чужое"
            "записано"
        }
        assertNull(stale)
        assertEquals(emptyList<String>(), written)

        val fresh = writeIfStillAsked(asked, { asked.copy() }) {
            written += "своё"
            "записано"
        }
        assertEquals("записано", fresh)
        assertEquals(listOf("своё"), written)
    }

    @Test
    fun `кто выбран, спрашивается в момент записи, а не заранее`() = runBlocking {
        // Выбор сменился между запросом и записью: сверка берёт нынешний.
        var now = asked
        val result = writeIfStillAsked(asked, { now }) { "записано" }
        assertEquals("записано", result)
        now = asked.copy(id = "isp-924-3")
        assertNull(writeIfStillAsked(asked, { now }) { "записано" })
    }
}
