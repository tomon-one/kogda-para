package ru.whensclass.data

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.WeekWidget

/** Сколько дней держим на телефоне: неделя целиком. */
const val DAYS = 7

/**
 * С какого дня показывать расписание.
 *
 * Обычно — с понедельника текущей недели: прошедшие пары никуда не деваются,
 * иногда нужно вспомнить, что было в начале недели. В воскресенье неделя уже
 * прожита, поэтому показываем следующую.
 */
fun weekStart(today: java.time.LocalDate = java.time.LocalDate.now()): java.time.LocalDate =
    if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
        today.plusDays(1)
    } else {
        today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    }

/** Что случилось при обновлении — приложению есть что показать, виджету нет. */
sealed interface RefreshResult {
    data object Updated : RefreshResult
    data object AlreadyFresh : RefreshResult
    data object NoGroup : RefreshResult
    data class Failed(val error: Throwable) : RefreshResult
}

class ScheduleRepository(
    private val context: Context,
    private val api: ScheduleApi,
    private val store: ScheduleStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val schedule: Flow<ScheduleDto?> = store.scheduleJson.map { body ->
        body?.let { runCatching { json.decodeFromString<ScheduleDto>(it) }.getOrNull() }
    }

    val fetchedAt: Flow<Long> = store.fetchedAt
    val groupName: Flow<String?> = store.groupName

    /** Есть ли сегодняшний день в том, что лежит на телефоне. */
    private fun coversToday(saved: ScheduleDto?): Boolean {
        if (saved == null) return false
        val today = java.time.LocalDate.now().toString()
        return saved.days.any { it.date == today }
    }

    /**
     * Рассказать об изменениях, если человек этого хотел.
     *
     * Молчим, когда меняется что-то далёкое: про послезавтрашнюю замену
     * сообщать посреди пары — только раздражать.
     */
    private suspend fun announceChanges(old: ScheduleDto?, fresh: ScheduleDto) {
        if (!store.notifyChangesEnabled()) return
        val today = java.time.LocalDate.now()
        val soon = setOf(today.toString(), today.plusDays(1).toString())
        val changes = ScheduleDiff.compare(old, fresh).filter { it.day in soon }
        if (changes.isEmpty()) return

        val text = changes.joinToString("\n") { change ->
            val when_ = if (change.day == today.toString()) "Сегодня" else "Завтра"
            "$when_: ${change.text}"
        }
        Notifications.changes(context, "Расписание изменилось", text)
    }

    /** Перерисовать виджеты по тому, что уже лежит на телефоне. */
    suspend fun redrawWidgets() = updateWidgets()

    private suspend fun updateWidgets() {
        ScheduleWidget().updateAll(context)
        WeekWidget().updateAll(context)
        NextLessonWidget().updateAll(context)
        // Считаем только тогда, когда виджет и правда стоит на экране.
        // Перерисовка пустого места ответом не была: счётчик обещает, что
        // столько раз расписание показали вместо таблицы.
        if (widgetsPlaced()) store.countWidgetDraw()
    }

    private suspend fun widgetsPlaced(): Boolean = runCatching {
        val manager = GlanceAppWidgetManager(context)
        listOf(
            ScheduleWidget::class.java,
            WeekWidget::class.java,
            NextLessonWidget::class.java,
        ).any { manager.getGlanceIds(it).isNotEmpty() }
    }.getOrDefault(false)

    /**
     * Добавить пары соседней подгруппы, если она выбрана.
     *
     * Не достучались до неё — показываем своё расписание как есть: без пары
     * соседей человек всё же обойдётся, а без своих пар — нет.
     */
    private suspend fun withSecondGroup(mine: ScheduleDto): Merged {
        val second = store.currentSecondGroupId() ?: return Merged(mine, whole = true)
        val extra = runCatching {
            api.schedule(second, from = weekStart(), days = DAYS)
        }.getOrNull() ?: return Merged(mine, whole = false)
        return Merged(mergeSecondGroup(mine, extra), whole = true)
    }

    /**
     * Расписание и признак того, что склейка удалась целиком.
     *
     * Когда запрос за парами соседней подгруппы не прошёл, мы показываем своё
     * расписание без них — так лучше, чем ничего. Но сравнивать такой обрубок с
     * прежним полным снимком нельзя: разница выглядит как отмена, и человеку
     * уходило уведомление «убрали 3 пару» на пару, которая никуда не делась.
     */
    private data class Merged(val schedule: ScheduleDto, val whole: Boolean)

    suspend fun groups(): List<GroupDto> = withContext(Dispatchers.IO) {
        val cached = store.groupsJson.first()
        val fromNetwork = runCatching { api.groups() }.getOrNull()
        if (fromNetwork != null) {
            store.putGroups(json.encodeToString(fromNetwork))
            return@withContext fromNetwork.groups
        }
        // Список групп открывается и без сети: выбрать группу в метро тоже надо.
        cached?.let { runCatching { json.decodeFromString<GroupsDto>(it).groups }.getOrNull() }
            .orEmpty()
    }

    /**
     * Список преподавателей.
     *
     * Сначала отдаём сохранённый — экран открывается сразу и без сети, а
     * свежий подтягиваем следом. Раньше он ехал по сети при каждом заходе на
     * вкладку, и та заметно подтормаживала.
     */
    suspend fun teachers(): List<GroupDto> = withContext(Dispatchers.IO) {
        val cached = store.teachersJson.first()
            ?.let { runCatching { json.decodeFromString<TeachersDto>(it).teachers }.getOrNull() }
        val fresh = runCatching { api.teachers() }.getOrNull()
        if (fresh != null) {
            store.putTeachers(json.encodeToString(fresh))
            return@withContext fresh.teachers
        }
        cached.orEmpty()
    }

    /** Расписание преподавателя — берём по запросу, на телефоне не храним. */
    suspend fun teacherSchedule(teacherId: String): ScheduleDto? = withContext(Dispatchers.IO) {
        runCatching { api.teacher(teacherId, from = weekStart(), days = DAYS) }.getOrNull()
    }

    /** Студент выбрал группу — заодно это и означает, что он студент. */
    suspend fun selectGroup(group: GroupDto) {
        store.setTeacherMode(false)
        store.selectGroup(group.id, group.name)
        // Перерисовать сразу, не дожидаясь сети: смена роли стирает расписание,
        // и до конца запроса виджет иначе показывает чужое — то, что осталось от
        // прошлой роли. Если запрос не дойдёт, честнее «ещё не загружено».
        updateWidgets()
        refresh(force = true)
    }

    /** Преподаватель выбрал себя — дальше всё работает как у студента. */
    suspend fun selectSelfAsTeacher(teacher: GroupDto) {
        store.setTeacherMode(true)
        store.selectTeacher(teacher.id, teacher.name)
        updateWidgets()
        refresh(force = true)
    }

    /** Расписание группы — для просмотра чужого, без смены роли. */
    suspend fun groupSchedule(groupId: String): ScheduleDto? = withContext(Dispatchers.IO) {
        runCatching { api.schedule(groupId, from = weekStart(), days = DAYS) }.getOrNull()
    }

    /**
     * Обновляет расписание.
     *
     * Сначала спрашиваем /v1/meta: если сервер не перечитывал таблицу с
     * прошлого раза, качать расписание незачем. Ошибку наружу не выносим —
     * пусть виджет молча показывает прежнее, это лучше, чем сообщение об
     * ошибке вместо пар.
     */
    suspend fun refresh(force: Boolean = false): RefreshResult = withContext(Dispatchers.IO) {
        val teacherMode = store.teacherMode()
        val subject = if (teacherMode) store.teacherId.first() else store.currentGroupId()
        val second = store.currentSecondGroupId()
        if (subject == null) return@withContext RefreshResult.NoGroup
        try {
            // Сохранённые дни начинаются с даты загрузки, поэтому со временем
            // сегодняшнего среди них может не оказаться — и виджет пустеет.
            // В этом случае перезапрашиваем, даже если сервер говорит, что у
            // него ничего не изменилось.
            val stale = !coversToday(schedule.first())
            if (!force && !stale) {
                val meta = api.meta()
                if (meta.generatedAt == store.generatedAt.first()) {
                    // Данные те же, но проверку показать надо: иначе кажется,
                    // что кнопка обновления не работает.
                    store.touchChecked()
                    updateWidgets()
                    return@withContext RefreshResult.AlreadyFresh
                }
            }
            val fresh = if (teacherMode) {
                api.teacher(subject, from = weekStart(), days = DAYS)
            } else {
                api.schedule(subject, from = weekStart(), days = DAYS)
            }
            val merged = if (teacherMode) Merged(fresh, whole = true) else withSecondGroup(fresh)
            val full = merged.schedule

            // Пока шёл запрос, человек мог сменить группу, роль или подгруппу.
            // Тогда пришедшее расписание — чужое, и записывать его нельзя: оно
            // молча возвращало на экран прежние пары поверх только что выбранных.
            val nowTeacher = store.teacherMode()
            val nowSubject = if (nowTeacher) store.teacherId.first() else store.currentGroupId()
            // Подгруппу проверяем тоже: запрос, начатый до её смены, приносил
            // склейку с прежней соседкой и записывал её поверх новой.
            if (nowTeacher != teacherMode ||
                nowSubject != subject ||
                store.currentSecondGroupId() != second
            ) {
                return@withContext RefreshResult.AlreadyFresh
            }

            val previous = schedule.first()
            store.putSchedule(json.encodeToString(full), full.generatedAt)
            updateWidgets()
            // Об изменениях говорим только по целому снимку: обрубок без пар
            // соседней подгруппы отличается от прежнего так же, как отмена.
            if (merged.whole) announceChanges(previous, full)
            LessonAlarms.reschedule(context)
            RefreshResult.Updated
        } catch (error: Exception) {
            RefreshResult.Failed(error)
        }
    }
}
