package ru.whensclass.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

// ScheduleStore: выключатели уведомлений и закреплённые группы и преподаватели.

/** Значения DataStore списком: строки через перевод строки, пустые выброшены. */
internal fun String?.asLines(): List<String> = this?.split("\n")?.filter(String::isNotBlank).orEmpty()

suspend fun ScheduleStore.notifyUpdatesEnabled(): Boolean = notifyUpdates.first()

suspend fun ScheduleStore.setNotifyUpdates(enabled: Boolean) = setFlag(KEY_NOTIFY_UPDATES, enabled)

suspend fun ScheduleStore.notifyChangesEnabled(): Boolean = notifyChanges.first()

suspend fun ScheduleStore.setNotifyChanges(enabled: Boolean) = setFlag(KEY_NOTIFY_CHANGES, enabled)

suspend fun ScheduleStore.notifyServerEnabled(): Boolean = notifyServer.first()

suspend fun ScheduleStore.setNotifyServer(enabled: Boolean) = setFlag(KEY_NOTIFY_SERVER, enabled)

suspend fun ScheduleStore.notifyGroupsGoneEnabled(): Boolean = notifyGroupsGone.first()

suspend fun ScheduleStore.setNotifyGroupsGone(enabled: Boolean) = setFlag(KEY_NOTIFY_SUBGROUP, enabled)

/** Выключатель в DataStore: «1» — включён, «0» — выключен. */
private suspend fun ScheduleStore.setFlag(key: Preferences.Key<String>, enabled: Boolean) {
    context.dataStore.edit { it[key] = if (enabled) "1" else "0" }
}

suspend fun ScheduleStore.togglePinnedGroup(id: String) = togglePinned(KEY_PINNED_GROUPS, id)

suspend fun ScheduleStore.togglePinnedTeacher(id: String) = togglePinned(KEY_PINNED_TEACHERS, id)

/** Закреплённый id сменился — имя исправили. */
suspend fun ScheduleStore.replacePinnedTeacher(old: String, new: String) = replacePinned(KEY_PINNED_TEACHERS, old, new)

internal suspend fun ScheduleStore.replacePinned(key: Preferences.Key<String>, old: String, new: String) {
    context.dataStore.edit { prefs ->
        val current = prefs[key].asLines()
        prefs[key] = current.map { if (it == old) new else it }.distinct().joinToString("\n")
    }
}

/** Список id в строку через перевод строки; пустой — пустая строка. */
private suspend fun ScheduleStore.togglePinned(key: Preferences.Key<String>, id: String) {
    context.dataStore.edit { prefs ->
        val current = prefs[key].asLines()
        prefs[key] = (if (id in current) current - id else current + id).joinToString("\n")
    }
}
