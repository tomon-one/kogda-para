package ru.whensclass.data

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

// ScheduleStore: что приложение помнит об обновлении — объявленная версия и итог установки.

/** Про какую сборку уже сказали — чтобы не повторяться каждый час. */
suspend fun ScheduleStore.announcedVersion(): Int =
    context.dataStore.data.first()[KEY_UPDATE_ANNOUNCED]?.toIntOrNull() ?: 0

suspend fun ScheduleStore.setAnnouncedVersion(code: Int) {
    context.dataStore.edit { it[KEY_UPDATE_ANNOUNCED] = code.toString() }
}

/** Сборка, отданная установщику из приложения: после замены сказать, что встала. */
suspend fun ScheduleStore.setInstalling(code: Int) {
    context.dataStore.edit { it[KEY_INSTALL_PENDING] = code.toString() }
}

/** Ждали эту сборку — забыть ожидание; true — её и ставили из приложения. */
suspend fun ScheduleStore.takeInstalling(code: Int): Boolean {
    var mine = false
    context.dataStore.edit {
        mine = it[KEY_INSTALL_PENDING]?.toIntOrNull() == code
        it.remove(KEY_INSTALL_PENDING)
    }
    return mine
}

/** Чем кончилась последняя установка из приложения — для «Сведений для отчёта». */
suspend fun ScheduleStore.lastInstall(): String? = context.dataStore.data.first()[KEY_LAST_INSTALL]

suspend fun ScheduleStore.setLastInstall(text: String) {
    context.dataStore.edit { it[KEY_LAST_INSTALL] = text }
}
