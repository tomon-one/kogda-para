package ru.whensclass.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.whensclass.AppContainer
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.WeekWidget

/**
 * Перерисовка виджетов в момент, когда их содержимое меняется само: смена
 * суток (WorkManager не обещает сработать в полночь) и звонок (подсветка
 * идущей пары иначе ждала бы часового обновления).
 *
 * Будильник один и всегда стоит на ближайшем из этих моментов; сработав, он
 * заводит себя заново.
 */
object MidnightUpdater {

    /**
     * Ближайший момент, когда виджет должен перерисоваться: звонок по сетке
     * сегодняшнего дня из сохранённого расписания или, без сетки, полночь.
     */
    suspend fun nextMoment(context: Context): LocalDateTime {
        val state = runCatching { AppContainer.get(context).store.widgetState() }.getOrNull()
        val now = ru.whensclass.widget.collegeNow()
        val bells = state?.let { ScheduleWidget.parse(it.scheduleJson)?.bellsOn(now.toLocalDate()) }.orEmpty()
        return ru.whensclass.widget.nextTick(bells, now)
    }

    suspend fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = ru.whensclass.widget.millisOf(nextMoment(context))
        // Точное время, если разрешено: подсветка должна меняться со звонком.
        if (LessonAlarms.exactAllowed(context)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pendingIntent(context))
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC, at, pendingIntent(context))
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, MidnightReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Ловит свой будильник, перезагрузку и перевод часов. После перезагрузки
 * будильники стираются, и без этого виджет замер бы на дне выключения.
 */
class MidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Перерисовка — работа с диском: через goAsync, onReceive живёт секунды.
        val finish = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // На смене суток сбрасываем листание дневного виджета (иначе
                // утром «завтра» покажет послезавтра); на звонке — нет, чтобы не
                // отобрать открытый день. «Сменились сутки с прошлого раза», а
                // не «сработало в полночь»: будильник неточный, телефон может
                // спать или перезагрузиться.
                val night = ScheduleWidget().newDay(app)
                // Сброс листания сам перерисовывает дневной виджет, поэтому
                // здесь только он; остальные — общим путём ниже.
                if (night) ScheduleWidget().resetDayOffset(app)
                // Все три виджета — через репозиторий: там вшит счёт показов.
                // Сбой перерисовки не должен отменить следующий звонок.
                runCatching { AppContainer.get(app).repository.redrawWidgets() }
                MidnightUpdater.schedule(app)
                // Напоминания живут в абсолютном времени: после перевода часов
                // или смены пояса (TIME_SET, TIMEZONE_CHANGED) их надо
                // переставить. На своём звонке — незачем, только на смене суток.
                if (night || intent?.action != null) LessonAlarms.reschedule(app)
                // За расписанием ходим только ночью: на звонке достаточно
                // перерисовать то, что уже лежит на телефоне.
                if (night) SyncWorker.now(app)
            } finally {
                finish.finish()
            }
        }
    }
}
