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
    /**
     * Только для экрана: имена выбранных групп по порядку, первая — своя
     * ([combineGroups]). Номер значка у пары — место в этом списке плюс один.
     * Пусто — группа одна, значков нет.
     */
    @kotlinx.serialization.Transient val groupNames: List<String> = emptyList(),
) {
    val isTeacher: Boolean get() = kind == "teacher"

    /**
     * Пара пришла из соседней подгруппы — в снимке сборок до 0.1.4, где
     * склейка хранилась вместе со своими парами. Склейка подписывала на общем
     * номере и свою пару своим именем, поэтому «есть подпись» ещё не значит
     * «чужая»; у преподавателя подпись группы стоит у каждой пары, и чужих
     * там нет.
     */
    fun isNeighbours(lesson: LessonDto): Boolean =
        !isTeacher && lesson.groups != null && lesson.groups != groupName

    /**
     * Свои пары: без пришедших из соседней подгруппы и без своей подписи.
     * Нужно, чтобы перевести снимок сборок до 0.1.4 ([ScheduleStore.migrateGroups]).
     */
    fun ownOnly(): ScheduleDto = copy(
        days = days.map { day ->
            day.copy(
                lessons = day.lessons.filterNot(::isNeighbours)
                    .map { if (it.groups == groupName) it.copy(groups = null) else it },
            )
        },
    )
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
     * не «колледж выложил день пустым».
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
    /**
     * Только для экрана: у каких из выбранных групп есть эта пара — места в
     * [ScheduleDto.groupNames], 0 — своя. Пусто — группа одна.
     */
    @kotlinx.serialization.Transient val slots: List<Int> = emptyList(),
) {
    val isCancelled: Boolean get() = cancelled != 0

    /**
     * Ссылка без признака и без аудитории — снимок, скачанный до появления
     * поля. Ссылка при аудитории — очная пара: «преподаватель на онлайн,
     * студенты в кабинете 269».
     */
    val isOnline: Boolean get() = online != 0 || (url != null && room == null)

    /** Какую пару эта заменила — из примечания сервера «вместо: X»; null — не замена. */
    val replaces: String?
        get() = note?.takeIf { it.startsWith(INSTEAD) }?.removePrefix(INSTEAD)?.trim()?.ifEmpty { null }

    private companion object {
        const val INSTEAD = "вместо: "
    }
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
    // `err` (почему — словами разборщика) приложение не читает: человеку
    // «формат таблицы изменился» ничего не даёт, а поле без чтения путало.
)
