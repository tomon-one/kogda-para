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
        teachers: List<String> = emptyList(),
        note: String? = null,
    ) = LessonDto(
        number = number,
        subject = subject,
        teachers = teachers,
        note = note,
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
    fun `непрочитанный день без пар — не «убрали», прочитанный следом — не «добавилась»`() {
        val read = schedule(lesson(2, "Физика"))
        val unread = read.copy(days = listOf(DayDto(date = "2026-09-08", unreadMark = "missing")))
        assertEquals(emptyList<String>(), texts(read, unread))
        assertEquals(emptyList<String>(), texts(unread, read))
    }

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
        // Живой случай: пары стояли онлайн со словом вместо аудитории, вечером
        // к ним дописали ссылки — это не «стали онлайн».
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
        // Своя пара и пара соседней подгруппы стоят на одном номере и не
        // должны затирать друг друга.
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
        val was = schedule(LessonDto(number = 2, subject = "Физика", teachers = listOf("Ортега К.")))
        val now = schedule(LessonDto(number = 2, subject = "Физика", teachers = listOf("Андерсон Т.")))
        assertEquals(
            listOf("у 2 пары другой преподаватель: Андерсон Т."),
            ScheduleDiff.compare(was, now).map { it.text },
        )
    }

    @Test
    fun `пустой преподаватель — не замена, его просто ещё не вписали`() {
        val was = schedule(LessonDto(number = 2, subject = "Физика", teachers = listOf("Ортега К.")))
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
        // ссылка», а повод насторожиться.
        val was = schedule(lesson(1, "Информатика", url = "https://my.mts-link.ru/j/1"))
        val now = schedule(lesson(1, "Информатика", url = "https://evil.example/j/1"))
        assertEquals(listOf("у 1 пары сменилась ссылка — чужой адрес: evil.example"), texts(was, now))
    }

    @Test
    fun `смена преподавателя не заслоняет смену ссылки`() {
        // Смена ссылки не теряется за заменой преподавателя в том же обновлении.
        val was = schedule(
            LessonDto(number = 1, subject = "Физика", teachers = listOf("Ортега К."),
                online = 1, url = "https://my.mts-link.ru/j/1"),
        )
        val now = schedule(
            LessonDto(number = 1, subject = "Физика", teachers = listOf("Андерсон Т."),
                online = 1, url = "https://my.mts-link.ru/j/2"),
        )
        assertEquals(
            listOf("у 1 пары другой преподаватель: Андерсон Т.", "у 1 пары сменилась ссылка"),
            texts(was, now),
        )
    }

    @Test
    fun `замена предмета на том же номере — добавилась и убрали`() {
        // Главное, ради чего включают уведомления; сопоставление по порядку
        // внутри номера сделало бы её немой.
        val was = schedule(lesson(3, "Физика", room = "272"))
        val now = schedule(lesson(3, "Химия", room = "272"))
        assertEquals(listOf("добавилась 3 пара: Химия", "убрали 3 пару: Физика"), texts(was, now))
    }

    @Test
    fun `своя пара ушла с общего номера — не переезд соседской`() {
        // Было [A/1 в 454, B/2 в 455], стало [B/2]: по порядку B сопоставилась
        // бы с A — «переехала в 455» вместо «убрали».
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
        // переехала к соседям — одна общая строка без подписи. Это переезд
        // своей, а не «убрали» соседскую.
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
        // Физику заменили на Химию и тут же отменили.
        val was = schedule(lesson(4, "Физика"))
        val now = schedule(lesson(4, "Химия", cancelled = true))
        assertEquals(listOf("отменили 4 пару: Химия", "убрали 4 пару: Физика"), texts(was, now))
    }

    @Test
    fun `у преподавателя поток распался и одну группу сняли — отмена у неё`() {
        // В каком порядке ни пришли записи, отмена части не теряется.
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
        // «добавилась» на прежние ложные «убрали» должно прийти.
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
        // первая в свёрнутом уведомлении читалась бы как лишняя пара.
        val was = schedule(lesson(3, "Математика", room = "272"))
        val now = schedule(
            LessonDto(number = 3, subject = "Физика", room = "272", note = "вместо: Математика"),
        )
        assertEquals(listOf("замена 3 пары: Математика → Физика"), texts(was, now))
    }

    @Test
    fun `переезд не теряется за сменой преподавателя`() {
        // «Другой преподаватель» не закрывает собой «переехала».
        val was = schedule(LessonDto(number = 2, subject = "Физика", room = "101", teachers = listOf("Ортега К.")))
        val now = schedule(LessonDto(number = 2, subject = "Физика", room = "205", teachers = listOf("Андерсон Т.")))
        assertEquals(
            listOf("у 2 пары другой преподаватель: Андерсон Т.", "2 пара переехала в каб. 205"),
            texts(was, now),
        )
    }
    @Test
    fun `преподавателю — только о группе, что пришла или ушла`() {
        // Склеенная запись берёт самое длинное название: это не «убрали» у
        // группы, у которой ничего не менялось.
        // Правила — как в server/src/whensclass/push/changes.py.
        val joined = texts(
            schedule(lesson(2, "Физическая культура", groups = "Л-926/4"), kind = "teacher"),
            schedule(lesson(2, "Физическая культура / Адаптивная физическая культура", groups = "Л-1126, Л-926/4"), kind = "teacher"),
        )
        assertEquals(listOf("добавилась 2 пара (Л-1126): Физическая культура / Адаптивная физическая культура"), joined)
        val left = texts(
            schedule(lesson(5, "Физическая культура.", groups = "ГД-926/3, ПД-925/1, ПД-925/2, ПД-925/3"), kind = "teacher"),
            schedule(lesson(5, "Физическая культура", groups = "ГД-926/3"), kind = "teacher"),
        )
        assertEquals(listOf("убрали 5 пару (ПД-925/1, ПД-925/2, ПД-925/3): Физическая культура."), left)
    }

    @Test
    fun `преподавателю при другом предмете у тех же групп — и убрали, и добавилась`() {
        // Замена предмета у той же группы — не склейка.
        val swapped = texts(
            schedule(lesson(3, "Физика", groups = "ИСП-924/1"), kind = "teacher"),
            schedule(lesson(3, "Астрономия", groups = "ИСП-924/1"), kind = "teacher"),
        )
        assertEquals(
            listOf("добавилась 3 пара (ИСП-924/1): Астрономия", "убрали 3 пару (ИСП-924/1): Физика"),
            swapped.sorted(),
        )
    }

    @Test
    fun `исправили написание — пара та же`() {
        // «Обествознание» → «Обществознание» — не «убрали» и «добавилась».
        for ((old, new) in listOf(
            "Обествознание" to "Обществознание",
            "кураторский час" to "Кураторский час",
            "Физическая культура / Адаптивная физическая культура" to "Физическая культура",
        )) {
            assertEquals(emptyList<String>(), texts(schedule(lesson(2, old, room = "301")), schedule(lesson(2, new, room = "301"))))
        }
        assertEquals(2, texts(schedule(lesson(2, "Физика")), schedule(lesson(2, "Химия"))).size)
    }

    @Test
    fun `обратная косая в ссылке — чужой адрес с хостом до неё`() {
        // Браузер читает «\» как «/» и ведёт на evil.com.
        val now = texts(
            schedule(lesson(1, "Право", online = true)),
            schedule(lesson(1, "Право", online = true, url = "https://evil.com\\my.mts-link.ru/j/1")),
        )
        assertEquals(listOf("у 1 пары появилась ссылка — чужой адрес: evil.com"), now)
    }

    @Test
    fun `половинки одного предмета сопоставляются по преподавателю и кабинету`() {
        val left = lesson(4, "Иностранный язык", room = "55/1", teachers = listOf("Миллер Д. Х."))
        val right = lesson(4, "Иностранный язык", room = "467", teachers = listOf("Уэллс Д. Р."))
        val was = schedule(left, right)
        // Левую подгруппу сняли — «убрали», а не «другой преподаватель» и
        // «переехала»; какую из одинаковых — по преподавателю.
        assertEquals(listOf("убрали 4 пару: Иностранный язык, Миллер Д. Х."), texts(was, schedule(right)))
        // Половинки поменялись местами — у каждой всё прежнее.
        assertEquals(emptyList<String>(), texts(was, schedule(right, left)))
        // Заменили правую — замена только у неё.
        val math = lesson(4, "Математика", room = "467", teachers = listOf("Сильверхенд Д."), note = "вместо: Иностранный язык")
        assertEquals(listOf("замена 4 пары: Иностранный язык, Уэллс Д. Р. → Математика"), texts(was, schedule(left, math)))
        // Миллера заменили и перенесли — половинку называет прежний
        // преподаватель: нового подгруппа ещё не знает.
        val holm = lesson(4, "Иностранный язык", room = "310", teachers = listOf("Хольм К. К."))
        assertEquals(
            listOf(
                "у 4 пары (Иностранный язык, Миллер Д. Х.) другой преподаватель: Хольм К. К.",
                "4 пара (Иностранный язык, Миллер Д. Х.) переехала в каб. 310",
            ),
            texts(was, schedule(holm, right)),
        )
        // Отменили одну — название и преподаватель один раз.
        assertEquals(
            listOf("отменили 4 пару: Иностранный язык, Миллер Д. Х."),
            texts(was, schedule(left.copy(cancelled = 1), right)),
        )
    }

    @Test
    fun `две пары одного номера — в строке названо, какая переехала`() {
        val was = schedule(lesson(4, "Немецкий"), lesson(4, "Английский", room = "257"))
        val now = schedule(lesson(4, "Немецкий", room = "55/1"), lesson(4, "Английский", room = "257"))
        assertEquals(listOf("4 пара (Немецкий) переехала в каб. 55/1"), texts(was, now))
        // «отменили» и «вернули» называют пару и так — второй раз в скобках не нужно.
        val off = schedule(lesson(4, "Немецкий", cancelled = true), lesson(4, "Английский", room = "257"))
        assertEquals(listOf("отменили 4 пару: Немецкий"), texts(was, off))
        assertEquals(listOf("вернули 4 пару: Немецкий"), texts(off, was))
    }

    @Test
    fun `у преподавателя день без одной группы — остальные сравниваются`() {
        fun t(vararg lessons: LessonDto, unread: Boolean = false) = schedule(*lessons, kind = "teacher").let { s ->
            if (!unread) s else s.copy(days = s.days.map { it.copy(unreadMark = "missing", unreadGroups = listOf("ПХД-923/1")) })
        }
        val was = lesson(2, "Физика", room = "101", groups = "ИСП-924/1")
        val off = lesson(2, "Физика", room = "101", cancelled = true, groups = "ИСП-924/1")
        val other = lesson(3, "Право", room = "205", groups = "ПХД-923/1")
        // Её пары — ни «убрали», ни «добавилась»; отмена у другой группы —
        // и пока пометка висит, и когда день прочитали.
        assertEquals(listOf("отменили 2 пару (ИСП-924/1): Физика"), texts(t(was, unread = true), t(off, other)))
        assertEquals(listOf("отменили 2 пару (ИСП-924/1): Физика"), texts(t(was), t(off, unread = true)))
    }

    @Test
    fun `у преподавателя изменения — по группам, одинаковые склеены`() {
        fun t(vararg lessons: LessonDto) = schedule(*lessons, kind = "teacher")
        assertEquals(
            listOf("убрали 3 пару (ИСП-924/1): Физ"),
            texts(t(lesson(3, "Физ", room = "Спортзал 1", groups = "ИСП-924/1"), lesson(3, "Физ", room = "Спортзал 2", groups = "ИСП-924/2")),
                t(lesson(3, "Физ", room = "Спортзал 2", groups = "ИСП-924/2"))),
        )
        assertEquals(
            listOf("добавилась 3 пара (ИСП-924/1): Физ"),
            texts(t(lesson(3, "Физ", room = "Спортзал 2", groups = "ИСП-924/2")),
                t(lesson(3, "Физ", room = "Спортзал 1", groups = "ИСП-924/1"), lesson(3, "Физ", room = "Спортзал 2", groups = "ИСП-924/2"))),
        )
        assertEquals(
            listOf("отменили 5 пару (ПХД-924/3, ПХД-924/4): Право"),
            texts(t(lesson(5, "Право", cancelled = true, groups = "ПД-1125"), lesson(5, "Право", room = "301", groups = "ПХД-924/3, ПХД-924/4")),
                t(lesson(5, "Право", cancelled = true, groups = "ПД-1125, ПХД-924/3, ПХД-924/4"))),
        )
        // Скобки в имени группы склейке не мешают.
        assertEquals(
            listOf("1 пара (СИС(а)-926/1, СИС(а)-926/2) переехала в каб. 255"),
            texts(t(lesson(1, "Сети", room = "254", groups = "СИС(а)-926/1, СИС(а)-926/2")),
                t(lesson(1, "Сети", room = "255", groups = "СИС(а)-926/1, СИС(а)-926/2"))),
        )
    }

    @Test
    fun `замена найдена и после причины в приписке`() {
        assertEquals(
            listOf("замена 4 пары: Иностранный язык → Кураторский час"),
            texts(schedule(lesson(4, "Иностранный язык")),
                schedule(lesson(4, "Кураторский час", note = "Преподаватель заболел; вместо: Иностранный язык"))),
        )
    }
}
