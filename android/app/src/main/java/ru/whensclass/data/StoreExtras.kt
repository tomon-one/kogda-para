package ru.whensclass.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

// ScheduleStore: остальные группы — список, их расписания и пропажи.

suspend fun ScheduleStore.putExtraSchedules(body: String) {
    context.dataStore.edit { it[KEY_EXTRA_SCHEDULES] = body }
}

/** Группу переименовали: сервер ответил за неё под новым id. */
suspend fun ScheduleStore.adoptExtra(oldId: String, id: String, name: String) {
    editExtras { list -> list.map { if (it.id == oldId) it.copy(id = id, name = name) else it } }
}

/**
 * Группа ответила 404 при здоровом сервере. Подтверждается тем же часом, что и пропажа своей
 * ([gone]). true — подтверждено.
 */
suspend fun ScheduleStore.noteExtraNotFound(id: String, nowMillis: Long): Boolean {
    var confirmed = false
    editExtras { list ->
        list.map {
            if (it.id != id) return@map it
            val since = it.goneSince
            if (since != null && nowMillis - since >= GONE_CONFIRM_MILLIS) confirmed = true
            if (since == null) it.copy(goneSince = nowMillis) else it
        }
    }
    return confirmed
}

/** Группы снова отвечают: отметки о пропаже — прочь, их пары вернутся. */
suspend fun ScheduleStore.clearExtrasNotFound(ids: Set<String>) {
    if (ids.isEmpty()) return
    editExtras { list ->
        list.map { if (it.id in ids && (it.gone || it.goneSince != null)) it.copy(gone = false, goneSince = null, told = false) else it }
    }
}

/**
 * Группы нет в таблице — подтверждено. Выбор не стирается: после починки её пары вернутся. true —
 * отметка новая, о ней пора сказать.
 */
/** Пропавшие группы, о которых уведомление сказало. */
suspend fun ScheduleStore.markExtrasTold(ids: Set<String>) {
    editExtras { list -> list.map { if (it.id in ids) it.copy(told = true) else it } }
}

suspend fun ScheduleStore.markExtraGone(id: String): Boolean {
    var fresh = false
    editExtras { list ->
        list.map {
            if (it.id != id || it.gone) it
            else it.copy(gone = true).also { fresh = true }
        }
    }
    return fresh
}

internal fun ScheduleStore.readExtras(prefs: Preferences): List<ExtraGroup> =
    prefs[KEY_EXTRA_GROUPS]
        ?.let { body -> runCatching { json.decodeFromString<List<ExtraGroup>>(body) }.getOrNull() }
        .orEmpty()

internal fun ScheduleStore.writeExtras(prefs: MutablePreferences, groups: List<ExtraGroup>) {
    if (groups.isEmpty()) prefs.remove(KEY_EXTRA_GROUPS)
    else prefs[KEY_EXTRA_GROUPS] = json.encodeToString(groups)
    val keep = groups.map { it.id }.toSet()
    val schedules = prefs[KEY_EXTRA_SCHEDULES]
        ?.let { runCatching { json.decodeFromString<JsonObject>(it) }.getOrNull() }
        ?: return
    val left = JsonObject(schedules.filterKeys { it in keep })
    if (left.isEmpty()) prefs.remove(KEY_EXTRA_SCHEDULES)
    else prefs[KEY_EXTRA_SCHEDULES] = json.encodeToString(left)
}

/**
 * Правка списка остальных групп одной записью: прочитанное и записанное — из одного состояния.
 * Расписания убранных — прочь сразу ([writeExtras]): иначе при повторном выборе вернулся бы прошлый
 * снимок.
 */
internal suspend fun ScheduleStore.editExtras(change: (List<ExtraGroup>) -> List<ExtraGroup>) {
    context.dataStore.edit { writeExtras(it, change(readExtras(it))) }
}
