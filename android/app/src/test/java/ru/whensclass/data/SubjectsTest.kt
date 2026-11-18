package ru.whensclass.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Короткие названия предметов: что показывать и как читаются свои. */
class SubjectsTest {

    private val lesson = LessonDto(number = 1, subject = "Основы алгоритмизации и программирования", shortSubject = "алгоритмы")
    private val plain = LessonDto(number = 2, subject = "Физика")

    @Test
    fun `по умолчанию полное имя`() {
        assertEquals("Основы алгоритмизации и программирования", SubjectNames().text(lesson))
        assertEquals("Физика", SubjectNames().text(plain))
    }

    @Test
    fun `короткое — от службы, а без него полное`() {
        val names = SubjectNames(short = true)
        // Словарь отдаёт строчными; в карточке — с заглавной, как обычный текст.
        assertEquals("Алгоритмы", names.text(lesson))
        // Служба короткого не дала — показываем полное, а не пустое.
        assertEquals("Физика", names.text(plain))
    }

    @Test
    fun `своё важнее словаря и работает при выключенных коротких`() {
        val names = SubjectNames(short = true, custom = mapOf("Основы алгоритмизации и программирования" to "Алг"))
        assertEquals("Алг", names.text(lesson))
        val off = SubjectNames(short = false, custom = mapOf("Физика" to "Физ"))
        assertEquals("Физ", off.text(plain))
    }

    @Test
    fun `свои названия читаются из строк хранилища`() {
        assertEquals(
            mapOf("Основы алгоритмизации и программирования" to "алг", "Физика" to "Физ"),
            readShorts("Основы алгоритмизации и программирования\tалг\nФизика\tФиз"),
        )
        // Испорченные строки не ломают остальные: без таба или с пустым хвостом.
        assertEquals(mapOf("Физика" to "Физ"), readShorts("мусор\n\t\nФизика\tФиз\nЕщё\t"))
        assertEquals(emptyMap<String, String>(), readShorts(null))
    }
}
