package ru.whensclass.data

/**
 * Склейка расписания с расписанием второй подгруппы.
 *
 * Зачем: в таблице колледжа подгруппы стоят разными колонками, и общая пара
 * нередко записана только в одной из них — в своей колонке её просто нет.
 * Человек, который смотрит только свою, такую пару пропускает.
 *
 * Что делаем: пары из второй колонки, которых нет в своей, добавляем в день и
 * подписываем группой — чтобы было видно, откуда они взялись. Если в один
 * номер пары у подгрупп разное, подписываем обе: иначе непонятно, какая ваша.
 * Совпавшие пары остаются одной строкой и без подписи — это и есть общая пара.
 */
fun mergeSecondGroup(primary: ScheduleDto, secondary: ScheduleDto): ScheduleDto {
    val extraByDate = secondary.days.associateBy({ it.date }, { it.lessons })
    val days = primary.days.map { day ->
        val extra = extraByDate[day.date].orEmpty()
        day.copy(
            lessons = mergeLessons(
                mine = day.lessons,
                other = extra,
                myName = primary.groupName,
                otherName = secondary.groupName,
            ),
        )
    }
    return primary.copy(days = days)
}

private fun mergeLessons(
    mine: List<LessonDto>,
    other: List<LessonDto>,
    myName: String,
    otherName: String,
): List<LessonDto> {
    val extra = other.filterNot { theirs -> mine.any { same(it, theirs) } }
    if (extra.isEmpty()) return mine

    val contested = extra.map { it.number }.toSet()
    val labelledMine = mine.map {
        if (it.number in contested && it.groups == null) it.copy(groups = myName) else it
    }
    val labelledExtra = extra.map { it.copy(groups = otherName) }
    // sortedBy устойчива: при равном номере своя пара остаётся выше чужой.
    return (labelledMine + labelledExtra).sortedBy { it.number }
}

/**
 * Одна ли это пара в двух колонках.
 *
 * Аудиторию сравниваем нарочно: у общей лекции она одна, а если подгруппы
 * сидят в разных кабинетах — это разные пары, и показать надо обе.
 */
private fun same(a: LessonDto, b: LessonDto): Boolean =
    a.number == b.number &&
        a.subject.trim() == b.subject.trim() &&
        a.room?.trim() == b.room?.trim() &&
        a.url == b.url &&
        a.isCancelled == b.isCancelled
