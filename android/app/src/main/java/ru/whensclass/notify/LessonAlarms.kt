package ru.whensclass.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Duration
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
import ru.whensclass.widget.formatDurationLong
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
    fun plan(schedule: ScheduleDto, minutes: Int, now: LocalDateTime = ru.whensclass.widget.collegeNow()):
        List<Alarm> {
        val out = mutableListOf<Alarm>()
        for (day in schedule.days) {
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: continue
            // Конец предыдущей пары этого дня. Отменённые не в счёт: они никого
            // не держат, и пара после отменённой — это уже возвращение с улицы.
            var busyUntil: LocalDateTime? = null
            for (lesson in day.lessons.sortedBy { it.number }) {
                if (lesson.isCancelled) continue
                val bells = schedule.bells[lesson.number.toString()]
                val start = bells?.getOrNull(0)
                    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: continue
                val fireAt = LocalDateTime.of(date, start).minusMinutes(minutes.toLong())

                // Напоминание, приходящее посреди предыдущей пары, не сообщает
                // ничего: человек уже здесь, а что дальше — видно в приложении.
                // Напоминают о том, к чему надо прийти: о первой паре дня и о
                // паре после окна.
                //
                // Ровно в звонок — тоже поздно: человек ещё в аудитории,
                // собирает сумку. Перемены в сетке колледжа по 10 и 20 минут,
                // и с напоминанием за 20 минут граница попадает точно в звонок.
                val duringPrevious = busyUntil?.let { !fireAt.isAfter(it) } == true
                busyUntil = bells.getOrNull(1)
                    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    ?.let { LocalDateTime.of(date, it) }

                if (duringPrevious) continue
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
    ) {
        /** Когда пара начнётся: будильник стоит настолько же раньше. */
        val start: LocalDateTime get() = at.plusMinutes(minutes.toLong())
    }

    private fun schedule(context: Context, index: Int, alarm: Alarm) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, LessonAlarmReceiver::class.java)
            // Предмет и время начала, а не готовый заголовок: сколько осталось,
            // считается в момент показа. См. title().
            .putExtra(EXTRA_SUBJECT, alarm.lesson.subject)
            .putExtra(EXTRA_START, alarm.start.toString())
            .putExtra(EXTRA_TEXT, text(alarm))
            .putExtra(EXTRA_DAY, alarm.day)
        val pending = PendingIntent.getBroadcast(
            context,
            index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val millis = alarm.at.atZone(ru.whensclass.widget.COLLEGE_ZONE).toInstant().toEpochMilli()
        if (exactAllowed(context)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        } else {
            // Запасной путь: разрешение на точное время не выдали. Напоминание
            // придёт, но система вправе его отложить.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        }
    }

    /**
     * Разрешено ли будить телефон в точное время.
     *
     * До Android 12 отдельного разрешения не было. Начиная с 14-й версии оно
     * по умолчанию не выдано, и человек включает его сам — в настройках
     * приложения есть подсказка.
     */
    fun exactAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
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

    /**
     * Заголовок напоминания.
     *
     * Остаток считается сейчас, а не при постановке будильника: будильник может
     * сработать позже назначенного, и обещание «через двадцать минут», данное
     * заранее, к моменту показа успевает соврать.
     */
    fun title(
        subject: String,
        start: LocalDateTime,
        now: LocalDateTime = ru.whensclass.widget.collegeNow(),
    ): String {
        val left = Math.round(Duration.between(now, start).seconds / 60.0).toInt()
        return when {
            left > 0 -> "Через ${formatDurationLong(left)} — $subject"
            left == 0 -> "Пара начинается — $subject"
            else -> "Пара уже идёт — $subject"
        }
    }

    fun text(alarm: Alarm): String = buildString {
        append("${alarm.lesson.number} пара")
        kindName(alarm.lesson.kind)?.let { append(", ${it.lowercase()}") }
        if (alarm.lesson.isOnline) {
            append(". Занятие онлайн")
            alarm.lesson.room?.trim()?.takeIf { it.isNotEmpty() }?.let { append(", комната $it") }
        } else {
            roomLabel(alarm.lesson.room)?.let { append(". $it") }
        }
        alarm.lesson.teachers.firstOrNull()?.let { append(". $it") }
        // Чья пара: у подгруппы — соседки, у преподавателя — каким группам он
        // идёт читать. На экране и в виджетах подпись есть, а в напоминании её
        // не было, и пара соседней подгруппы приходила как своя (второй аудит,
        // М16).
        alarm.lesson.groups?.trim()?.takeIf { it.isNotEmpty() }?.let { append(". $it") }
    }

    const val EXTRA_SUBJECT = "subject"
    const val EXTRA_START = "start"
    const val EXTRA_TEXT = "text"
    const val EXTRA_DAY = "day"
}

/** Показывает напоминание и заодно переставляет будильники на следующие пары. */
class LessonAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val subject = intent?.getStringExtra(LessonAlarms.EXTRA_SUBJECT) ?: return
        val start = intent.getStringExtra(LessonAlarms.EXTRA_START)
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return
        val text = intent.getStringExtra(LessonAlarms.EXTRA_TEXT).orEmpty()
        Notifications.lessonSoon(
            context,
            LessonAlarms.title(subject, start),
            text,
            intent.getStringExtra(LessonAlarms.EXTRA_DAY),
        )
        LessonAlarms.reschedule(context)
    }
}
