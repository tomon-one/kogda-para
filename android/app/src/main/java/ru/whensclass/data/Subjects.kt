package ru.whensclass.data

/**
 * Как показывать названия предметов. Короткие имена приходят от службы (`sn`),
 * свои человек пишет в настройках — они живут на телефоне и важнее словаря.
 */
data class SubjectNames(
    val short: Boolean = false,
    val custom: Map<String, String> = emptyMap(),
) {
    fun text(lesson: LessonDto): String =
        custom[lesson.subject] ?: if (short) lesson.shortSubject?.let(::withCapital) ?: lesson.subject else lesson.subject
}

/**
 * Строка таблицы сокращений: один предмет — одна строка, даже если колледж пишет его с пометкой
 * («Экономика отрасли» и «Экономика отрасли ЗАЧЕТ»). Своё имя из строки пишется всем вариантам.
 */
data class SubjectLine(
    /** Полное имя для строки — без пометки таблицы. */
    val subject: String,
    /** Как предмет записан в таблице: по этим именам и сохраняется своё. */
    val variants: List<String>,
    /**
     * Короткое имя службы для подсказки в поле. Пометку «зачёт» в нём не срезаем: если зачётная
     * пара — единственное написание предмета, в подсказке будет «История (зачёт)».
     */
    val short: String?,
)

// Пометка после названия — свойство пары, а не другого предмета (как у службы).
private val SUBJECT_MARK = Regex("\\s+(зач[её]т(ы)?|экзамен)$", RegexOption.IGNORE_CASE)

/** Ключ строки: полное имя без пометки, строчными. «Экономика отрасли» и «… ЗАЧЕТ» — один ключ. */
private fun subjectKey(subject: String): String = subject.replace(SUBJECT_MARK, "").trim().lowercase()

/** Строки таблицы сокращений: предметы из расписания, по одной строке на предмет. */
fun subjectLines(lessons: List<LessonDto>): List<SubjectLine> {
    val byKey = LinkedHashMap<String, MutableList<LessonDto>>()
    lessons.filterNot { it.isCancelled }.forEach { lesson ->
        byKey.getOrPut(subjectKey(lesson.subject)) { mutableListOf() } += lesson
    }
    return byKey.map { (_, group) ->
        val variants = group.map { it.subject }.distinct()
        // Подсказка — от написания без пометки; если без неё предмета нет, годится и зачётное,
        // тогда пометка видна: «История (зачёт)».
        val plain = group.firstOrNull { !SUBJECT_MARK.containsMatchIn(it.subject) } ?: group.first()
        SubjectLine(
            subject = variants.first().replace(SUBJECT_MARK, "").trim(),
            variants = variants,
            short = plain.shortSubject,
        )
    }.sortedBy { it.subject.lowercase() }
}

/** Словарь приходит строчными («физра», «адм. право»): в карточке — с заглавной, как обычный текст. */
private fun withCapital(name: String): String = name.replaceFirstChar { it.uppercase() }
