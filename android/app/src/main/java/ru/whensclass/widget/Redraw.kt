package ru.whensclass.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import java.time.LocalDateTime

/**
 * Перерисовать все виджеты так, чтобы корень перекомпоновался и в живой сессии. updateAll при живой
 * сессии (~45 с после отрисовки, в глубоком сне дольше) не перекомпонует корень, если не изменились
 * ни Preferences, ни WidgetState — звонок без сети был бы холостым. Поэтому в состояние каждого
 * виджета пишется новый [ScheduleWidget.KEY_TICK], а корень читает его через [moment].
 */
suspend fun redrawWidgets(context: Context) {
    val tick = System.currentTimeMillis()
    listOf(
        ScheduleWidget() to ScheduleWidgetReceiver::class.java,
        WeekWidget() to WeekWidgetReceiver::class.java,
        NextLessonWidget() to NextLessonWidgetReceiver::class.java,
    ).forEach { (widget, receiver) ->
        widgetIds(context, receiver).forEach { id ->
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply { this[ScheduleWidget.KEY_TICK] = tick }
            }
            widget.update(context, id)
        }
    }
}

/**
 * Виджеты приёмника на экране — по списку системы, а не Glance: Glance узнаёт о виджете из onUpdate
 * и забывает вместе с данными приложения, и такой виджет без периодического обновления не
 * перерисовался бы никогда и стоял бы с заглушкой загрузки.
 */
internal fun widgetIds(context: Context, receiver: Class<*>): List<GlanceId> {
    val ids = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, receiver))
    }.getOrNull() ?: return emptyList()
    val manager = GlanceAppWidgetManager(context)
    // Виджет могли снять после списка: один снятый не должен обрывать весь заход.
    return ids.toList().mapNotNull { id -> runCatching { manager.getGlanceIdBy(id) }.getOrNull() }
}

/**
 * Сейчас — с явной зависимостью от [ScheduleWidget.KEY_TICK]: корень, прочитавший его через
 * currentState, перекомпонуется на каждую перерисовку.
 */
@Suppress("UNUSED_PARAMETER")
internal fun moment(tick: Long?): LocalDateTime = collegeNow()
