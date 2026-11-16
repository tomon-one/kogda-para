package ru.whensclass.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.whensclass.AppContainer
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.SubjectNames
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.collegeNow
import ru.whensclass.widget.millisOf

/**
 * Вечерняя сводка: одна строка о первой паре завтрашнего дня. Вечера — перед учебным днём:
 * вс–пт; в субботу молчим (воскресенье). Пар на завтра нет — не показываем ничего.
 */
object Digest {

    /** Время сводки — минута дня по часам колледжа. Раньше 16:00 «завтра» ещё не думают. */
    const val MIN = 16 * 60
    const val MAX = 23 * 60 + 59
    const val DEFAULT = 20 * 60

    fun reschedule(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch { rescheduleNow(app) }
    }

    /** Ставит будильник на ближайший вечер; выключено — снимает. */
    suspend fun rescheduleNow(context: Context) {
        val app = context.applicationContext
        val store = AppContainer.store(app)
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        // Прежний будильник — прочь: время могли поменять, а вечер — пройти.
        pending(app, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { manager.cancel(it) }
        val minutes = store.digestMinutes()
        if (minutes <= 0) return
        // Группы нет в таблице — о прежнем снимке сводку не шлём.
        if (store.widgetState().gone) return
        val at = nextEvening(collegeNow(), minutes)
        val fresh = pending(app, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) ?: return
        // Время колледжа, не телефона: millisOf закреплён тестом в чужом поясе.
        val millis = millisOf(at)
        if (LessonAlarms.exactAllowed(app)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, fresh)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, fresh)
        }
    }

    /**
     * Ближайший вечер, когда сводка уместна: не раньше `now`. В субботу не бывает — этот вечер
     * накануне воскресенья.
     */
    fun nextEvening(now: LocalDateTime, minutes: Int): LocalDateTime {
        var day = now.toLocalDate()
        var at = LocalDateTime.of(day, LocalTime.of(minutes / 60, minutes % 60))
        while (!at.isAfter(now) || day.dayOfWeek == DayOfWeek.SATURDAY) {
            day = day.plusDays(1)
            at = LocalDateTime.of(day, LocalTime.of(minutes / 60, minutes % 60))
        }
        return at
    }

    /**
     * Заголовок и текст сводки; null — сказать нечего: дня нет, он не прочитан, одни отмены или
     * пар нет. Короткое название — словарное, но своё, написанное человеком, важнее.
     */
    fun digestText(schedule: ScheduleDto, tomorrow: LocalDate, names: SubjectNames): Pair<String, String>? {
        val day = schedule.days.firstOrNull { it.date == tomorrow.toString() } ?: return null
        if (day.unread != null) return null
        val bells = schedule.bellsOf(day)
        val lessons = day.lessons.filterNot { it.isCancelled }
        if (lessons.isEmpty()) return null

        fun start(lesson: LessonDto): String? = bells[lesson.number.toString()]?.getOrNull(0)

        // Раньше всех начинается — она и первая; без звонка (не бывает, но) — по номеру.
        val first = lessons.minWithOrNull(compareBy<LessonDto>({ start(it) ?: "99:99" }, { it.number }))
            ?: return null
        val subject = names.custom[first.subject] ?: first.shortSubject?.takeIf { it.isNotBlank() }
        ?: first.subject
        val parts = listOfNotNull(subject.trim(), start(first), place(first))
        return "Завтра к ${first.number}-й паре" to parts.filter { it.isNotEmpty() }.joinToString(", ")
    }

    /** «ауд. 214», «онлайн»; пусто у пары без места. */
    private fun place(lesson: LessonDto): String? = when {
        lesson.isOnline && lesson.room.isNullOrBlank() -> "онлайн"
        lesson.isOnline -> "онлайн · " + lesson.room?.trim()
        else -> lesson.room?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "1" }
            ?.let { "ауд. $it" }
    }

    private fun pending(context: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, DigestReceiver::class.java),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Показывает сводку и ставит будильник на следующий вечер. */
class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = AppContainer.store(app)
                val state = store.widgetState()
                val schedule = ScheduleWidget.parse(state.scheduleJson)
                val tomorrow = collegeNow().toLocalDate().plusDays(1)
                if (schedule != null && !state.gone) {
                    Digest.digestText(schedule, tomorrow, state.subjects)?.let { (title, text) ->
                        Notifications.digest(app, title, text, tomorrow.toString())
                    }
                }
            } finally {
                // Следующий вечер — сразу: даже если сегодня сказать было нечего.
                Digest.rescheduleNow(app)
                done.finish()
            }
        }
    }
}
