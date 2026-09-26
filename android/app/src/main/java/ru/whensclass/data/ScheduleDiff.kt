package ru.whensclass.data

/**
 * Сравнение двух расписаний — чтобы рассказать, что поменялось.
 *
 * Ради этого люди и перепроверяют таблицу: сама по себе пара, стоящая на своём
 * месте, новостью не является, а вот отменённая или внезапно появившаяся —
 * очень даже.
 *
 * Пары сопоставляются внутри номера по названию, а не по одному лишь номеру:
 * со второй подгруппой на один номер приходится две пары, и по номеру они
 * затирали друг друга — обычное обновление выглядело как «убрали пару».
 */
object ScheduleDiff {

    data class Change(val day: String, val text: String)

    /**
     * Что изменилось в новых данных по сравнению со старыми.
     *
     * `sameSubject` — старое и новое про одного и того же: при смене id по
     * выбору человека сравнивать бессмысленно, всё «изменилось». А при смене,
     * которую принёс сервер (группу или преподавателя переименовали), —
     * нужно: иначе ответное «добавилась» на ложные «убрали» так и не
     * приходило.
     */
    fun compare(old: ScheduleDto?, fresh: ScheduleDto, sameSubject: Boolean? = null): List<Change> {
        if (old == null) return emptyList()
        if (!(sameSubject ?: (old.groupId == fresh.groupId))) return emptyList()

        val changes = mutableListOf<Change>()
        val oldDays = old.days.associateBy { it.date }

        for (day in fresh.days) {
            val before = oldDays[day.date] ?: continue
            val numbers = sortedSetOf<Int>().apply {
                before.lessons.forEach { add(it.number) }
                day.lessons.forEach { add(it.number) }
            }
            for (number in numbers) {
                compareNumber(
                    day = day.date,
                    number = number,
                    was = before.lessons.filter { it.number == number },
                    now = day.lessons.filter { it.number == number },
                    fresh = fresh,
                    changes = changes,
                )
            }
        }
        return changes
    }

