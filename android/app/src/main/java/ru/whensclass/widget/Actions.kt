package ru.whensclass.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import ru.whensclass.AppContainer

/**
 * Переключение дня в виджете.
 *
 * День хранится в состоянии конкретного виджета, а не в общих настройках:
 * два виджета на экране могут показывать разные дни, и это разумно.
 */
class ShiftDayAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val step = parameters[KEY_STEP] ?: return
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            val current = prefs[ScheduleWidget.KEY_DAY_OFFSET] ?: 0
            val next = (current + step).coerceIn(0, ScheduleWidget.MAX_OFFSET)
            prefs.toMutablePreferences().apply { this[ScheduleWidget.KEY_DAY_OFFSET] = next }
        }
        ScheduleWidget().update(context, glanceId)
    }

    companion object {
        val KEY_STEP = ActionParameters.Key<Int>("step")
    }
}

/**
 * Обновление по нажатию на виджете.
 *
 * Идём за расписанием сами, а не через WorkManager: тот вправе отложить
 * задачу на минуты, и нажатие выглядит как не сработавшее.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        AppContainer.get(context).repository.refresh(force = true)
    }
}
