package ru.whensclass.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
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
            // Назад — в прожитые дни недели: они уже лежат на телефоне.
            val next = (current + step)
                .coerceIn(-ScheduleWidget.MAX_OFFSET, ScheduleWidget.MAX_OFFSET)
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
        setBusy(context, glanceId, true)
        AppContainer.get(context).repository.refresh(force = true)
        setBusy(context, glanceId, false)
    }

    /** Отметка «обновляю» и сразу перерисовка: запрос идёт заметные секунды. */
    private suspend fun setBusy(context: Context, glanceId: GlanceId, busy: Boolean) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply { this[ScheduleWidget.KEY_BUSY] = busy }
        }
        // Кнопка есть на обоих виджетах, а перерисовать нужно тот, на котором
        // нажали: чужая разметка сюда не встанет.
        val ids = GlanceAppWidgetManager(context).getGlanceIds(ScheduleWidget::class.java)
        if (glanceId in ids) {
            ScheduleWidget().update(context, glanceId)
        } else {
            NextLessonWidget().update(context, glanceId)
        }
    }
}