    /**
     * «ИСП-924/1, ИСП-924/2» -> множество групп. Своя пара студента — своя и
     * без подписи, и с подписью: склейка подписывает её на общем номере.
     */
    private fun groupsOf(lesson: LessonDto, fresh: ScheduleDto): Set<String> =
        (lesson.groups ?: fresh.groupName.takeIf { !fresh.isTeacher })
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    private fun compareNumber(
        day: String,
        number: Int,
        was: List<LessonDto>,
        now: List<LessonDto>,
        fresh: ScheduleDto,
        changes: MutableList<Change>,
    ) {
        // Чья пара: у преподавателя — «у ИСП-924/2», у студента пара соседней
        // подгруппы — «(ИСП-924/2)», своя — без подписи.
        // У преподавателя тоже в скобках: « у ИСП-924/2» давало «у 3 пары у
        // ИСП-924/2 появилась ссылка».
        fun whose(lesson: LessonDto): String = when {
            lesson.groups == null || lesson.groups == fresh.groupName && !fresh.isTeacher -> ""
            else -> " (${lesson.groups})"
        }
        fun say(text: String) = changes.add(Change(day, text))
        fun groups(lesson: LessonDto) = groupsOf(lesson, fresh)
        fun overlap(a: LessonDto, b: LessonDto) = (groups(a) intersect groups(b)).isNotEmpty()

        // Что от прежнего набора ещё не нашло себе пару в новом.
        val unmatched = was.toMutableList()

        // Замена — одной строкой: «добавилась 3 пара: Физика» первой строкой
        // свёрнутого уведомления читалась как лишняя пара, а «убрали» — ниже.
        // Сервер помечает её примечанием «вместо: X».
        val replaced = mutableSetOf<LessonDto>()
        for (lesson in now) {
            val instead = lesson.replaces ?: continue
            if (lesson.isCancelled || was.any { it.subject == lesson.subject }) continue
            val index = unmatched.indexOfFirst { it.subject == instead }
            if (index < 0) continue
            unmatched.removeAt(index)
            replaced += lesson
            // Стрелкой, а не «вместо»: названия предметов не склоняются сами.
            say("замена $number пары${whose(lesson)}: $instead → ${lesson.subject}")
        }

        for (lesson in now) {
            if (lesson in replaced) continue
            // Сначала — тот же предмет у тех же групп, потом у пересекающихся
            // (у преподавателя «ИСП-924/1, ИСП-924/2» распалась на две записи),
            // потом просто тот же предмет. Раньше — только по названию, и
            // слияние записей с соседкой объявлялось «убрали» при своей паре
            // на месте.
            var index = unmatched.indexOfFirst {
                it.subject == lesson.subject && groups(it) == groups(lesson)
            }
            if (index < 0) index = unmatched.indexOfFirst {
                it.subject == lesson.subject && overlap(it, lesson)
            }
            if (index < 0) index = unmatched.indexOfFirst { it.subject == lesson.subject }
            if (index < 0) {
                // Та же пара раздвоилась на записи: новость, только если часть
                // отменили или вернули — у преподавателя «ИСП-924/1,
                // ИСП-924/2» распалась, и одну из групп сняли.
                val source = was.firstOrNull { it.subject == lesson.subject && overlap(it, lesson) }
                    ?: was.firstOrNull { it.subject == lesson.subject }
                when {
                    source != null && !source.isCancelled && lesson.isCancelled ->
                        say("отменили $number пару${whose(lesson)}: ${lesson.subject}")
                    source != null && source.isCancelled && !lesson.isCancelled ->
                        say("вернули $number пару${whose(lesson)}: ${lesson.subject}")
                    source != null -> Unit
                    // Пришла сразу отменённой под другим названием — это
                    // отмена, а не «добавилась».
                    lesson.isCancelled ->
                        say("отменили $number пару${whose(lesson)}: ${lesson.subject}")
                    else -> say("добавилась $number пара${whose(lesson)}: ${lesson.subject}")
                }
                continue
            }
            val previous = unmatched.removeAt(index)
            val tag = whose(lesson)
            when {
                !previous.isCancelled && lesson.isCancelled ->
                    say("отменили $number пару$tag: ${lesson.subject}")

                previous.isCancelled && !lesson.isCancelled ->
                    say("вернули $number пару$tag: ${lesson.subject}")

                // Онлайн — состояние пары, а не наличие ссылки. Ссылку к
                // давно онлайновой паре дописывают отдельно и позже:
                // объявлять из-за этого «стала онлайн» значит врать.
                !previous.isOnline && lesson.isOnline -> say("$number пара$tag стала онлайн")

                previous.isOnline && !lesson.isOnline -> say("$number пара$tag снова очная")

                // Замена преподавателя при том же предмете — это и есть
                // «замена», ради которой уведомления включают; раньше её не
                // замечали вовсе. Пустой новый список —
                // не замена: преподавателей часто вписывают позже.
                lesson.teachers.isNotEmpty() && previous.teachers != lesson.teachers ->
                    say("у $number пары$tag другой преподаватель: ${lesson.teachers.joinToString(", ")}")
            }
            // Кабинет — своей проверкой: смена преподавателя в том же
            // обновлении закрывала собой переезд, и человек шёл в старый
            // кабинет. Онлайн-комната — не аудитория: «переехала в 12» звало
            // бы в кабинет 12. Отменённой — не до
            // кабинета.
            if (!lesson.isCancelled && lesson.room != null && previous.room != lesson.room) {
                when {
                    lesson.isOnline -> say("у $number пары$tag онлайн-комната ${lesson.room}")
                    else -> {
                        // «в каб. 355», а не «в 355»: голое число читается как
                        // номер пары; словесное место — через двоеточие.
                        val place = ru.whensclass.widget.roomLabel(lesson.room) ?: lesson.room
                        say(if (place.startsWith("каб.")) "$number пара$tag переехала в $place"
                            else "$number пара$tag переехала: $place")
                    }
                }
            }
            // Ссылка — своей проверкой, а не веткой того же when: смена
            // преподавателя в том же обновлении закрывала собой смену ссылки,
            // а по ссылке идут на занятие.
            val url = lesson.url
            when {
                url == null || url == previous.url -> Unit
                !isKnownWebinar(url) ->
                    say("у $number пары$tag ${if (previous.url == null) "появилась" else "сменилась"} " +
                        "ссылка — чужой адрес: ${runCatching { java.net.URI(url).host }.getOrNull() ?: url}")
                previous.url == null -> say("у $number пары$tag появилась ссылка")
                else -> say("у $number пары$tag сменилась ссылка")
            }
        }

        unmatched.forEach { gone ->
            // Та же пара слилась из нескольких записей в одну — не новость.
            if (now.any { it.subject == gone.subject }) return@forEach
            say("убрали $number пару${whose(gone)}: ${gone.subject}")
        }
    }
}
