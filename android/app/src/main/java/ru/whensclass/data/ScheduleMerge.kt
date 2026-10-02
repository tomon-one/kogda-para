package ru.whensclass.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Сколько групп можно выбрать вместе со своей. Хватает на любую группу
 * колледжа целиком: больше всего подгрупп, шесть, у одной.
 */
const val MAX_GROUPS = 6

/**
 * Ещё одна выбранная группа (любая): её пары видны на экране рядом со своими.
 * В таблице колледжа подгруппы стоят разными колонками, и общая пара бывает
 * записана не во всех. Виджеты, напоминания и уведомления об изменениях —
 * только о своей группе.
 */
@Serializable
data class ExtraGroup(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    /**
     * Группы нет в таблице — подтверждено часом 404. Выбор не стирается:
     * вернётся группа — вернутся и её пары.
     */
    @SerialName("gone") val gone: Boolean = false,
    /** Когда сервер впервые ответил на неё 404, мс; null — отвечает. */
    @SerialName("gone_since") val goneSince: Long? = null,
)

/**
 * Своё расписание вместе с парами остальных выбранных групп — для экрана.
 *
 * Одинаковая пара у нескольких групп остаётся одной строкой с отметками всех
 * этих групп. Пара, которой у своей группы нет, — отдельной строкой без своей
 * отметки. В один номер своя пара стоит выше чужих, чужие — в порядке выбора.
 *
 * [extras] — по порядку выбора; null — расписания этой группы на телефоне
 * ещё нет, её место в значках остаётся. Дни — только внутри своего окна: за
 * его краем своих данных нет.
 */
fun combineGroups(main: ScheduleDto, extras: List<Pair<String, ScheduleDto?>>): ScheduleDto {
    if (extras.isEmpty() || main.isTeacher) return main
    val names = listOf(main.groupName) + extras.map { it.first }
    val own = main.days.mapNotNull { day -> day.date.takeIf { it.isNotEmpty() } }
    val first = own.minOrNull()
    val last = own.maxOrNull()
    val theirs = extras.map { (_, schedule) ->
        schedule?.days.orEmpty().associateBy({ it.date }, { it.lessons })
    }
    // Дни, которых у своей группы нет, а у выбранной есть, — внутри окна.
    val extraDates = theirs.flatMap { it.keys }
        .filter { first != null && last != null && it >= first && it <= last }
        .filterNot { date -> main.days.any { it.date == date } }
        .toSortedSet()
    val days = (main.days + extraDates.map { DayDto(date = it) }).sortedBy { it.date }
    return main.copy(
        groupNames = names,
        // Непрочитанный день любой из групп — пометка на дне: часть пар в нём прежняя.
        unread = (main.unread + extras.flatMap { it.second?.unread.orEmpty() }).distinct().sorted(),
        days = days.map { day ->
            val rows = day.lessons.map { it.copy(slots = listOf(0)) }.toMutableList()
            theirs.forEachIndexed { index, byDate ->
                val slot = index + 1
                byDate[day.date].orEmpty().forEach { lesson ->
                    val at = rows.indexOfFirst { same(it, lesson) && slot !in it.slots }
                    if (at >= 0) rows[at] = rows[at].copy(slots = rows[at].slots + slot)
                    else rows += lesson.copy(groups = null, slots = listOf(slot))
                }
            }
            // sortedBy устойчива: в один номер своя пара выше чужих.
            day.copy(lessons = rows.sortedBy { it.number })
        },
    )
}

/**
 * Остальные подгруппы своей группы: для «ИСП-924/1» — «ИСП-924/2» и дальше
 * по номеру. Пусто, если у группы нет подгрупп.
 */
fun subgroupsOf(name: String, groups: List<GroupDto>): List<GroupDto> {
    val base = SUBGROUP.matchEntire(name.trim())?.groupValues?.get(1) ?: return emptyList()
    return groups
        .mapNotNull { group ->
            val match = SUBGROUP.matchEntire(group.name.trim()) ?: return@mapNotNull null
            if (match.groupValues[1] != base || group.name.trim() == name.trim()) return@mapNotNull null
            match.groupValues[2].toIntOrNull()?.let { it to group }
        }
        .sortedBy { it.first }
        .map { it.second }
}

private val SUBGROUP = Regex("""(.+)/(\d+)""")

/**
 * Одна ли это пара у двух групп.
 *
 * Аудиторию сравниваем нарочно: у общей лекции она одна, а если подгруппы
 * сидят в разных кабинетах — это разные пары, и показать надо обе.
 */
internal fun same(a: LessonDto, b: LessonDto): Boolean =
    a.number == b.number &&
        a.subject.trim() == b.subject.trim() &&
        a.room?.trim() == b.room?.trim() &&
        a.url == b.url &&
        a.isOnline == b.isOnline &&
        a.isCancelled == b.isCancelled
