package ru.whensclass.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * После смены имён предметов виджеты и висящее напоминание должны обновиться сразу: оба берут
 * имена из хранилища, а сами по себе не перерисовываются до звонка или синхронизации.
 * Сторож по исходнику: настройки — Compose, исполнением в unit-тесте не проверяются.
 */
class SubjectNamesSaveTest {

    @Test
    fun `сохранение имён перерисовывает виджеты и переставляет напоминания`() {
        val lines = File("src/main/java/ru/whensclass/ui/App.kt").readLines()
        // Запись имени и то, что должно случиться сразу за ней: у темы перерисовка есть, у имён не было.
        listOf("setShortSubjects(", "setSubjectShort(").forEach { call ->
            val at = lines.indexOfFirst { call in it }
            assertTrue("в App.kt нет $call", at >= 0)
            val after = lines.drop(at).take(8).joinToString("\n")
            assertTrue("$call без redrawWidgets()", "redrawWidgets()" in after)
            assertTrue("$call без LessonAlarms.reschedule", "LessonAlarms.reschedule" in after)
        }
    }
}
