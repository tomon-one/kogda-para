package ru.whensclass.notify

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.whensclass.data.LessonDto

/**
 * Тело напоминания — то, что человек читает на заблокированном экране: куда
 * идти. Ошибка здесь тише всего — уведомление придёт вовремя и будет
 * выглядеть исправным.
 */
class LessonTextTest {

    private fun alarm(lesson: LessonDto) = LessonAlarms.Alarm(
        at = LocalDateTime.of(2026, 9, 9, 8, 40),
        lesson = lesson,
        minutes = 20,
        day = "2026-09-09",
    )

    @Test
    fun `очная пара называет аудиторию первой, потом номер, вид и преподавателя`() {
        val text = LessonAlarms.text(
            alarm(
                LessonDto(
                    number = 3,
                    subject = "Физика",
                    kind = "Пр",
                    room = "272",
                    teachers = listOf("Нечаев С. А."),
                ),
            ),
        )

        assertEquals("Каб. 272. 3 пара, практика. Нечаев С. А.", text)
    }

    @Test
    fun `у онлайн-пары вместо кабинета — онлайн-комната`() {
        // При o:1 сервер кладёт в r не кабинет, а номер онлайн-комнаты
        // («онлайн 12» в таблице); «каб.» к нему не приписывается.
        val text = LessonAlarms.text(
            alarm(
                LessonDto(
                    number = 2,
                    subject = "Английский",
                    room = "12",
                    online = 1,
                    teachers = listOf("Кушинада Л. Т."),
                ),
            ),
        )

        assertEquals("Онлайн, комната 12. 2 пара. Кушинада Л. Т.", text)

        // Просто «онлайн», без номера — и текст без комнаты.
        val bare = LessonAlarms.text(
            alarm(LessonDto(number = 2, subject = "Английский", online = 1)),
        )
        assertEquals("Онлайн. 2 пара", bare)
    }

    @Test
    fun `пара без вида, аудитории и преподавателя — только номер`() {
        // Пустых кусков в тексте быть не должно: «1 пара. . » выглядит
        // как поломка, хотя в таблице просто ничего не написано.
        val text = LessonAlarms.text(alarm(LessonDto(number = 1, subject = "Химия")))

        assertEquals("1 пара", text)
    }

    @Test
    fun `первый преподаватель, а не все`() {
        // Полный список не влезает в строку уведомления, а нужен он редко.
        val text = LessonAlarms.text(
            alarm(
                LessonDto(
                    number = 4,
                    subject = "Проект",
                    teachers = listOf("Первый П. П.", "Второй В. В."),
                ),
            ),
        )

        assertEquals("4 пара. Первый П. П.", text)
    }

    @Test
    fun `своя группа не подписывается, точка после инициалов не удваивается`() {
        val own = LessonAlarms.Alarm(
            at = LocalDateTime.of(2026, 9, 9, 8, 40),
            lesson = LessonDto(number = 4, subject = "Физика", teachers = listOf("Нечаев С. А."), groups = "ИСП-924/1"),
            minutes = 20,
            day = "2026-09-09",
            ownGroup = "ИСП-924/1",
        )
        assertEquals("4 пара. Нечаев С. А.", LessonAlarms.text(own))
        val theirs = own.copy(lesson = own.lesson.copy(groups = "ИСП-924/2"))
        assertEquals("4 пара. Нечаев С. А. ИСП-924/2", LessonAlarms.text(theirs))
    }

    @Test
    fun `пара другой группы подписана группой`() {
        val text = LessonAlarms.text(
            alarm(LessonDto(number = 3, subject = "Физика", room = "272", groups = "ИСП-924/2")),
        )
        assertTrue(text, text.endsWith("ИСП-924/2"))
    }

    @Test
    fun `две пары одного номера — строка на каждую, место первым`() {
        val text = LessonAlarms.text(
            LessonAlarms.Alarm(
                at = LocalDateTime.of(2026, 10, 5, 14, 5),
                lesson = LessonDto(number = 4, subject = "Немецкий", room = "55/1", teachers = listOf("Миллер Д. Х.")),
                minutes = 15,
                day = "2026-10-05",
                also = listOf(LessonDto(number = 4, subject = "Английский", room = "467", teachers = listOf("Уэллс Д. Р."))),
            ),
        )

        assertEquals("4 пара\nКаб. 55/1 — Немецкий. Миллер Д. Х.\nКаб. 467 — Английский. Уэллс Д. Р.", text)
    }

    @Test
    fun `одна пара в двух залах — предмет один раз, номер с типом первой строкой`() {
        val alarm = LessonAlarms.Alarm(
            at = LocalDateTime.of(2026, 9, 30, 12, 15),
            lesson = LessonDto(number = 3, subject = "Физическая культура", kind = "Пр", room = "Спортзал 2", groups = "БП-1126"),
            minutes = 15,
            day = "2026-09-30",
            also = listOf(
                LessonDto(number = 3, subject = "Физическая культура.", kind = "Пр", room = "Спортзал 3", groups = "Т-925/3"),
            ),
        )

        assertEquals("Физическая культура", alarm.subject)
        assertEquals("3 пара, практика\nСпортзал 2. БП-1126\nСпортзал 3. Т-925/3", LessonAlarms.text(alarm))
    }
}
