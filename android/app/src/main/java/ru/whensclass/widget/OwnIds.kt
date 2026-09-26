package ru.whensclass.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Только свои id. Приёмники виджетов экспортированы — иначе система не
 * доставит им обновление, — а APPWIDGET_UPDATE не защищённая рассылка: чужое
 * приложение могло прислать id недельного виджета маленькому приёмнику, и
 * Glance рисовал на месте недели одну строку (третий аудит, М47 прогона 1).
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
