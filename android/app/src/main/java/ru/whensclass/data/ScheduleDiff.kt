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

    /** Что изменилось в новых данных по сравнению со старыми. */
    fun compare(old: ScheduleDto?, fresh: ScheduleDto): List<Change> {
        if (old == null) return emptyList()
        // Другая группа — сравнивать бессмысленно, всё «изменилось».
        if (old.groupId != fresh.groupId) return emptyList()

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
                    changes = changes,
                )
            }
        }
        return changes
    }

    private fun compareNumber(
        day: String,
        number: Int,
        was: List<LessonDto>,
        now: List<LessonDto>,
        changes: MutableList<Change>,
    ) {
        // Что от прежнего набора ещё не нашло себе пару в новом.
        val unmatched = was.toMutableList()

        for (lesson in now) {
            val index = unmatched.indexOfFirst { it.subject == lesson.subject }
            if (index < 0) {
                changes.add(Change(day, "добавилась $number пара: ${lesson.subject}"))
                continue
            }
            val previous = unmatched.removeAt(index)
            when {
                !previous.isCancelled && lesson.isCancelled ->
                    changes.add(Change(day, "отменили $number пару: ${lesson.subject}"))

                previous.isCancelled && !lesson.isCancelled ->
                    changes.add(Change(day, "вернули $number пару: ${lesson.subject}"))

                // Онлайн — состояние пары, а не наличие ссылки. Ссылку к
                // давно онлайновой паре дописывают отдельно и позже:
                // объявлять из-за этого «стала онлайн» значит врать.
                !previous.isOnline && lesson.isOnline ->
                    changes.add(Change(day, "$number пара стала онлайн"))

                previous.isOnline && !lesson.isOnline ->
                    changes.add(Change(day, "$number пара снова очная"))

                // Замена преподавателя при том же предмете — это и есть
                // «замена», ради которой уведомления включают; раньше её не
                // замечали вовсе (второй аудит, В11). Пустой новый список —
                // не замена: преподавателей часто вписывают позже.
                lesson.teachers.isNotEmpty() && previous.teachers != lesson.teachers ->
                    changes.add(
                        Change(day, "у $number пары другой преподаватель: ${lesson.teachers.joinToString(", ")}")
                    )

                previous.url == null && lesson.url != null ->
                    changes.add(Change(day, "у $number пары появилась ссылка"))

                // Подменённая ссылка — тоже новость: по ней идут на занятие
                // (второй аудит, М37).
                previous.url != null && lesson.url != null && previous.url != lesson.url ->
                    changes.add(Change(day, "у $number пары сменилась ссылка"))

                // Номер онлайн-комнаты — не аудитория: «переехала в 12» звало
                // бы в кабинет 12 (второй аудит, М22).
                previous.room != lesson.room && lesson.room != null && lesson.isOnline ->
                    changes.add(Change(day, "у $number пары онлайн-комната ${lesson.room}"))

                previous.room != lesson.room && lesson.room != null ->
                    changes.add(Change(day, "$number пара переехала в ${lesson.room}"))
            }
        }

        unmatched.forEach {
            changes.add(Change(day, "убрали $number пару: ${it.subject}"))
        }
    }
}
