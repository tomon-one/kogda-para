package ru.whensclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        // Если хранилище не открылось (бывает при обновлении приложения),
        // виджет должен сказать об этом, а не остаться пустым.
        val state = runCatching { AppContainer.get(context).store.widgetState() }.getOrNull()
        val schedule = parse(state?.scheduleJson)
        val colors = WidgetColors.resolve(
            context, ThemeChoice.from(state?.theme),
        )

        provideContent {
            // Палитра своя (см. WidgetColors), а не системная: оболочки на
            // телефонах слишком по-разному понимают динамические цвета.
            Content(
                schedule,
                state?.groupName,
                state?.fetchedAt ?: 0L,
                colors,
                offset = currentOffset(),
                busy = currentState(KEY_BUSY) == true,
            )
        }
    }

    @Composable
    private fun currentOffset(): Int = currentState(KEY_DAY_OFFSET) ?: 0

    @Composable
    private fun Content(
        schedule: ScheduleDto?,
        groupName: String?,
        fetchedAt: Long,
        colors: Palette,
        offset: Int,
        busy: Boolean,
    ) {
        ScheduleWidgetContent(
            schedule = schedule,
            groupName = groupName,
            fetchedAt = fetchedAt,
            busy = busy,
            colors = colors,
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

        /** Идёт ли сейчас обновление: нажатие должно отзываться сразу. */
        val KEY_BUSY = booleanPreferencesKey("busy")

        /** Дальше недели листать нечего: ровно столько храним на телефоне. */
        const val MAX_OFFSET = 6

        private val json = Json { ignoreUnknownKeys = true }

        // Разбор одного и того же ответа при каждом переключении дня — самая
        // дорогая часть перерисовки. Пока текст не менялся, отдаём разобранное.
        private var cachedJson: String? = null
        private var cachedSchedule: ScheduleDto? = null

        /** Разбор ответа, общий для всех виджетов. */
        @Synchronized
        fun parse(body: String?): ScheduleDto? {
            if (body == null) return null
            if (body == cachedJson) return cachedSchedule
            val parsed = runCatching { json.decodeFromString<ScheduleDto>(body) }.getOrNull()
            cachedJson = body
            cachedSchedule = parsed
            return parsed
        }
    }
}
