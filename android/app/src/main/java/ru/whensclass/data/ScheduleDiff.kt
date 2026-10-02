package ru.whensclass.data

/**
 * Сравнение двух расписаний — чтобы рассказать, что поменялось.
 *
 * Пары сопоставляются внутри номера по названию, а не по одному лишь номеру:
 * с подгруппами на один номер приходится две пары, и по номеру они затирали
 * бы друг друга.
 */
object ScheduleDiff {

    data class Change(val day: String, val text: String)

    /**
     * Что изменилось в новых данных по сравнению со старыми.
     *
     * `sameSubject` — старое и новое про одного и того же. По умолчанию — по
     * совпадению id; при смене id сервером (переименование) сравнивать нужно,
     * при смене выбора человеком — бессмысленно.
     */
    fun compare(old: ScheduleDto?, fresh: ScheduleDto, sameSubject: Boolean? = null): List<Change> {
        if (old == null) return emptyList()
        if (!(sameSubject ?: (old.groupId == fresh.groupId))) return emptyList()

        val changes = mutableListOf<Change>()
        val oldDays = old.days.associateBy { it.date }

        for (day in fresh.days) {
            val before = oldDays[day.date] ?: continue
            // Непрочитанный день без пар — не «убрали», а «не знаем»;
            // прочитанный следом — не «добавилась».
            if (UnreadDay.MISSING in listOf(before.unread, day.unread)) continue
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

    private fun norm(subject: String): String =
        subject.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun distance(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = row[0]
            row[0] = i
            for (j in 1..b.length) {
                val keep = row[j]
                row[j] = minOf(row[j] + 1, row[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = keep
            }
        }
        return row[b.length]
    }

    /**
     * Та же пара под чуть другим названием: исправили опечатку, регистр или
     * точку («Обествознание» → «Обществознание»), или у одной записи полное
     * «А / Б», у другой — только «А». Правила те же, что у same_subject в
     * server/src/whensclass/push/changes.py.
     */
    internal fun sameSubject(a: String, b: String): Boolean {
        if (a == b) return true
        val na = norm(a)
        val nb = norm(b)
        if (na == nb) return true
        val headA = norm(a.substringBefore('/'))
        if (headA.isNotEmpty() && headA == norm(b.substringBefore('/'))) return true
        return minOf(na.length, nb.length) >= 8 && distance(na, nb) <= 2
    }

    /**
     * Хост, куда поведёт ссылка, — для «чужой адрес: …». «\» браузер читает
     * как «/» и ведёт на хост до неё; кривой адрес — сам адрес.
     */
    internal fun urlHost(url: String): String =
        runCatching { java.net.URI(url.trim().replace('\\', '/')).host }.getOrNull() ?: url

    private fun compareNumber(
        day: String,
        number: Int,
        was: List<LessonDto>,
        now: List<LessonDto>,
        fresh: ScheduleDto,
        changes: MutableList<Change>,
    ) {
        // Чья пара: чужая — «(ИСП-924/2)», своя — без подписи. У
        // преподавателя тоже в скобках: «у 3 пары у ИСП-924/2» читается плохо.
        fun whose(lesson: LessonDto): String = when {
            lesson.groups == null || lesson.groups == fresh.groupName && !fresh.isTeacher -> ""
            else -> " (${lesson.groups})"
        }
        fun say(text: String) = changes.add(Change(day, text))
        fun groups(lesson: LessonDto) = groupsOf(lesson, fresh)
        fun overlap(a: LessonDto, b: LessonDto) = (groups(a) intersect groups(b)).isNotEmpty()
        // Только эти группы записи — в её порядке.
        fun listed(lesson: LessonDto, keep: Set<String>) =
            lesson.groups.orEmpty().split(",").map { it.trim() }.filter { it in keep }.joinToString(", ")
        // Группы на этом номере до и после: у преподавателя записи склеены из
        // групп, и к паре присоединяется или уходит группа — «добавилась» и
        // «убрали» только о ней, а не о всей записи.
        val beforeGroups = was.flatMap { groups(it) }.toSet()
        val afterGroups = now.flatMap { groups(it) }.toSet()

        // Что от прежнего набора ещё не нашло себе пару в новом.
        val unmatched = was.toMutableList()
        // Прежняя пара осталась в новом листе как была (не замена).
        fun keptAsIs(old: LessonDto) = now.any {
            it.replaces == null && it.subject == old.subject && it.teachers == old.teachers && it.room == old.room
        }

        // Замена — одной строкой: «добавилась» первой строкой свёрнутого
        // уведомления читалась бы как лишняя пара. Сервер помечает замену
        // примечанием «вместо: X».
        val replaced = mutableSetOf<LessonDto>()
        for (lesson in now) {
            val instead = lesson.replaces ?: continue
            if (lesson.isCancelled || was.any { it.subject == lesson.subject }) continue
            // Из двух пар с этим названием заменили ту, у которой в новом
            // листе нет точной копии: другая половинка блока осталась как была.
            var index = unmatched.indexOfFirst { it.subject == instead && !keptAsIs(it) }
            if (index < 0) index = unmatched.indexOfFirst { it.subject == instead }
            if (index < 0) index = unmatched.indexOfFirst { sameSubject(it.subject, instead) }
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
            // потом просто тот же предмет: иначе слияние записей объявлялось
            // бы «убрали» при паре на месте. У тех же групп две пары одного
            // названия (подгруппы одного языка) — сначала та, что с тем же
            // преподавателем и кабинетом: по порядку вышли бы «другой
            // преподаватель» и «переехала» вместо «убрали».
            var index = unmatched.withIndex()
                .filter { (_, it) -> it.subject == lesson.subject && groups(it) == groups(lesson) }
                .minWithOrNull(
                    compareBy({ it.value.teachers != lesson.teachers }, { it.value.room != lesson.room }, { it.index }),
                )?.index ?: -1
            if (index < 0) index = unmatched.indexOfFirst {
                it.subject == lesson.subject && overlap(it, lesson)
            }
            if (index < 0) index = unmatched.indexOfFirst { it.subject == lesson.subject }
            // Та же пара под чуть другим названием — у тех же групп (у
            // преподавателя — пересекающихся) и с тем же преподавателем.
            if (index < 0) index = unmatched.indexOfFirst {
                sameSubject(it.subject, lesson.subject) && overlap(it, lesson) && it.teachers == lesson.teachers
            }
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
            if (fresh.isTeacher) {
                val added = groups(lesson) - beforeGroups
                if (added.isNotEmpty()) say("добавилась $number пара (${listed(lesson, added)}): ${lesson.subject}")
                val left = groups(previous) - afterGroups
                if (left.isNotEmpty()) say("убрали $number пару (${listed(previous, left)}): ${previous.subject}")
            }
            when {
                !previous.isCancelled && lesson.isCancelled ->
                    say("отменили $number пару$tag: ${lesson.subject}")

                previous.isCancelled && !lesson.isCancelled ->
                    say("вернули $number пару$tag: ${lesson.subject}")

                // Онлайн — состояние пары, а не наличие ссылки: ссылку к
                // онлайновой паре часто дописывают позже.
                !previous.isOnline && lesson.isOnline -> say("$number пара$tag стала онлайн")

                previous.isOnline && !lesson.isOnline -> say("$number пара$tag снова очная")

                // Замена преподавателя при том же предмете. Пустой новый
                // список — не замена: преподавателей часто вписывают позже.
                lesson.teachers.isNotEmpty() && previous.teachers != lesson.teachers ->
                    say("у $number пары$tag другой преподаватель: ${lesson.teachers.joinToString(", ")}")
            }
            // Кабинет — своей проверкой, а не веткой when: смена преподавателя
            // в том же обновлении закрыла бы собой переезд. Онлайн-комната — не
            // аудитория. Отменённой — не до кабинета.
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
            // Ссылка — тоже своей проверкой: по ней идут на занятие.
            val url = lesson.url
            when {
                url == null || url == previous.url -> Unit
                !isKnownWebinar(url) ->
                    say("у $number пары$tag ${if (previous.url == null) "появилась" else "сменилась"} " +
                        "ссылка — чужой адрес: ${urlHost(url)}")
                previous.url == null -> say("у $number пары$tag появилась ссылка")
                else -> say("у $number пары$tag сменилась ссылка")
            }
        }

        unmatched.forEach { gone ->
            // Та же пара слилась с записью других групп — не новость. С
            // записью тех же групп (половинка блока одного языка) — убрали.
            if (now.any { it.subject == gone.subject && groups(it) != groups(gone) }) return@forEach
            if (fresh.isTeacher && gone.groups != null) {
                // Все её группы на номере остались при той же паре — запись
                // просто склеилась с другой. При другом предмете у тех же групп
                // это замена, и «убрали» прежний нужен.
                val kept = now.filter { sameSubject(it.subject, gone.subject) }.flatMap { groups(it) }.toSet()
                val left = groups(gone) - kept
                if (left.isNotEmpty()) say("убрали $number пару (${listed(gone, left)}): ${gone.subject}")
                return@forEach
            }
            say("убрали $number пару${whose(gone)}: ${gone.subject}")
        }
    }
}
