package ru.whensclass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("whensclass")

/**
 * Всё, что приложение помнит между запусками: выбранная группа и последний
 * ответ сервера.
 *
 * Виджет рисуется только отсюда и никогда из сети — поэтому он одинаково
 * работает в метро и при выключенном сервере, а обновление всего лишь меняет
 * содержимое хранилища.
 */
class ScheduleStore(private val context: Context) {

    val groupId: Flow<String?> = context.dataStore.data.map { it[KEY_GROUP_ID] }
    val groupName: Flow<String?> = context.dataStore.data.map { it[KEY_GROUP_NAME] }
    val scheduleJson: Flow<String?> = context.dataStore.data.map { it[KEY_SCHEDULE] }
    val groupsJson: Flow<String?> = context.dataStore.data.map { it[KEY_GROUPS] }
    val fetchedAt: Flow<Long> = context.dataStore.data.map { it[KEY_FETCHED_AT]?.toLongOrNull() ?: 0L }
    val generatedAt: Flow<String?> = context.dataStore.data.map { it[KEY_GENERATED_AT] }

    /** За сколько минут напоминать о паре. 0 — не напоминать вовсе. */
    val notifyBefore: Flow<Int> = context.dataStore.data.map {
        it[KEY_NOTIFY_BEFORE]?.toIntOrNull() ?: 0
    }

    suspend fun notifyBeforeMinutes(): Int = notifyBefore.first()

    suspend fun setNotifyBefore(minutes: Int) {
        context.dataStore.edit { it[KEY_NOTIFY_BEFORE] = minutes.toString() }
    }

