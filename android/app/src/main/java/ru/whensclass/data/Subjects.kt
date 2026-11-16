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
        custom[lesson.subject] ?: if (short) lesson.shortSubject ?: lesson.subject else lesson.subject
}
