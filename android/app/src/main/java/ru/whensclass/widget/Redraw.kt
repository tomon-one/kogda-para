package ru.whensclass.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import java.time.LocalDateTime

/**
 * Перерисовать все виджеты так, чтобы корень перекомпоновался и в живой сессии.
 *
 * updateAll при живой сессии виджета (~45 с после отрисовки, а в глубоком сне
 * и дольше) не перекомпонует корень, если не изменились ни Preferences
 * виджета, ни WidgetState, — звонок без сети был бы холостым. Поэтому в
 * состояние каждого виджета пишется новый [ScheduleWidget.KEY_TICK], а корень
 * читает его через [moment].
 */
suspend fun redrawWidgets(context: Context) {
    val manager = GlanceAppWidgetManager(context)
    val tick = System.currentTimeMillis()
    listOf(ScheduleWidget(), WeekWidget(), NextLessonWidget()).forEach { widget ->
        manager.getGlanceIds(widget.javaClass).forEach { id ->
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply { this[ScheduleWidget.KEY_TICK] = tick }
            }
            widget.update(context, id)
        }
    }
}

/**
 * Сейчас — с явной зависимостью от [ScheduleWidget.KEY_TICK]: корень, прочитавший
 * его через currentState, перекомпонуется на каждую перерисовку.
 */
@Suppress("UNUSED_PARAMETER")
internal fun moment(tick: Long?): LocalDateTime = collegeNow()
