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
     * Звонки дня: свои, если колледж записал их этому дню в таблице
     * (сокращённые пары), иначе обычные. Свои заменяют обычные целиком.
     */
    fun bellsOf(day: DayDto?): Map<String, List<String>> = day?.ownBells?.takeIf { it.isNotEmpty() } ?: bells

    /** Звонки дня по дате: дня в расписании нет — обычные. */
    fun bellsOn(date: java.time.LocalDate): Map<String, List<String>> =
        bellsOf(date.toString().let { iso -> days.firstOrNull { it.date == iso } })

    /**
     * Пара пришла из соседней подгруппы — в снимке сборок до 0.1.4, где
     * склейка хранилась вместе со своими парами. Склейка подписывала своим
     * именем и свою пару, поэтому «есть подпись» ещё не значит «чужая»; у
     * преподавателя подпись стоит у каждой пары, и чужих там нет.
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
    /** Свои звонки дня: номер пары → начало и конец. Пусто — обычные ([ScheduleDto.bellsOf]). */
    @SerialName("bl") val ownBells: Map<String, List<String>> = emptyMap(),
    /**
     * Дня нет в ответе, а по листу он есть — воскресенье или будень без
     * строки: его вставляет [ru.whensclass.ui.daysWithGaps]. Это «выходной», а
     * не «колледж выложил день пустым».
     */
    @kotlinx.serialization.Transient val absent: Boolean = false,
    /** Сервер не прочитал этот день в таблице: «kept» или «missing» ([unread]). */
    @SerialName("un") val unreadMark: String? = null,
    /** У преподавателя — группы, из-за которых пометка. */
    @SerialName("ug") val unreadGroups: List<String> = emptyList(),
    /** У преподавателя при «missing» — группы, чьи пары этого дня прежние. */
    @SerialName("uk") val unreadKept: List<String> = emptyList(),
    /** Только для экрана: другие выбранные группы, чей этот день не прочитан ([combineGroups]). */
    @kotlinx.serialization.Transient val unreadOthers: List<UnreadOther> = emptyList(),
) {
    val unread: UnreadDay?
        get() = when (unreadMark) {
            "kept" -> UnreadDay.KEPT
            "missing" -> UnreadDay.MISSING
            else -> null
        }
}

enum class UnreadDay {
    /** Пары дня — прежние, из последнего прочитанного снимка. */
    KEPT,

    /** Прежних нет: день пустой, но это не «пар нет». */
    MISSING,
}

/** Непрочитанный день другой выбранной группы — с её именем. */
data class UnreadOther(val name: String, val kind: UnreadDay)

