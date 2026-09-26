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
    /** Лист таблицы колледжа, с которого эти дни: адрес с `#gid=`. */
    @SerialName("src_url") val sourceUrl: String? = null,
    /** Колонка группы в этом листе, буквами как в Sheets: «EQ». */
    @SerialName("col") val column: String? = null,
    /** «teacher» у расписания преподавателя: там подпись группы у каждой пары. */
    @SerialName("kind") val kind: String? = null,
) {
    val isTeacher: Boolean get() = kind == "teacher"
}

@Serializable
data class DayDto(
    @SerialName("d") val date: String,
    @SerialName("l") val lessons: List<LessonDto> = emptyList(),
    /** Строка листа, где стоит дата этого дня, — чтобы ссылка подвела к ней. */
    @SerialName("row") val row: Int? = null,
    /**
     * Дня нет в ответе, а по листу он есть — воскресенье или будень без
     * строки: его вставляет [ru.whensclass.ui.daysWithGaps]. Это «выходной», а
     * не «колледж выложил день пустым» (третий аудит, М26 прогона 1).
     */
    @kotlinx.serialization.Transient val absent: Boolean = false,
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
    /** В расписании преподавателя — колонка группы, у которой эта пара. */
    @SerialName("col") val column: String? = null,
) {
    val isCancelled: Boolean get() = cancelled != 0

    /**
     * Ссылка без признака и без аудитории — снимок, скачанный до появления
     * поля. Ссылка при аудитории — очная пара: «преподаватель на онлайн,
     * студенты в кабинете 269» (третий аудит, В1 прогона 1).
     */
    val isOnline: Boolean get() = online != 0 || (url != null && room == null)
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
    /** С какого момента сервер не обновляется — только при `status` не `ok`. */
    @SerialName("since") val since: String? = null,
    /** Почему — словами разборщика. */
    @SerialName("err") val error: String? = null,
)
