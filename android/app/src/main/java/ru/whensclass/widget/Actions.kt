package ru.whensclass.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import ru.whensclass.work.SyncWorker

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

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        SyncWorker.now(context)
    }
}
