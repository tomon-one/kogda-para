package ru.whensclass.data

/**
 * Сравнение двух расписаний — чтобы рассказать, что поменялось.
 *
 * Ради этого люди и перепроверяют таблицу: сама по себе пара, стоящая на своём
 * месте, новостью не является, а вот отменённая или внезапно появившаяся —
 * очень даже.
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
            val beforeByNumber = before.lessons.associateBy { it.number }
            val afterByNumber = day.lessons.associateBy { it.number }

            for ((number, lesson) in afterByNumber) {
                val was = beforeByNumber[number]
                when {
                    was == null ->
                        changes.add(Change(day.date, "добавилась $number пара: ${lesson.subject}"))

                    !was.isCancelled && lesson.isCancelled ->
                        changes.add(Change(day.date, "отменили $number пару: ${lesson.subject}"))

                    was.isCancelled && !lesson.isCancelled ->
                        changes.add(Change(day.date, "вернули $number пару: ${lesson.subject}"))

                    was.subject != lesson.subject ->
                        changes.add(
                            Change(day.date, "$number пара теперь ${lesson.subject}"),
                        )

                    was.room != lesson.room && lesson.room != null ->
                        changes.add(
                            Change(day.date, "$number пара переехала в ${lesson.room}"),
                        )

                    was.url == null && lesson.url != null ->
                        changes.add(Change(day.date, "$number пара стала онлайн"))

                    was.url != null && lesson.url == null ->
                        changes.add(Change(day.date, "$number пара снова очная"))
                }
            }

            for ((number, lesson) in beforeByNumber) {
                if (!afterByNumber.containsKey(number)) {
                    changes.add(Change(day.date, "убрали $number пару: ${lesson.subject}"))
                }
            }
        }
        return changes
    }
}
