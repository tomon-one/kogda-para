package ru.whensclass.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Только свои id. Приёмники виджетов экспортированы — иначе система не
 * доставит им обновление, — а APPWIDGET_UPDATE не защищённая рассылка: чужое
 * приложение могло прислать id недельного виджета маленькому приёмнику, и
 * Glance рисовал на месте недели одну строку.
 */
internal fun GlanceAppWidgetReceiver.ownIds(
    context: Context,
    manager: AppWidgetManager,
    ids: IntArray,
): IntArray {
    val mine = runCatching { manager.getAppWidgetIds(ComponentName(context, this::class.java)) }
        .getOrNull() ?: return IntArray(0)
    return ids.filter { it in mine }.toIntArray()
}

/**
 * Приёмник виджета, который слушает только свои id — во всех входах, а не
 * только в onUpdate. Чужое приложение слало DEBUG_UPDATE (Glance подставлял
 * все настоящие id, и каждая рассылка дёргала сеть), APPWIDGET_UPDATE_OPTIONS
 * с id чужого виджета (на месте недели рисовалась «Ближайшая пара») и
 * APPWIDGET_DELETED (стиралось состояние виджета).
 */
abstract class OwnWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Отладочная рассылка Glance: система её не шлёт, а своим мы её не шлём.
        if (intent.action == DEBUG_UPDATE) return
        super.onReceive(context, intent)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        if (ownIds(context, appWidgetManager, intArrayOf(appWidgetId)).isEmpty()) return
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Настоящий удалённый виджет в списках системы уже не значится, а
        // живой — значится: живые id (любого из трёх виджетов) не стираем.
        val manager = AppWidgetManager.getInstance(context)
        val live = RECEIVERS.flatMap { cls ->
            runCatching { manager.getAppWidgetIds(ComponentName(context, cls)).toList() }.getOrDefault(emptyList())
        }.toSet()
        val ids = appWidgetIds.filter { it !in live }.toIntArray()
        if (ids.isNotEmpty()) super.onDeleted(context, ids)
    }

    private companion object {
        const val DEBUG_UPDATE = "androidx.glance.appwidget.action.DEBUG_UPDATE"
        val RECEIVERS = listOf(
            ScheduleWidgetReceiver::class.java,
            WeekWidgetReceiver::class.java,
            NextLessonWidgetReceiver::class.java,
        )
    }
}
