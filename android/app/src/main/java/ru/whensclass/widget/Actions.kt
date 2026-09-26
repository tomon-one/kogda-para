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
        // Границы — те же, что гасят стрелки: дни, что лежат на телефоне. Раньше
        // ±MAX_OFFSET, и быстрое второе нажатие, пришедшее раньше перерисовки,
        // уводило за край окна — будень следующей недели назывался «выходным»
        // (третий аудит, М13 прогона 2).
        val schedule = ScheduleWidget.parse(
            runCatching { AppContainer.get(context).store.widgetState() }.getOrNull()?.scheduleJson
        )
        val today = collegeToday()
        val low = firstOffset(schedule, today)
        val high = maxOf(low, lastOffset(schedule, today))
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            val current = prefs[ScheduleWidget.KEY_DAY_OFFSET] ?: 0
            // Назад — в прожитые дни недели: они уже лежат на телефоне.
            val next = (current + step).coerceIn(low, high)
            prefs.toMutablePreferences().apply { this[ScheduleWidget.KEY_DAY_OFFSET] = next }
        }
        ScheduleWidget().update(context, glanceId)
    }

    companion object {
        val KEY_STEP = ActionParameters.Key<Int>("step")
    }
}

/**
 * Идёт ли нажатое обновление. Отметка старше [BUSY_LIMIT_MS] — след нажатия,
 * процесс которого умер (М31 прогона 1): её не показываем.
 */
@androidx.compose.runtime.Composable
internal fun refreshing(): Boolean {
    val busy = androidx.glance.currentState(ScheduleWidget.KEY_BUSY) == true
    val at = androidx.glance.currentState(ScheduleWidget.KEY_BUSY_AT) ?: 0L
    return busy && System.currentTimeMillis() - at < BUSY_LIMIT_MS
}

private const val BUSY_LIMIT_MS = 90_000L

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
        val repository = AppContainer.get(context).repository
        // Обновление уже идёт (другое нажатие, часовой заход, открытие): второе
        // не запускаем и чужие отметки не трогаем — раньше одно нажатие писало
        // «обновлено», пока другое ещё шло (третий аудит, М15 прогона 2).
        if (repository.refreshing) return
        mark(context, glanceId, busy = true, done = false, failed = false)
        val result = repository.refresh(force = true)
        // «Обновлено» на пару секунд: нажавшему нужен ответ и тогда, когда
        // расписание не изменилось (время в шапке — время проверки, и оно
        // сдвигается при каждом удачном ответе). Но ответ должен быть честным:
        // раньше «обновлено» загоралось и после неудачи.
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
                // Момент начала: процесс может умереть посреди нажатия, и
                // «обновляю…» висело навсегда, пряча «сбой» (третий аудит,
                // М31 прогона 1). Старше минуты — не считается.
                if (busy) this[ScheduleWidget.KEY_BUSY_AT] = System.currentTimeMillis()
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
