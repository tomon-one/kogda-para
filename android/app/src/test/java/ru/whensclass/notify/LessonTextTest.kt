package ru.whensclass.notify

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.whensclass.data.LessonDto

/**
 * Тело напоминания — то, что человек читает на заблокированном экране.
 *
 * Проверялся только заголовок. А тело как раз и отвечает на вопрос, ради
 * которого напоминание существует: куда идти. Ошибка здесь тише всего:
 * уведомление придёт вовремя и будет выглядеть исправным.
 */
class LessonTextTest {

    private fun alarm(lesson: LessonDto) = LessonAlarms.Alarm(
        at = LocalDateTime.of(2026, 9, 9, 8, 40),
        lesson = lesson,
        minutes = 20,
        day = "2026-09-09",
    )

    @Test
    fun `очная пара называет номер, вид, аудиторию и преподавателя`() {
        val text = LessonAlarms.text(
            alarm(
                LessonDto(
                    number = 3,
                    subject = "Физика",
                    kind = "Пр",
                    room = "272",
                    teachers = listOf("Трухачев Д. Д."),
                ),
            ),
        )

        assertEquals("3 пара, практика. каб. 272. Трухачев Д. Д.", text)
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
                    teachers = listOf("Старостина Е. А."),
                ),
            ),
        )

        assertEquals("2 пара. Занятие онлайн, комната 12. Старостина Е. А.", text)

        // Просто «онлайн», без номера — и текст без комнаты.
        val bare = LessonAlarms.text(
            alarm(LessonDto(number = 2, subject = "Английский", online = 1)),
        )
        assertEquals("2 пара. Занятие онлайн", bare)
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
}
