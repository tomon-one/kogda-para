package ru.whensclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import ru.whensclass.AppContainer
import ru.whensclass.data.ScheduleDto

/**
 * Виджет на домашнем экране.
 *
 * Рисуется только из локального хранилища и никогда не ходит в сеть: за
 * обновление отвечает [ru.whensclass.work.SyncWorker]. Поэтому виджет одинаково
 * работает в метро, при выключенном сервере и на морозе — показывает последнее,
 * что знает, и честно говорит, когда это было.
 */
class ScheduleWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = AppContainer.get(context)
        val json = Json { ignoreUnknownKeys = true }

        val raw = container.store.scheduleJson.first()
        val schedule = raw?.let { runCatching { json.decodeFromString<ScheduleDto>(it) }.getOrNull() }
        val fetchedAt = container.store.fetchedAt.first()
        val groupName = container.store.groupName.first()

        provideContent {
            // Тема своя (см. WidgetColors), а не системная: виджет должен
            // выглядеть одинаково на любой оболочке.
            Content(schedule, groupName, fetchedAt, offset = currentOffset())
        }
    }

    @Composable
    private fun currentOffset(): Int = currentState(KEY_DAY_OFFSET) ?: 0

    @Composable
    private fun Content(
        schedule: ScheduleDto?,
        groupName: String?,
        fetchedAt: Long,
        offset: Int,
    ) {
        ScheduleWidgetContent(
            schedule = schedule,
            groupName = groupName,
            fetchedAt = fetchedAt,
            day = LocalDate.now().plusDays(offset.toLong()),
            offset = offset,
            modifier = GlanceModifier,
        )
    }

    /** Утром виджет обязан показывать сегодня, а не вчерашнее «завтра». */
    suspend fun resetDayOffset(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(ScheduleWidget::class.java).forEach { id ->
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply { this[KEY_DAY_OFFSET] = 0 }
            }
            update(context, id)
        }
    }

    companion object {
        val KEY_DAY_OFFSET = intPreferencesKey("day_offset")

        /** Сервер отдаёт три дня — дальше листать нечего. */
        const val MAX_OFFSET = 2
    }
}
