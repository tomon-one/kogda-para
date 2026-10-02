package ru.whensclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import ru.whensclass.AppContainer
import ru.whensclass.data.ScheduleDto

/**
 * Дневной виджет.
 *
 * Рисуется только из локального хранилища и никогда не ходит в сеть: за
 * обновление отвечает [ru.whensclass.work.SyncWorker]. Без сети показывает
 * последнее, что знает, и говорит, когда это было.
 */
class ScheduleWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = AppContainer.get(context).store
        // Если хранилище не открылось (бывает при обновлении приложения),
        // виджет должен сказать об этом, а не остаться пустым.
        val first = runCatching { store.widgetState() }.getOrNull()

        provideContent {
            // Следим за хранилищем из самой разметки: provideGlance на
            // перерисовку заново не зовётся ([ScheduleStore.widgetStates]).
            // Первым значением — то, что успели прочитать: иначе первый кадр
            // моргнул бы «расписание ещё не загружено».
            val state by store.widgetStates.collectAsState(initial = first)
            val schedule = parse(state?.scheduleJson)
            // Палитра своя (см. WidgetColors), а не системная: оболочки на
            // телефонах слишком по-разному понимают динамические цвета.
            val colors = WidgetColors.resolve(context, ThemeChoice.from(state?.theme))
            // Часы — только здесь, и дальше параметром: корень перекомпонуется
            // на каждую перерисовку, а Content с теми же входами Compose
            // пропускает, и «сегодня» с идущей парой внутри него замирали бы.
            val now = moment(currentState(KEY_TICK))
            Content(
                schedule,
                state?.groupName,
                state?.fetchedAt ?: 0L,
                colors,
                now = now,
                offset = currentOffset(),
                busy = refreshing(),
                done = currentState(KEY_DONE) == true,
                failed = currentState(KEY_FAILED) == true,
                serverBroken = state?.serverBroken == true,
                gone = state?.gone == true,
                unsupported = state?.unsupported == true,
                sourceUrl = state?.sourceUrl,
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
        now: LocalDateTime,
        offset: Int,
        busy: Boolean,
        done: Boolean,
        failed: Boolean,
        serverBroken: Boolean,
        gone: Boolean,
        unsupported: Boolean,
        sourceUrl: String?,
    ) {
        ScheduleWidgetContent(
            schedule = schedule,
            groupName = groupName,
            fetchedAt = fetchedAt,
            busy = busy,
            done = done,
            failed = failed,
            serverBroken = serverBroken,
            gone = gone,
            unsupported = unsupported,
            sourceUrl = sourceUrl,
            colors = colors,
            now = now,
            day = now.toLocalDate().plusDays(offset.toLong()),
            offset = offset,
            modifier = GlanceModifier,
        )
    }

    /**
     * Сменились ли сутки с прошлой проверки — чтобы утром виджет показывал
     * сегодня, а не вчерашнее «завтра». По дате в хранилище, а не по часу
     * будильника: он неточный и не переживает перезагрузку.
     */
    suspend fun newDay(context: Context): Boolean {
        val store = AppContainer.get(context).store
        val today = collegeToday().toString()
        if (store.lastWidgetDay() == today) return false
        // Не записалось (память забита) — сутки всё равно сменились.
        runCatching { store.setLastWidgetDay(today) }
        return true
    }

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
        val KEY_BUSY_AT = longPreferencesKey("busy_at")
        val KEY_DONE = booleanPreferencesKey("done")
        val KEY_FAILED = booleanPreferencesKey("failed")

        /** Меняется при каждой перерисовке — см. [redrawWidgets]. */
        val KEY_TICK = longPreferencesKey("tick")

        /** Виджет листает неделю в обе стороны; дальше — в приложении, там две недели. */
        const val MAX_OFFSET = 6

        private val json = Json { ignoreUnknownKeys = true }

        // Разбор — самая дорогая часть перерисовки: пока текст не менялся,
        // отдаём разобранное.
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
