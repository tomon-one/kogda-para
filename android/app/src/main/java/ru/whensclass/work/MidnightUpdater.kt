package ru.whensclass.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.glance.appwidget.updateAll
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.WeekWidget

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
        // Перерисовка виджета — работа с диском, в onReceive её держать нельзя:
        // система даёт обработчику несколько секунд и снимает процесс.
        val finish = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ScheduleWidget().resetDayOffset(app)
                // Недельный виджет тоже живёт сегодняшним днём: он выделяет
                // текущую пару, прячет прожитые дни и пишет даты в заголовке.
                // Без этого до утреннего обновления он считал бы вчера сегодня.
                WeekWidget().updateAll(app)
                NextLessonWidget().updateAll(app)
                MidnightUpdater.schedule(app)
                SyncWorker.now(app)
            } finally {
                finish.finish()
            }
        }
    }
}
