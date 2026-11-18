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

/** Словарь приходит строчными («физра», «адм. право»): в карточке — с заглавной, как обычный текст. */
private fun withCapital(name: String): String = name.replaceFirstChar { it.uppercase() }
