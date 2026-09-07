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

                previous.url == null && lesson.url != null ->
                    changes.add(Change(day, "$number пара стала онлайн"))

                previous.url != null && lesson.url == null ->
                    changes.add(Change(day, "$number пара снова очная"))

                previous.room != lesson.room && lesson.room != null ->
                    changes.add(Change(day, "$number пара переехала в ${lesson.room}"))
            }
        }

        unmatched.forEach {
            changes.add(Change(day, "убрали $number пару: ${it.subject}"))
        }
    }
}
