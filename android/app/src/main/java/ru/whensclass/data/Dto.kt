package ru.whensclass.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ответы сервера. Ключи короткие — их читает виджет при каждом обновлении.
 * Контракт описан в docs/api.md; отсутствующее поле означает «нет значения»,
 * поэтому почти всё здесь nullable.
 */
@Serializable
data class ScheduleDto(
    @SerialName("g") val groupId: String,
    @SerialName("gn") val groupName: String,
    @SerialName("gen") val generatedAt: String,
    @SerialName("src") val sheet: String? = null,
    @SerialName("cov") val coverage: List<String> = emptyList(),
    @SerialName("bells") val bells: Map<String, List<String>> = emptyMap(),
    @SerialName("days") val days: List<DayDto> = emptyList(),
)

@Serializable
data class DayDto(
    @SerialName("d") val date: String,
    @SerialName("l") val lessons: List<LessonDto> = emptyList(),
)

@Serializable
data class LessonDto(
    @SerialName("n") val number: Int,
    @SerialName("s") val subject: String,
    @SerialName("k") val kind: String? = null,
    @SerialName("t") val teachers: List<String> = emptyList(),
    @SerialName("r") val room: String? = null,
    @SerialName("u") val url: String? = null,
    // Пара идёт не в аудитории. Признак отдельный от ссылки: чаще всего
    // в таблице просто написано «онлайн», а ссылку дают позже или в чате
    // группы, и до неё пара всё равно уже онлайн.
    @SerialName("o") val online: Int = 0,
    @SerialName("x") val cancelled: Int = 0,
    @SerialName("c") val note: String? = null,
    @SerialName("gr") val groups: String? = null,
) {
    val isCancelled: Boolean get() = cancelled != 0

    /** Ссылка без признака — снимок, скачанный до появления поля. */
    val isOnline: Boolean get() = online != 0 || url != null
}

@Serializable
data class GroupsDto(
    @SerialName("gen") val generatedAt: String,
    @SerialName("groups") val groups: List<GroupDto> = emptyList(),
)

@Serializable
data class GroupDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
)

@Serializable
data class TeachersDto(
    @SerialName("gen") val generatedAt: String,
    @SerialName("teachers") val teachers: List<GroupDto> = emptyList(),
)

@Serializable
data class MetaDto(
    @SerialName("gen") val generatedAt: String,
    @SerialName("status") val status: String = "ok",
    /** Адрес таблицы колледжа: куда идти, когда расписание застряло. */
    @SerialName("src_url") val sourceUrl: String? = null,
)
