package ru.whensclass.data

import java.time.LocalDate

/**
 * Ссылка в таблицу колледжа — не в книгу, а к своей ячейке: в таблице на
 * семьсот колонок свою ещё надо найти. Google Sheets по `#gid=...&range=EQ139`
 * открывает лист и подводит к ячейке; лист и колонка приходят в ответе
 * сервера, строка — у каждого дня своя. Ничего из этого нет — открываем книгу
 * из `/v1/meta` как есть.
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
    // День — по две строки на пару (пара и преподаватели), сколько пар в
    // сетке звонков; без сетки — шесть, как в листе.
    val pairs = schedule.bells.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 6
    return when {
        // Диапазон вправо-вниз, а не одна ячейка: приложение Google Таблиц на
        // Android оставляет ячейку у правого верхнего края, почти за кадром.
        // Ячейка дня становится левым верхним углом выделения. ПРЕДПОЛОЖЕНИЕ:
        // что так она окажется в кадре, на телефоне ещё не проверено.
        // Выделение — ровно блок группы и день: четыре колонки (+0…+3) и все
        // строки дня.
        column != null && row != null ->
            "$base&range=$column$row:${shiftColumn(column, 3)}${row + 2 * pairs - 1}"
        column != null -> "$base&range=${column}1"
        row != null -> "$base&range=A$row"
        else -> base
    }
}

/** Буквы колонки, сдвинутые на `by`: «EQ» + 4 -> «EU», «Z» + 1 -> «AA». */
internal fun shiftColumn(letters: String, by: Int): String {
    var number = letters.uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } + by
    val out = StringBuilder()
    while (number > 0) {
        val rem = (number - 1) % 26
        out.insert(0, 'A' + rem)
        number = (number - 1) / 26
    }
    return out.toString()
}
