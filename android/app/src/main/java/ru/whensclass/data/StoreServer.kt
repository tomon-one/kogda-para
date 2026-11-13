package ru.whensclass.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import ru.whensclass.BuildConfig

// ScheduleStore: что приложение помнит о сервере — состояние, недозвоны,
// придержанные изменения, пропажа своей группы.

suspend fun ScheduleStore.putServerState(status: String, sourceUrl: String?, since: String? = null) {
    context.dataStore.edit {
        it[KEY_SERVER_STATUS] = status
        sourceUrl?.let { url -> it[KEY_SOURCE_URL] = url }
        if (status == "ok" || since == null) it.remove(KEY_SERVER_SINCE)
        else it[KEY_SERVER_SINCE] = since
        // Отказ относится к этой сборке: после обновления его надо забыть.
        if (status == STATUS_UNSUPPORTED) it[KEY_UNSUPPORTED_BUILD] = BuildConfig.VERSION_CODE.toString()
    }
}

/**
 * Сервер ответил расписанием, хотя /v1/meta в этом заходе сорвался: «не отвечает» — уже неправда.
 * true — отметка стояла и снята.
 */
suspend fun ScheduleStore.forgetUnreachable(): Boolean {
    var was = false
    context.dataStore.edit {
        if (it[KEY_SERVER_STATUS] != STATUS_UNREACHABLE) return@edit
        was = true
        it[KEY_SERVER_STATUS] = "ok"
        it.remove(KEY_SERVER_SINCE)
    }
    return was
}

/**
 * Неудача связи с сервером при живой сети: с какого момента он молчит
 * ([ScheduleStore.Unreachable.since] — для текста «Сбой с …», держится до первого удачного ответа)
 * и с какого — подряд, без перерывов дольше [UNREACHABLE_STREAK_GAP] ([Unreachable.run] — для
 * решения «не отвечает»). Второй счёт нужен, чтобы вечерняя и утренняя неудачи через ночь без
 * проверок не сложились в сбой.
 */
suspend fun ScheduleStore.noteUnreachable(now: java.time.Instant): ScheduleStore.Unreachable {
    var first = now
    var run = now
    context.dataStore.edit { prefs ->
        fun instant(key: Preferences.Key<String>) =
            prefs[key]?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        val known = instant(KEY_UNREACHABLE_SINCE)
        val last = instant(KEY_UNREACHABLE_LAST)
        val started = instant(KEY_UNREACHABLE_RUN)
        if (known != null) first = known else prefs[KEY_UNREACHABLE_SINCE] = now.toString()
        val streak = last != null && java.time.Duration.between(last, now) < UNREACHABLE_STREAK_GAP
        if (streak && started != null) run = started else prefs[KEY_UNREACHABLE_RUN] = now.toString()
        prefs[KEY_UNREACHABLE_LAST] = now.toString()
    }
    return ScheduleStore.Unreachable(first, run)
}

suspend fun ScheduleStore.clearUnreachable() {
    context.dataStore.edit {
        it.remove(KEY_UNREACHABLE_SINCE)
        it.remove(KEY_UNREACHABLE_LAST)
        it.remove(KEY_UNREACHABLE_RUN)
    }
}

/**
 * Строки непрочитанного «Расписание изменилось»: день и текст. Копятся, потому что новое
 * уведомление с тем же id заменяет прежнее, и неосмотренная отмена пропала бы.
 */
suspend fun ScheduleStore.pendingChanges(): List<Pair<String, String>> =
    context.dataStore.data.first()[KEY_PENDING_CHANGES].orEmpty().split("\n")
        .mapNotNull { line -> line.split("\t", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }

suspend fun ScheduleStore.putPendingChanges(lines: List<Pair<String, String>>) {
    context.dataStore.edit {
        it[KEY_PENDING_CHANGES] = lines.joinToString("\n") { (day, text) -> "$day\t$text" }
    }
}

/** Тихие часы: изменения лежат в [pendingChanges] и ждут утра ([quietHours]). */
suspend fun ScheduleStore.changesHeld(): Boolean = context.dataStore.data.first()[KEY_CHANGES_HELD] != null

suspend fun ScheduleStore.setChangesHeld(held: Boolean) {
    context.dataStore.edit { if (held) it[KEY_CHANGES_HELD] = "1" else it.remove(KEY_CHANGES_HELD) }
}

/** О каком сбое (по его `since`) телефон уже сказал уведомлением. */
suspend fun ScheduleStore.staleNotifiedFor(): String? = context.dataStore.data.first()[KEY_STALE_NOTIFIED]

suspend fun ScheduleStore.setStaleNotifiedFor(since: String?) {
    context.dataStore.edit {
        if (since == null) it.remove(KEY_STALE_NOTIFIED) else it[KEY_STALE_NOTIFIED] = since
    }
}

/** Отмечает 404. True, если пора говорить человеку. */
suspend fun ScheduleStore.noteNotFound(nowMillis: Long): Boolean {
    var confirmed = false
    context.dataStore.edit {
        val first = it[KEY_GONE_SINCE]?.toLongOrNull()
        when {
            first == null -> it[KEY_GONE_SINCE] = nowMillis.toString()
            nowMillis - first >= GONE_CONFIRM_MILLIS -> {
                it[KEY_GONE] = "1"
                confirmed = true
            }
        }
    }
    return confirmed
}

suspend fun ScheduleStore.clearNotFound() {
    context.dataStore.edit {
        it.remove(KEY_GONE_SINCE)
        it.remove(KEY_GONE)
    }
}
