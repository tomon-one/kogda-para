package ru.whensclass.data

import android.content.Context
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

/** Что случилось при обновлении — приложению есть что показать, виджету нет. */
/** Сколько дней держим на телефоне: неделю вперёд, чтобы листать без сети. */
const val DAYS = 7

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

    /** Перерисовать оба виджета: и большой, и тот, что на одну пару. */
    private suspend fun updateWidgets() {
        ScheduleWidget().updateAll(context)
        NextLessonWidget().updateAll(context)
    }

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
        runCatching { api.teacher(teacherId, days = DAYS) }.getOrNull()
    }

    suspend fun selectGroup(group: GroupDto) {
        store.selectGroup(group.id, group.name)
        refresh(force = true)
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
        val groupId = store.currentGroupId() ?: return@withContext RefreshResult.NoGroup
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
            val fresh = api.schedule(groupId, days = DAYS)
            val previous = schedule.first()
            store.putSchedule(json.encodeToString(fresh), fresh.generatedAt)
            updateWidgets()
            announceChanges(previous, fresh)
            LessonAlarms.reschedule(context)
            RefreshResult.Updated
        } catch (error: Exception) {
            RefreshResult.Failed(error)
        }
    }
}
