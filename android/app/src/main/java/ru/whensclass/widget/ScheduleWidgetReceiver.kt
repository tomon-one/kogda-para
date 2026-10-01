package ru.whensclass.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import ru.whensclass.work.SyncWorker

class ScheduleWidgetReceiver : OwnWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScheduleWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val ids = ownIds(context, appWidgetManager, appWidgetIds)
        // Чужая рассылка без наших id не должна и дёргать сеть: каждое
        // SyncWorker.now отменяет идущее обновление.
        if (ids.isEmpty()) return
        super.onUpdate(context, appWidgetManager, ids)
        // Виджет только что поставили на экран — не заставлять человека ждать
        // ближайшего часового обновления.
        SyncWorker.now(context)
    }
}
