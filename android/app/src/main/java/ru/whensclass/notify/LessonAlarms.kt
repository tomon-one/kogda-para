package ru.whensclass.notify

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
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.kindName
import ru.whensclass.widget.roomLabel

/**
 * Напоминания «скоро пара».
 *
 * Будильники ставятся на ближайшие сутки после каждого обновления расписания:
 * держать больше незачем — расписание всё равно перечитывается несколько раз
 * в день, и каждый раз мы их переставляем заново.
 */
object LessonAlarms {

    /** Сколько напоминаний держим одновременно. Больше в сутки и не бывает. */
    private const val MAX_ALARMS = 8

    fun reschedule(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            val store = AppContainer.store(app)
            val minutes = store.notifyBeforeMinutes()
            cancelAll(app)
            if (minutes <= 0) return@launch

            val schedule = ScheduleWidget.parse(store.widgetState().scheduleJson) ?: return@launch
            plan(schedule, minutes)
                // Своя и соседняя подгруппы дают две пары в одно время —
                // напоминание об этом должно быть одно.
                .distinctBy { it.at }
                .take(MAX_ALARMS)
                .forEachIndexed { index, alarm -> schedule(app, index, alarm) }
        }
    }

    /** Что и когда напомнить. Вынесено отдельно, чтобы можно было проверить. */
    fun plan(schedule: ScheduleDto, minutes: Int, now: LocalDateTime = LocalDateTime.now()):
        List<Alarm> {
        val out = mutableListOf<Alarm>()
        for (day in schedule.days) {
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: continue
            for (lesson in day.lessons) {
                if (lesson.isCancelled) continue
                val start = schedule.bells[lesson.number.toString()]?.getOrNull(0)
                    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: continue
                val fireAt = LocalDateTime.of(date, start).minusMinutes(minutes.toLong())
                if (fireAt.isBefore(now)) continue
                out.add(Alarm(fireAt, lesson, minutes, date.toString()))
            }
        }
        return out.sortedBy { it.at }
    }

    data class Alarm(
        val at: LocalDateTime,
        val lesson: LessonDto,
        val minutes: Int,
        val day: String,
    )

    private fun schedule(context: Context, index: Int, alarm: Alarm) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, LessonAlarmReceiver::class.java)
            .putExtra(EXTRA_TITLE, title(alarm))
            .putExtra(EXTRA_TEXT, text(alarm))
            .putExtra(EXTRA_DAY, alarm.day)
        val pending = PendingIntent.getBroadcast(
            context,
            index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val millis = alarm.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // setAndAllowWhileIdle, а не точный будильник: минута туда-сюда роли не
        // играет, зато не нужно просить особое разрешение у системы.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    }

    private fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        repeat(MAX_ALARMS) { index ->
            val pending = PendingIntent.getBroadcast(
                context,
                index,
                Intent(context, LessonAlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            pending?.let { manager.cancel(it) }
        }
    }

    fun title(alarm: Alarm): String = "Через ${alarm.minutes} мин — ${alarm.lesson.subject}"

    fun text(alarm: Alarm): String = buildString {
        append("${alarm.lesson.number} пара")
        kindName(alarm.lesson.kind)?.let { append(", ${it.lowercase()}") }
        if (alarm.lesson.url != null) {
            append(". Занятие онлайн")
        } else {
            roomLabel(alarm.lesson.room)?.let { append(". $it") }
        }
        alarm.lesson.teachers.firstOrNull()?.let { append(". $it") }
    }

    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_DAY = "day"
}

/** Показывает напоминание и заодно переставляет будильники на следующие пары. */
class LessonAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val title = intent?.getStringExtra(LessonAlarms.EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(LessonAlarms.EXTRA_TEXT).orEmpty()
        Notifications.lessonSoon(
            context, title, text, intent.getStringExtra(LessonAlarms.EXTRA_DAY),
        )
        LessonAlarms.reschedule(context)
    }
}