    /** Сообщать ли об изменениях в расписании. */
    val notifyChanges: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_NOTIFY_CHANGES] != "0"
    }

    suspend fun notifyChangesEnabled(): Boolean = notifyChanges.first()

    suspend fun setNotifyChanges(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFY_CHANGES] = if (enabled) "1" else "0" }
    }

    /**
     * Закреплённые преподаватели — их показываем вверху списка.
     *
     * Списком в полторы сотни фамилий пользоваться каждый день невозможно, а
     * смотрят обычно одних и тех же: своих или собственное расписание, если
     * приложением пользуется преподаватель.
     */
    val pinnedTeachers: Flow<List<String>> = context.dataStore.data.map {
        it[KEY_PINNED_TEACHERS]?.split("\n")?.filter(String::isNotBlank).orEmpty()
    }

    suspend fun togglePinnedTeacher(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PINNED_TEACHERS]?.split("\n")?.filter(String::isNotBlank)
                .orEmpty()
            val next = if (id in current) current - id else current + id
            prefs[KEY_PINNED_TEACHERS] = next.joinToString("\n")
        }
    }

    /**
     * Кто пользуется приложением: студент или преподаватель.
     *
     * От этого зависит, чьё расписание качается и показывается везде — на
     * экране, в виджетах и в напоминаниях. Преподавателю нужны его пары, а не
     * пары какой-то группы.
     */
    val isTeacher: Flow<Boolean> = context.dataStore.data.map { it[KEY_ROLE] == "teacher" }

    suspend fun teacherMode(): Boolean = isTeacher.first()

    suspend fun setTeacherMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ROLE] = if (enabled) "teacher" else "student"
            // Расписание прежней роли показывать нельзя ни секунды.
            prefs.remove(KEY_SCHEDULE)
            prefs.remove(KEY_GENERATED_AT)
        }
    }

    /** Выбранный преподаватель — для роли преподавателя. */
    val teacherId: Flow<String?> = context.dataStore.data.map { it[KEY_TEACHER_ID] }
    val teacherName: Flow<String?> = context.dataStore.data.map { it[KEY_TEACHER_NAME] }

    suspend fun selectTeacher(id: String, name: String) {
        context.dataStore.edit {
            it[KEY_TEACHER_ID] = id
            it[KEY_TEACHER_NAME] = name
            it.remove(KEY_SCHEDULE)
            it.remove(KEY_GENERATED_AT)
        }
    }

    /** Прочитано ли приветствие при первом запуске. */
    val welcomeSeen: Flow<Boolean> = context.dataStore.data.map { it[KEY_WELCOME] == "1" }

    suspend fun markWelcomeSeen() {
        context.dataStore.edit { it[KEY_WELCOME] = "1" }
    }

    /** «system», «light» или «dark». По умолчанию — как в системе. */
    val theme: Flow<String> = context.dataStore.data.map { it[KEY_THEME] ?: "system" }

    suspend fun currentTheme(): String = theme.first()

    suspend fun setTheme(value: String) {
        context.dataStore.edit { it[KEY_THEME] = value }
    }

    suspend fun currentGroupId(): String? = groupId.first()

    /**
     * Всё, что нужно виджету, за одно чтение.
     *
     * Раньше он спрашивал хранилище по разу на каждое поле — четыре обращения
     * к диску на каждую перерисовку, и переключение дня заметно подтормаживало.
     */
    suspend fun widgetState(): WidgetState {
        val prefs = context.dataStore.data.first()
        val teacher = prefs[KEY_ROLE] == "teacher"
        return WidgetState(
            // Виджету всё равно, чьё расписание, — он рисует то, что лежит.
            groupName = if (teacher) prefs[KEY_TEACHER_NAME] else prefs[KEY_GROUP_NAME],
            scheduleJson = prefs[KEY_SCHEDULE],
            fetchedAt = prefs[KEY_FETCHED_AT]?.toLongOrNull() ?: 0L,
            theme = prefs[KEY_THEME] ?: "system",
        )
    }

    suspend fun selectGroup(id: String, name: String) {
        context.dataStore.edit {
            it[KEY_GROUP_ID] = id
            it[KEY_GROUP_NAME] = name
            // Расписание прошлой группы показывать нельзя ни секунды.
            it.remove(KEY_SCHEDULE)
            it.remove(KEY_GENERATED_AT)
        }
    }

    suspend fun putSchedule(body: String, generatedAt: String) {
        context.dataStore.edit {
            it[KEY_SCHEDULE] = body
            it[KEY_GENERATED_AT] = generatedAt
            it[KEY_FETCHED_AT] = System.currentTimeMillis().toString()
        }
    }

    /** Отмечает, что расписание проверяли, даже если оно не изменилось. */
    suspend fun touchChecked() {
        context.dataStore.edit { it[KEY_FETCHED_AT] = System.currentTimeMillis().toString() }
    }

    suspend fun putGroups(body: String) {
        context.dataStore.edit { it[KEY_GROUPS] = body }
    }

    /** Список преподавателей: полторы сотни имён, качать их каждый раз незачем. */
    val teachersJson: Flow<String?> = context.dataStore.data.map { it[KEY_TEACHERS] }

    suspend fun putTeachers(body: String) {
        context.dataStore.edit { it[KEY_TEACHERS] = body }
    }

    data class WidgetState(
        val groupName: String?,
        val scheduleJson: String?,
        val fetchedAt: Long,
        val theme: String,
    )

    private companion object {
        val KEY_GROUP_ID = stringPreferencesKey("group_id")
        val KEY_GROUP_NAME = stringPreferencesKey("group_name")
        val KEY_SCHEDULE = stringPreferencesKey("schedule_json")
        val KEY_GROUPS = stringPreferencesKey("groups_json")
        val KEY_TEACHERS = stringPreferencesKey("teachers_json")
        val KEY_FETCHED_AT = stringPreferencesKey("fetched_at")
        val KEY_GENERATED_AT = stringPreferencesKey("generated_at")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_WELCOME = stringPreferencesKey("welcome_seen")
        val KEY_NOTIFY_BEFORE = stringPreferencesKey("notify_before")
        val KEY_NOTIFY_CHANGES = stringPreferencesKey("notify_changes")
        val KEY_PINNED_TEACHERS = stringPreferencesKey("pinned_teachers")
        val KEY_ROLE = stringPreferencesKey("role")
        val KEY_TEACHER_ID = stringPreferencesKey("teacher_id")
        val KEY_TEACHER_NAME = stringPreferencesKey("teacher_name")
    }
}
