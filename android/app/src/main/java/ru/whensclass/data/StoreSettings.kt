package ru.whensclass.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ScheduleStore: выключатели уведомлений и закреплённые группы и преподаватели.

suspend fun ScheduleStore.notifyUpdatesEnabled(): Boolean = notifyUpdates.first()

suspend fun ScheduleStore.setNotifyUpdates(enabled: Boolean) {
    context.dataStore.edit { it[KEY_NOTIFY_UPDATES] = if (enabled) "1" else "0" }
}

suspend fun ScheduleStore.notifyChangesEnabled(): Boolean = notifyChanges.first()

suspend fun ScheduleStore.setNotifyChanges(enabled: Boolean) {
    context.dataStore.edit { it[KEY_NOTIFY_CHANGES] = if (enabled) "1" else "0" }
}

suspend fun ScheduleStore.notifyServerEnabled(): Boolean = notifyServer.first()

suspend fun ScheduleStore.setNotifyServer(enabled: Boolean) {
    context.dataStore.edit { it[KEY_NOTIFY_SERVER] = if (enabled) "1" else "0" }
}

suspend fun ScheduleStore.notifyGroupsGoneEnabled(): Boolean = notifyGroupsGone.first()

suspend fun ScheduleStore.setNotifyGroupsGone(enabled: Boolean) {
    context.dataStore.edit { it[KEY_NOTIFY_SUBGROUP] = if (enabled) "1" else "0" }
}

suspend fun ScheduleStore.togglePinnedGroup(id: String) {
    context.dataStore.edit { prefs ->
        val current = prefs[KEY_PINNED_GROUPS]?.split("\n")?.filter(String::isNotBlank)
            .orEmpty()
        val next = if (id in current) current - id else current + id
        prefs[KEY_PINNED_GROUPS] = next.joinToString("\n")
    }
}

/** Закреплённый id сменился — имя исправили. */
suspend fun ScheduleStore.replacePinnedTeacher(old: String, new: String) = replacePinned(KEY_PINNED_TEACHERS, old, new)

internal suspend fun ScheduleStore.replacePinned(key: Preferences.Key<String>, old: String, new: String) {
    context.dataStore.edit { prefs ->
        val current = prefs[key]?.split("\n")?.filter(String::isNotBlank).orEmpty()
        prefs[key] = current.map { if (it == old) new else it }.distinct().joinToString("\n")
    }
}

suspend fun ScheduleStore.togglePinnedTeacher(id: String) {
    context.dataStore.edit { prefs ->
        val current = prefs[KEY_PINNED_TEACHERS]?.split("\n")?.filter(String::isNotBlank)
            .orEmpty()
        val next = if (id in current) current - id else current + id
        prefs[KEY_PINNED_TEACHERS] = next.joinToString("\n")
    }
}
