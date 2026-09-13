package ru.whensclass.data

import java.time.LocalDate

/**
 * Ссылка в таблицу колледжа — не в книгу, а к своей ячейке.
 *
 * Таблица на семьсот колонок грузится восемь секунд, и потом в ней надо
 * искать свою колонку. Google Sheets по `#gid=...&range=EQ139` открывает
 * нужный лист и подводит к ячейке; лист и колонка приходят в ответе
 * сервера, строка — у каждого дня своя. Ничего из этого нет — открываем
 * то, что сервер назвал в `/v1/meta`: книгу как есть.
 *
 * У преподавателя своей колонки нет, пары стоят в колонках групп: берём
 * колонку первой пары дня. Пар в этот день нет — хотя бы строку дня.
 */
fun sheetLink(schedule: ScheduleDto?, day: LocalDate?, fallback: String?): String? {
    val base = schedule?.sourceUrl ?: return fallback
    val today = day?.toString()
    val dayDto = today?.let { d -> schedule.days.firstOrNull { it.date == d } }
        // Дня в ответе нет (воскресенье, каникулы) — ближайший следующий:
        // именно на него человек и смотрит.
        ?: today?.let { d -> schedule.days.firstOrNull { it.date > d } }
    val column = schedule.column ?: dayDto?.lessons?.firstNotNullOfOrNull { it.column }
    val row = dayDto?.row
    return when {
        column != null && row != null -> "$base&range=$column$row"
        column != null -> "$base&range=${column}1"
        row != null -> "$base&range=A$row"
        else -> base
    }
}
