package ru.whensclass.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDiffTest {

    private fun lesson(
        number: Int,
        subject: String,
        room: String? = null,
        url: String? = null,
        online: Boolean = false,
        cancelled: Boolean = false,
        groups: String? = null,
    ) = LessonDto(
        number = number,
        subject = subject,
        room = room,
        url = url,
        online = if (online) 1 else 0,
        cancelled = if (cancelled) 1 else 0,
        groups = groups,
    )

    private fun schedule(vararg lessons: LessonDto, kind: String? = null) = ScheduleDto(
        groupId = "isp-924-1",
        groupName = "ИСП-924/1",
        generatedAt = "2026-09-08T00:00:00Z",
        days = listOf(DayDto(date = "2026-09-08", lessons = lessons.toList())),
        kind = kind,
    )

    private fun texts(was: ScheduleDto, now: ScheduleDto) = ScheduleDiff.compare(was, now).map { it.text }

    @Test
    fun `без изменений молчим`() {
        val same = schedule(lesson(3, "Численные методы", room = "272"))
        assertEquals(emptyList<ScheduleDiff.Change>(), ScheduleDiff.compare(same, same))
    }

    @Test
    fun `отмена, переезд и онлайн названы словами`() {
        val was = schedule(
            lesson(3, "Численные методы", room = "272"),
            lesson(4, "Физика", room = "101"),
        )
        val now = schedule(
            lesson(3, "Численные методы", room = "355"),
            lesson(4, "Физика", room = "101", cancelled = true),
        )
        val texts = ScheduleDiff.compare(was, now).map { it.text }
        assertTrue(texts.contains("3 пара переехала в каб. 355"))
        assertTrue(texts.contains("отменили 4 пару: Физика"))
    }

    @Test
    fun `дописанная ссылка не выдаётся за переход в онлайн`() {
        // Живой случай 8 сентября: пары стояли онлайн со словом вместо
        // аудитории, вечером к ним дописали ссылки — и приложение
        // объявило все четыре «стали онлайн».
        val was = schedule(lesson(2, "Английский", online = true))
        val now = schedule(
            lesson(2, "Английский", online = true, url = "https://my.mts-link.ru/j/1"),
        )
        val texts = ScheduleDiff.compare(was, now).map { it.text }
        assertEquals(listOf("у 2 пары появилась ссылка"), texts)
    }

    @Test
    fun `настоящий переход в онлайн назван словами`() {
        val was = schedule(lesson(2, "Английский", room = "272"))
        val now = schedule(lesson(2, "Английский", online = true))
        assertEquals(
            listOf("2 пара стала онлайн"),
            ScheduleDiff.compare(was, now).map { it.text },
        )
    }

    @Test
    fun `две подгруппы на один номер не выглядят как пропажа пары`() {
        // Своя пара и пара соседней подгруппы стоят на одном номере. Раньше
        // они затирали друг друга, и обновление сообщало «убрали пару».
        val was = schedule(
            lesson(3, "Иностранный язык", room = "454", groups = "ИСП-924/1"),
            lesson(3, "Английский", room = "455", groups = "ИСП-924/2"),
        )
        val now = schedule(
            lesson(3, "Иностранный язык", room = "454", groups = "ИСП-924/1"),
            lesson(3, "Английский", room = "455", groups = "ИСП-924/2"),
        )
        assertEquals(emptyList<ScheduleDiff.Change>(), ScheduleDiff.compare(was, now))
    }

    @Test
    fun `появившаяся подпись группы не считается изменением`() {
        // Подпись появляется, когда в тот же номер приходит пара соседей:
        // сама пара при этом не менялась, и говорить о ней нечего. Пара
        // соседей — с её подписью: без неё «добавилась» читалось как своя.
        val was = schedule(lesson(3, "Иностранный язык", room = "454"))
        val now = schedule(
            lesson(3, "Иностранный язык", room = "454", groups = "ИСП-924/1"),
            lesson(3, "Английский", room = "455", groups = "ИСП-924/2"),
        )
        val texts = ScheduleDiff.compare(was, now).map { it.text }
        assertEquals(listOf("добавилась 3 пара (ИСП-924/2): Английский"), texts)
    }

    @Test
    fun `исчезнувшая пара названа своим именем`() {
        val was = schedule(lesson(5, "Основы алгоритмизации", room = "269"))
        val texts = ScheduleDiff.compare(was, schedule()).map { it.text }
        assertEquals(listOf("убрали 5 пару: Основы алгоритмизации"), texts)
    }

    @Test
    fun `чужое расписание не сравниваем`() {
        val other = ScheduleDto(
            groupId = "isp-924-2",
            groupName = "ИСП-924/2",
            generatedAt = "2026-09-08T00:00:00Z",
            days = listOf(DayDto(date = "2026-09-08", lessons = listOf(lesson(1, "Химия")))),
        )
        assertEquals(emptyList<ScheduleDiff.Change>(), ScheduleDiff.compare(other, schedule()))
    }

    @Test
    fun `замена преподавателя при том же предмете — новость`() {
        val was = schedule(LessonDto(number = 2, subject = "Физика", teachers = listOf("Иванов И. И.")))
        val now = schedule(LessonDto(number = 2, subject = "Физика", teachers = listOf("Петров П. П.")))
        assertEquals(
            listOf("у 2 пары другой преподаватель: Петров П. П."),
            ScheduleDiff.compare(was, now).map { it.text },
        )
    }

    @Test
    fun `пустой преподаватель — не замена, его просто ещё не вписали`() {
        val was = schedule(LessonDto(number = 2, subject = "Физика", teachers = listOf("Иванов И. И.")))
        val now = schedule(LessonDto(number = 2, subject = "Физика"))
        assertEquals(emptyList<ScheduleDiff.Change>(), ScheduleDiff.compare(was, now))
    }

    @Test
    fun `сменилась ссылка на вебинар`() {
        val was = schedule(lesson(1, "Информатика", url = "https://my.mts-link.ru/j/1"))
        val now = schedule(lesson(1, "Информатика", url = "https://my.mts-link.ru/j/2"))
        assertEquals(listOf("у 1 пары сменилась ссылка"), texts(was, now))
    }

    @Test
    fun `ссылка на чужой адрес названа адресом`() {
        // По ссылке из уведомления идут на занятие: чужой хост — не «сменилась
        // ссылка», а повод насторожиться (третий аудит, М50 прогона 1).
        val was = schedule(lesson(1, "Информатика", url = "https://my.mts-link.ru/j/1"))
        val now = schedule(lesson(1, "Информатика", url = "https://evil.example/j/1"))
        assertEquals(listOf("у 1 пары сменилась ссылка — чужой адрес: evil.example"), texts(was, now))
    }

    @Test
    fun `смена преподавателя не заслоняет смену ссылки`() {
        // Раньше ссылка была веткой того же when и при замене преподавателя
        // в том же обновлении молчала (М50 прогона 1).
        val was = schedule(
            LessonDto(number = 1, subject = "Физика", teachers = listOf("Иванов И. И."),
                online = 1, url = "https://my.mts-link.ru/j/1"),
        )
        val now = schedule(
            LessonDto(number = 1, subject = "Физика", teachers = listOf("Петров П. П."),
                online = 1, url = "https://my.mts-link.ru/j/2"),
        )
        assertEquals(
            listOf("у 1 пары другой преподаватель: Петров П. П.", "у 1 пары сменилась ссылка"),
            texts(was, now),
        )
    }

    @Test
    fun `замена предмета на том же номере — добавилась и убрали`() {
        // Главное, ради чего включают уведомления. Сопоставление по порядку
        // внутри номера делало её немой: комната та же — ни одной ветки
        // (третий аудит, В16 прогона 2).
        val was = schedule(lesson(3, "Физика", room = "272"))
        val now = schedule(lesson(3, "Химия", room = "272"))
        assertEquals(listOf("добавилась 3 пара: Химия", "убрали 3 пару: Физика"), texts(was, now))
    }

    @Test
    fun `своя пара ушла с общего номера — не переезд соседской`() {
        // Было [A/1 в 454, B/2 в 455], стало [B/2]: по порядку B сопоставилась
        // бы с A — «переехала в 455» вместо «убрали» (В16 прогона 2).
        val was = schedule(
            lesson(3, "Иностранный язык", room = "454", groups = "ИСП-924/1"),
            lesson(3, "Английский", room = "455", groups = "ИСП-924/2"),
        )
        val now = schedule(lesson(3, "Английский", room = "455", groups = "ИСП-924/2"))
        assertEquals(listOf("убрали 3 пару: Иностранный язык"), texts(was, now))
    }

    @Test
    fun `своя пара слилась с соседской — не убрали`() {
        // Подгруппы сидели в разных кабинетах — две записи с подписями; своя
        // переехала к соседям — одна общая строка без подписи. Раньше
        // соседская запись объявлялась «убрали» при своей паре на месте, а
        // переезд своей терялся (третий аудит, В22 прогона 1).
        val was = schedule(
            lesson(2, "Физика", room = "101", groups = "ИСП-924/2"),
            lesson(2, "Физика", room = "102", groups = "ИСП-924/1"),
        )
        val now = schedule(lesson(2, "Физика", room = "101"))
        assertEquals(listOf("2 пара переехала в каб. 101"), texts(was, now))
    }

    @Test
    fun `общая пара раздвоилась — не добавилась`() {
        val was = schedule(lesson(2, "Физика", room = "101"))
        val now = schedule(
            lesson(2, "Физика", room = "101", groups = "ИСП-924/1"),
            lesson(2, "Физика", room = "104", groups = "ИСП-924/2"),
        )
        assertEquals(emptyList<String>(), texts(was, now))
    }

    @Test
    fun `новая пара, пришедшая отменённой, — отмена, а не добавилась`() {
        // Физику заменили на Химию и тут же отменили (В23 прогона 1).
        val was = schedule(lesson(4, "Физика"))
        val now = schedule(lesson(4, "Химия", cancelled = true))
        assertEquals(listOf("отменили 4 пару: Химия", "убрали 4 пару: Физика"), texts(was, now))
    }

    @Test
    fun `у преподавателя поток распался и одну группу сняли — отмена у неё`() {
        // В каком порядке ни пришли записи, отмена части не теряется (В23
        // прогона 1).
        val was = schedule(lesson(2, "Физика", groups = "ИСП-924/1, ИСП-924/2"), kind = "teacher")
        for (order in listOf(true, false)) {
            val parts = listOf(
                lesson(2, "Физика", groups = "ИСП-924/1"),
                lesson(2, "Физика", groups = "ИСП-924/2", cancelled = true),
            )
            val now = schedule(*(if (order) parts else parts.reversed()).toTypedArray(), kind = "teacher")
            assertEquals(listOf("отменили 2 пару (ИСП-924/2): Физика"), texts(was, now))
        }
    }

    @Test
    fun `переименование, принятое из ответа сервера, сравнивается`() {
        // Id сменил сервер, а не человек — субъект тот же, и ответное
        // «добавилась» на прежние ложные «убрали» должно прийти (М7 прогона 2).
        val was = schedule(lesson(1, "Физика"))
        val now = schedule(lesson(1, "Физика"), lesson(2, "Химия")).copy(groupId = "isp-924-1a")
        assertEquals(emptyList<ScheduleDiff.Change>(), ScheduleDiff.compare(was, now))
        assertEquals(
            listOf("добавилась 2 пара: Химия"),
            ScheduleDiff.compare(was, now, sameSubject = true).map { it.text },
        )
    }

    @Test
    fun `номер онлайн-комнаты — не переезд в кабинет`() {
        val was = schedule(lesson(3, "Информатика", room = "5", online = true))
        val now = schedule(lesson(3, "Информатика", room = "12", online = true))
        assertEquals(listOf("у 3 пары онлайн-комната 12"), ScheduleDiff.compare(was, now).map { it.text })
    }

    @Test
    fun `замена — одной строкой, а не «добавилась» и «убрали»`() {
        // Сервер помечает замену примечанием «вместо: X». Двумя строками
        // первая в свёрнутом уведомлении читалась как лишняя пара (разбор
        // текстов 27.09).
        val was = schedule(lesson(3, "Математика", room = "272"))
        val now = schedule(
            LessonDto(number = 3, subject = "Физика", room = "272", note = "вместо: Математика"),
        )
        assertEquals(listOf("замена 3 пары: Математика → Физика"), texts(was, now))
    }

    @Test
    fun `переезд не теряется за сменой преподавателя`() {
        // Одна ветка when на пару: «другой преподаватель» закрывал собой
        // «переехала», и человек шёл в старый кабинет (разбор текстов 27.09).
        val was = schedule(LessonDto(number = 2, subject = "Физика", room = "101", teachers = listOf("Иванов И. И.")))
        val now = schedule(LessonDto(number = 2, subject = "Физика", room = "205", teachers = listOf("Петров П. П.")))
        assertEquals(
            listOf("у 2 пары другой преподаватель: Петров П. П.", "2 пара переехала в каб. 205"),
            texts(was, now),
        )
    }
}
