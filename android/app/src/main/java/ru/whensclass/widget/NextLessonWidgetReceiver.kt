package ru.whensclass.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget

class NextLessonWidgetReceiver : OwnWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextLessonWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val ids = ownIds(context, appWidgetManager, appWidgetIds)
        if (ids.isNotEmpty()) super.onUpdate(context, appWidgetManager, ids)
    }
}
