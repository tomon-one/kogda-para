package ru.whensclass.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.delay
import ru.whensclass.AppContainer
import ru.whensclass.data.RefreshResult

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
        mark(context, glanceId, busy = true, done = false, failed = false)
        val result = AppContainer.get(context).repository.refresh(force = true)
        // «Обновлено» на пару секунд: время в шапке меняется, только когда
        // расписание и правда другое, а нажавшему нужен ответ в любом случае.
        // Но ответ должен быть честным: раньше «обновлено» загоралось и после
        // неудачи, потому что на результат никто не смотрел.
        val failed = result is RefreshResult.Failed
        mark(context, glanceId, busy = false, done = !failed, failed = failed)
        delay(DONE_MS)
        mark(context, glanceId, busy = false, done = false, failed = false)
    }

    /** Отметка состояния и сразу перерисовка: запрос идёт заметные секунды. */
    private suspend fun mark(
        context: Context,
        glanceId: GlanceId,
        busy: Boolean,
        done: Boolean,
        failed: Boolean,
    ) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                this[ScheduleWidget.KEY_BUSY] = busy
                this[ScheduleWidget.KEY_DONE] = done
                this[ScheduleWidget.KEY_FAILED] = failed
            }
        }
        // Кнопка есть на обоих виджетах, а перерисовать нужно тот, на котором
        // нажали: чужая разметка сюда не встанет.
        val manager = GlanceAppWidgetManager(context)
        when (glanceId) {
            in manager.getGlanceIds(ScheduleWidget::class.java) ->
                ScheduleWidget().update(context, glanceId)

            in manager.getGlanceIds(WeekWidget::class.java) ->
                WeekWidget().update(context, glanceId)

            else -> NextLessonWidget().update(context, glanceId)
        }
    }

    private companion object {
        const val DONE_MS = 2000L
    }
}
