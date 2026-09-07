package ru.whensclass.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import ru.whensclass.widget.ScheduleWidget

/**
 * Перерисовка виджета при смене суток.
 *
 * WorkManager не обещает сработать ровно в полночь, а виджет, показывающий
 * вчерашний день, — ровно то, ради чего всё это писалось. Поэтому отдельный
 * будильник на 00:01, который заодно сбрасывает переключение «на завтра»:
 * иначе утром человек увидит послезавтра вместо сегодня.
 */
object MidnightUpdater {

    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val next = LocalDate.now().plusDays(1).atTime(LocalTime.of(0, 1))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        manager.setAndAllowWhileIdle(AlarmManager.RTC, next, pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, MidnightReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Ловит наступление новых суток, перезагрузку и перевод часов.
 *
 * После перезагрузки будильники стираются, поэтому его надо заводить заново —
 * иначе виджет замрёт на дне выключения телефона.
 */
class MidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runBlocking { ScheduleWidget().resetDayOffset(context) }
        MidnightUpdater.schedule(context)
        SyncWorker.now(context)
    }
}
