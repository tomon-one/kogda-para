package ru.whensclass.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetLinkTest {

    private val base = "https://docs.google.com/spreadsheets/d/ID/edit#gid=656498718"

    private fun schedule(column: String? = "EQ", url: String? = base) = ScheduleDto(
        groupId = "isp-924-1",
        groupName = "ИСП-924/1",
        generatedAt = "2026-09-13T16:36:51Z",
        sourceUrl = url,
        column = column,
        days = listOf(
            DayDto("2026-09-14", row = 139),
            DayDto("2026-09-15", row = 151, lessons = listOf(
                LessonDto(number = 1, subject = "Численные методы", column = "EQ"),
            )),
        ),
    )

    @Test
    fun `ссылка ведёт к ячейке своей колонки в строке дня`() {
        assertEquals("$base&range=EQ139:ET150", sheetLink(schedule(), LocalDate.of(2026, 9, 14), null))
    }

    @Test
    fun `в воскресенье — к ближайшему следующему дню`() {
        assertEquals("$base&range=EQ139:ET150", sheetLink(schedule(), LocalDate.of(2026, 9, 13), null))
    }

    @Test
    fun `у преподавателя колонка берётся из первой пары дня`() {
        val teacher = schedule(column = null)
        assertEquals("$base&range=EQ151:ET162", sheetLink(teacher, LocalDate.of(2026, 9, 15), null))
        // Пар в этот день нет — хотя бы строка дня.
        assertEquals("$base&range=A139", sheetLink(teacher, LocalDate.of(2026, 9, 14), null))
    }

    @Test
    fun `без сведений от сервера — то, что назвал meta`() {
        assertEquals("https://x/edit", sheetLink(schedule(url = null), LocalDate.of(2026, 9, 14), "https://x/edit"))
        assertNull(sheetLink(null, LocalDate.of(2026, 9, 14), null))
    }

    @Test
    fun `день за краем ответа — колонка без строки`() {
        assertEquals("$base&range=EQ1", sheetLink(schedule(), LocalDate.of(2026, 9, 30), null))
    }

    @Test
    fun `сдвиг колонки через Z`() {
        assertEquals("EU", shiftColumn("EQ", 4))
        assertEquals("AA", shiftColumn("Z", 1))
        assertEquals("AD", shiftColumn("Z", 4))
    }
}
