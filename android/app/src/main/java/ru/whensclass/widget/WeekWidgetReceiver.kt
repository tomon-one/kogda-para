package ru.whensclass.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget

class WeekWidgetReceiver : OwnWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val ids = ownIds(context, appWidgetManager, appWidgetIds)
        if (ids.isNotEmpty()) super.onUpdate(context, appWidgetManager, ids)
    }
}
