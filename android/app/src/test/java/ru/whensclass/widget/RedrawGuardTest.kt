package ru.whensclass.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Перерисовка виджетов — только через [redrawWidgets] и KEY_TICK. Голый
 * updateAll при живой сессии виджета ничего не перерисовывает (починено
 * 23.09.2026), а вернуть его можно одной строкой без нового импорта: три
 * мёртвых импорта лежали наготове.
 */
class RedrawGuardTest {

    @Test
    fun `нигде в коде нет голого updateAll`() {
        val main = File("src/main/java")
        val offenders = main.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//")
                    if ("updateAll(" in code || "import androidx.glance.appwidget.updateAll" in code) {
                        "${file.name}:${index + 1}"
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
