package ru.whensclass.data

import androidx.datastore.preferences.core.stringPreferencesKey

// Ключи настроек ScheduleStore и его постоянные: одно место на все файлы хранилища.

internal val KEY_GROUP_ID = stringPreferencesKey("group_id")
internal val KEY_SERVER_SINCE = stringPreferencesKey("server_since")
internal val KEY_STALE_NOTIFIED = stringPreferencesKey("stale_notified")
internal val KEY_LISTS_AT = stringPreferencesKey("lists_at")
internal val KEY_GITHUB_AT = stringPreferencesKey("github_at")
internal val KEY_PENDING_CHANGES = stringPreferencesKey("pending_changes")
internal val KEY_CHANGES_HELD = stringPreferencesKey("changes_held")
internal val KEY_UNREACHABLE_SINCE = stringPreferencesKey("unreachable_since")
internal val KEY_GONE_SINCE = stringPreferencesKey("gone_since")
internal val KEY_GONE = stringPreferencesKey("gone")
internal const val GONE_CONFIRM_MILLIS = 60L * 60 * 1000
/** Сборки до 0.1.4: снимок без пар соседки. Читается только при переводе. */
internal val KEY_PARTIAL = stringPreferencesKey("schedule_partial")
internal val KEY_EXTRA_GROUPS = stringPreferencesKey("extra_groups")
internal val KEY_EXTRA_SCHEDULES = stringPreferencesKey("extra_schedules")
internal val KEY_GROUP_LABELS = stringPreferencesKey("group_labels")
internal val KEY_CHANNEL_SEEN = stringPreferencesKey("channel_seen")
internal val KEY_WINDOW_FROM = stringPreferencesKey("window_from")
internal val KEY_GROUP2_GONE_SINCE = stringPreferencesKey("group2_gone_since")
internal val KEY_GROUP2_GONE = stringPreferencesKey("group2_gone")
internal val KEY_UNREACHABLE_LAST = stringPreferencesKey("unreachable_last")
internal val KEY_UNREACHABLE_RUN = stringPreferencesKey("unreachable_run")
/**
 * Неудачи, разделённые таким перерывом, — не одна беда. Три часа, а не
 * час: фоновый заход бывает раз в час с хвостиком, и при часе цепочка
 * рвалась бы каждый раз.
 */
internal val UNREACHABLE_STREAK_GAP: java.time.Duration = java.time.Duration.ofHours(3)
internal val KEY_GROUP_NAME = stringPreferencesKey("group_name")
internal val KEY_SCHEDULE = stringPreferencesKey("schedule_json")
internal val KEY_GROUPS = stringPreferencesKey("groups_json")
internal val KEY_TEACHERS = stringPreferencesKey("teachers_json")
internal val KEY_FETCHED_AT = stringPreferencesKey("fetched_at")
internal val KEY_GENERATED_AT = stringPreferencesKey("generated_at")
internal val KEY_FORMAT = stringPreferencesKey("format")
internal val KEY_UNSUPPORTED_BUILD = stringPreferencesKey("unsupported_build")
internal const val FORMAT = "2"
internal val KEY_THEME = stringPreferencesKey("theme")
internal val KEY_GLOW = stringPreferencesKey("glow")
internal val KEY_ROW_STAYS = stringPreferencesKey("row_stays")
internal val KEY_WIDGET_DAY = stringPreferencesKey("widget_day")
internal val KEY_WELCOME = stringPreferencesKey("welcome_seen")
internal val KEY_GROUP2_ID = stringPreferencesKey("group2_id")
internal val KEY_GROUP2_NAME = stringPreferencesKey("group2_name")
internal val KEY_NOTIFY_UPDATES = stringPreferencesKey("notify_updates")
internal val KEY_UPDATE_ANNOUNCED = stringPreferencesKey("update_announced")
internal val KEY_INSTALL_PENDING = stringPreferencesKey("install_pending")
internal val KEY_LAST_INSTALL = stringPreferencesKey("last_install")
internal val KEY_NOTIFY_BEFORE = stringPreferencesKey("notify_before")
internal val KEY_NOTIFY_ON = stringPreferencesKey("notify_on")
internal val KEY_NOTIFY_CHANGES = stringPreferencesKey("notify_changes")
internal val KEY_NOTIFY_SERVER = stringPreferencesKey("notify_server")
internal val KEY_NOTIFY_SUBGROUP = stringPreferencesKey("notify_subgroup")
internal val KEY_PINNED_TEACHERS = stringPreferencesKey("pinned_teachers")
internal val KEY_PINNED_GROUPS = stringPreferencesKey("pinned_groups")
internal val KEY_ROLE = stringPreferencesKey("role")
internal val KEY_TEACHER_ID = stringPreferencesKey("teacher_id")
internal val KEY_TEACHER_NAME = stringPreferencesKey("teacher_name")
internal val KEY_TALLY_OPENS = stringPreferencesKey("tally_opens")
internal val KEY_TALLY_SINCE = stringPreferencesKey("tally_since")
internal val KEY_SERVER_STATUS = stringPreferencesKey("server_status")
internal val KEY_SOURCE_URL = stringPreferencesKey("source_url")
