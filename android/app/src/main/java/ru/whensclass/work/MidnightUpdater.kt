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
 * Перерисовка виджетов в момент, когда их содержимое меняется само.
 *
 * Таких моментов два. Смена суток: виджет, показывающий вчерашний день, — ровно
 * то, ради чего всё это писалось, а WorkManager не обещает сработать в полночь.
 * И звонок: идущая пара выделена цветом, но без будильника виджет узнавал бы об
 * этом только при следующем обновлении расписания, то есть с опозданием до часа.
 *
 * Будильник один и всегда стоит на ближайшем из этих моментов; сработав, он
 * заводит себя заново.
 */
object MidnightUpdater {

    /**
     * Ближайший момент, когда виджет должен перерисоваться.
     *
     * Начала и концы пар берутся из сетки звонков, лежащей вместе с расписанием.
     * Если её нет, остаётся полночь — ждать больше нечего. И момент, когда
     * данные станут несвежими: время в шапках краснеет в нём, а не на
     * следующем звонке или в полночь (третий аудит, М16 прогона 1).
     */
    suspend fun nextMoment(context: Context): LocalDateTime {
        val state = runCatching { AppContainer.get(context).store.widgetState() }.getOrNull()
        val bells = state?.let { ScheduleWidget.parse(it.scheduleJson)?.bells }.orEmpty()
        val staleAt = state?.fetchedAt?.takeIf { it > 0 }?.let {
            java.time.Instant.ofEpochMilli(it).plus(ru.whensclass.widget.STALE_AFTER)
                .atZone(ru.whensclass.widget.COLLEGE_ZONE).toLocalDateTime()
        }
        return ru.whensclass.widget.nextTick(bells, ru.whensclass.widget.collegeNow(), staleAt)
    }

    suspend fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextMoment(context)
            .atZone(ru.whensclass.widget.COLLEGE_ZONE).toInstant().toEpochMilli()
        // Точное время, если разрешено: смысл будильника в том, чтобы подсветка
        // появлялась вместе со звонком, а не когда система сочтёт удобным.
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
                // Ночью сбрасываем переключение «на завтра»: иначе утром
                // человек увидит послезавтра вместо сегодня. На звонке этого
                // делать нельзя — он листает дни днём, и сброс отберёт у него
                // тот день, который он открыл.
                // Не «сработало в полночь», а «сменились сутки с прошлого
                // раза». Будильник неточный и телефон может спать: доставленный
                // в 01:05 он раньше не сбрасывал листание и не шёл за
                // расписанием. После ночной перезагрузки — то же самое.
                val night = ScheduleWidget().newDay(app)
                // Сброс листания сам перерисовывает дневной виджет, поэтому
                // здесь только он; остальные — общим путём ниже.
                if (night) ScheduleWidget().resetDayOffset(app)
                // Недельный виджет тоже живёт сегодняшним днём: он выделяет
                // текущую пару, прячет прожитые дни и пишет даты в заголовке.
                // Все три через репозиторий: счёт ответов вшит туда, и три
                // вызова руками мимо него теряли отрисовку.
                AppContainer.get(app).repository.redrawWidgets()
                MidnightUpdater.schedule(app)
                // Напоминания о парах живут в абсолютном времени, поэтому после
                // перевода часов или смены пояса приходят не тогда. Приёмник и
                // так подписан на TIME_SET и TIMEZONE_CHANGED — переставляем.
                //
                // Но не на каждом звонке: приёмник срабатывает по нашему же
                // будильнику шесть раз в день, и каждый раз переставлял восемь
                // будильников заново — при том что расписание с прошлого звонка
                // не менялось. Переставляем на смене суток и на системных
                // событиях, ради которых приёмник и подписан.
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