@Serializable
data class LessonDto(
    @SerialName("n") val number: Int,
    @SerialName("s") val subject: String,
    @SerialName("k") val kind: String? = null,
    @SerialName("t") val teachers: List<String> = emptyList(),
    @SerialName("r") val room: String? = null,
    @SerialName("u") val url: String? = null,
    // Пара идёт не в аудитории. Признак отдельный от ссылки: в таблице часто
    // просто написано «онлайн», а ссылку дают позже или в чате группы.
    @SerialName("o") val online: Int = 0,
    @SerialName("x") val cancelled: Int = 0,
    @SerialName("c") val note: String? = null,
    @SerialName("gr") val groups: String? = null,
    /**
     * Колонка пары в листе: у преподавателя — группы, у которой эта пара; у
     * группы — только в днях, прочитанных по повторному заголовку.
     */
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

    /**
     * Какую пару эта заменила — из примечания сервера «вместо: X»; null — не
     * замена. Сервер дописывает его через «; » к причине или к тексту у ссылки.
     */
    val replaces: String?
        get() = note?.split(";")?.map { it.trim() }?.firstOrNull { it.startsWith(INSTEAD) }
            ?.removePrefix(INSTEAD)?.trim()?.ifEmpty { null }

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

/** Кабинеты дня (docs/api.md, /v1/rooms): что занято на каждой паре, по корпусам, накладки. */
@Serializable
data class RoomsDto(
    @SerialName("date") val date: String,
    @SerialName("days") val days: List<String> = emptyList(),
    @SerialName("bells") val bells: Map<String, List<String>> = emptyMap(),
    @SerialName("buildings") val buildings: List<BuildingDto> = emptyList(),
    /** Номер пары -> кабинет -> кто в нём. */
    @SerialName("busy") val busy: Map<String, Map<String, List<RoomUseDto>>> = emptyMap(),
    @SerialName("clashes") val clashes: List<ClashDto> = emptyList(),
    /** Группы, у которых день не прочитан: их кабинеты не учтены. */
    @SerialName("unread") val unread: List<String> = emptyList(),
    /** Скольким группам колледж этот день ещё не выложил: их кабинеты не учтены. */
    @SerialName("unpublished") val unpublished: Int = 0,
)

@Serializable
data class BuildingDto(
    @SerialName("name") val name: String,
    @SerialName("rooms") val rooms: List<RoomDto> = emptyList(),
)

@Serializable
data class RoomDto(
    @SerialName("room") val room: String,
    @SerialName("floor") val floor: String = "",
)

@Serializable
data class RoomUseDto(
    @SerialName("g") val group: String,
    @SerialName("s") val subject: String,
    @SerialName("t") val teachers: List<String> = emptyList(),
)

@Serializable
data class ClashDto(
    @SerialName("n") val number: Int,
    @SerialName("room") val room: String,
    @SerialName("lessons") val lessons: List<RoomUseDto> = emptyList(),
)

/** Лист пересдач колледжа (docs/api.md, /v1/retakes): по преподавателям, без групп. */
@Serializable
data class RetakesDto(
    @SerialName("title") val title: String? = null,
    @SerialName("rows") val rows: List<RetakeDto> = emptyList(),
    @SerialName("redirects") val redirects: List<RetakeRedirectDto> = emptyList(),
) {
    /**
     * Пересдачи идут: в листе есть дни не раньше [today] — или даты, которые
     * служба не разобрала: про них не знаем, что прошли.
     */
    fun activeOn(today: java.time.LocalDate): Boolean = rows.any { row ->
        row.slots.isEmpty() || row.slots.any { it.to >= today.toString() }
    }
}

@Serializable
data class RetakeDto(
    @SerialName("teacher") val teacher: String,
    @SerialName("dates") val dates: String = "",
    @SerialName("time") val time: String = "",
    @SerialName("room") val room: String = "",
    @SerialName("slots") val slots: List<RetakeSlotDto> = emptyList(),
    @SerialName("teacher_id") val teacherId: String? = null,
    @SerialName("subjects") val subjects: List<String> = emptyList(),
)

@Serializable
data class RetakeSlotDto(
    @SerialName("from") val from: String,
    @SerialName("to") val to: String,
    @SerialName("time") val time: String = "",
)

@Serializable
data class RetakeRedirectDto(
    @SerialName("to") val to: String,
    @SerialName("teachers") val teachers: List<String> = emptyList(),
    /** Тот же человек в списке преподавателей; null — не нашёлся или это кабинет. */
    @SerialName("to_id") val toId: String? = null,
    /** id к [teachers] по порядку. */
    @SerialName("teacher_ids") val teacherIds: List<String?> = emptyList(),
)

@Serializable
data class MetaDto(
    @SerialName("gen") val generatedAt: String,
    @SerialName("status") val status: String = "ok",
    /** Адрес таблицы колледжа: куда идти, когда расписание застряло. */
    @SerialName("src_url") val sourceUrl: String? = null,
    /** С какого момента сервер не обновляется — только при `status` не `ok`. */
    @SerialName("since") val since: String? = null,
    /**
     * Состояние обновления без непрочитанных дней одной-двух групп. Прежним
     * версиям сервер отдаёт такие дни общим `stale`, этой — пометкой у дня.
     */
    @SerialName("refresh") val refresh: String? = null,
    /** Самая старая сборка, которой сервер ещё отвечает; нет — отвечает всем. */
    @SerialName("min") val minBuild: Int? = null,
    // `err` (почему — словами разборщика) приложение не читает: человеку
    // «формат таблицы изменился» ничего не даёт.
) {
    /** Обновляется ли сервер: непрочитанные дни — не общий сбой. */
    val health: String get() = refresh ?: status
}
