package ru.whensclass.ui

import java.time.LocalDateTime
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.widget.currentLessonNumber
import ru.whensclass.widget.onlineLabel
import ru.whensclass.widget.parseTime
import ru.whensclass.widget.roomLabel

/**
 * «Где преподаватель сейчас» — только по расписанию: идущая пара (номер, место, группы, до
 * скольки), окно между парами или «пар больше нет». Перемен и уходов не знаем — об этом говорит
 * подпись рядом. null — сказать нечего: день служба не прочитала.
 */
fun teacherNow(schedule: ScheduleDto, now: LocalDateTime): String? {
    val today = now.toLocalDate()
    val day = schedule.days.firstOrNull { it.date == today.toString() } ?: return "Сегодня пар нет"
    // Что в этот день, служба не знает: «свободен» было бы выдумкой.
    if (day.unreadMark != null) return null
    if (day.absent) return "Сегодня выходной"
    val bells = schedule.bellsOf(day)
    val lessons = day.lessons.filterNot { it.isCancelled }
    if (lessons.isEmpty()) return "Сегодня пар нет"

    currentLessonNumber(bells, today, now)?.let { current ->
        val at = lessons.filter { it.number == current }
        if (at.isNotEmpty()) {
            val parts = mutableListOf("Сейчас " + pairLabel(current))
            placeOf(at)?.let(parts::add)
            groupsOf(at)?.let(parts::add)
            bells[current.toString()]?.getOrNull(1)?.let { parts.add("до $it") }
            return parts.joinToString(", ")
        }
    }

    val nowSeconds = now.toLocalTime().toSecondOfDay()
    val windows = lessons.map { it.number }.distinct().mapNotNull { number ->
        val range = bells[number.toString()] ?: return@mapNotNull null
        val start = parseTime(range.getOrNull(0)) ?: return@mapNotNull null
        val end = parseTime(range.getOrNull(1))
        Window(start.toSecondOfDay(), end?.toSecondOfDay(), range.getOrNull(0)!!, range.getOrNull(1))
    }
    val next = windows.filter { it.start > nowSeconds }.minByOrNull { it.start }
        ?: return "На сегодня пар больше нет"
    val previous = windows.filter { it.end != null && it.end <= nowSeconds }.maxByOrNull { it.end!! }
    return if (previous != null) "Свободен с ${previous.endText} до ${next.startText}"
    else "Свободен до ${next.startText}"
}

private class Window(val start: Int, val end: Int?, val startText: String, val endText: String?)

/** Место пары: «онлайн», «каб. 214»; у блока в двух кабинетах — через запятую. */
private fun placeOf(lessons: List<LessonDto>): String? {
    lessons.firstOrNull { it.isOnline }?.let { return onlineLabel(it) }
    return lessons.mapNotNull { roomLabel(it.room) }.distinct().joinToString(", ").ifEmpty { null }
}

/** Группы пары: у преподавателя подпись стоит у каждой. */
private fun groupsOf(lessons: List<LessonDto>): String? =
    lessons.mapNotNull { it.groups?.trim()?.takeIf(String::isNotEmpty) }
        .distinct().joinToString(", ").ifEmpty { null }

private fun pairLabel(number: Int): String = "$number-я пара"
