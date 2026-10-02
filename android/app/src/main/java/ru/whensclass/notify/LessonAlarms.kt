package ru.whensclass.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
import ru.whensclass.widget.distinctSubjects
import ru.whensclass.widget.joinSubjects
import ru.whensclass.widget.kindName
import ru.whensclass.widget.roomLabel

/**
 * Напоминания «скоро пара». Будильники переставляются после каждого
 * обновления расписания и каждого срабатывания, поэтому держать их больше
 * чем на ближайшие пары незачем.
 */
object LessonAlarms {

    /** Сколько напоминаний держим одновременно. Больше в сутки и не бывает. */
    private const val MAX_ALARMS = 8

    fun reschedule(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch { rescheduleNow(app) }
    }

    /**
     * То же, но с ожиданием конца — для приёмника будильника: без `goAsync`
     * процесс могли убить раньше, чем будильники переставлены.
     */
    suspend fun rescheduleNow(context: Context) {
        val app = context.applicationContext
        val store = AppContainer.store(app)
        val minutes = store.notifyBeforeMinutes()
        cancelAll(app)
        if (minutes <= 0) return

        val state = store.widgetState()
        // Группы больше нет в таблице — по прежнему снимку напоминать нельзя:
        // пар, может, уже нет.
        if (state.gone) return
        val schedule = ScheduleWidget.parse(state.scheduleJson) ?: return
        // Висит напоминание о паре, которую с тех пор отменили или убрали, —
        // снять: рядом с «отменили N пару» оно звало бы на неё.
        Notifications.shownLessonKey(app)?.let { key ->
            if (!stillOn(schedule, key)) Notifications.lessonGone(app)
        }
        plan(schedule, minutes)
            // Две пары в одно время — одно напоминание.
            .distinctBy { it.at }
            .take(MAX_ALARMS)
            .forEachIndexed { index, alarm -> schedule(app, index, alarm) }
    }

    /**
     * Есть ли ещё пара из ключа напоминания («2026-09-29T21:47|Физика»), не
     * отменённая. У двух пар одного номера предметы в ключе — через [KEY_SEPARATOR].
     */
    internal fun stillOn(schedule: ScheduleDto, key: String): Boolean {
        val start = key.substringBefore('|')
        val subjects = key.substringAfter('|').split(KEY_SEPARATOR).toSet()
        val date = start.take(10)
        val time = start.drop(11).take(5)
        return schedule.days.firstOrNull { it.date == date }?.lessons?.any { lesson ->
            !lesson.isCancelled && lesson.subject in subjects &&
                schedule.bells[lesson.number.toString()]?.getOrNull(0) == time
        } == true
    }

    /** Что и когда напомнить. Вынесено отдельно, чтобы можно было проверить. */
    fun plan(schedule: ScheduleDto, minutes: Int, now: LocalDateTime = ru.whensclass.widget.collegeNow()):
        List<Alarm> {
        val out = mutableListOf<Alarm>()
        for (day in schedule.days) {
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: continue
            // Конец предыдущей пары этого дня. Отменённые не в счёт: пара после
            // отменённой — это уже возвращение с улицы.
            var busyUntil: LocalDateTime? = null
            // Пары соседней подгруппы — не свои: о них не напоминаем, и их
            // конец не глушит напоминание о своей. У преподавателя свои все.
            val own = day.lessons.filter { !it.isCancelled && !schedule.isNeighbours(it) }
            // Блок пополам — две пары одного номера в разных кабинетах: какая
            // из них его, приложение не знает, и напоминание называет обе.
            for ((_, same) in own.groupBy { it.number }.toSortedMap()) {
                val lesson = same.first()
                val bells = schedule.bells[lesson.number.toString()]
                val start = bells?.getOrNull(0)
                    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: continue
                val fireAt = LocalDateTime.of(date, start).minusMinutes(minutes.toLong())

                // Напоминают о том, к чему надо прийти: о первой паре дня и о
                // паре после окна. Посреди предыдущей пары — и ровно в её звонок
                // (перемены по 10 и 20 минут) — человек ещё в аудитории.
                val duringPrevious = busyUntil?.let { !fireAt.isAfter(it) } == true
                busyUntil = bells.getOrNull(1)
                    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    ?.let { LocalDateTime.of(date, it) }

                if (duringPrevious) continue
                if (fireAt.isBefore(now)) continue
                out.add(
                    Alarm(
                        fireAt, lesson, minutes, date.toString(), busyUntil,
                        ownGroup = schedule.groupName.takeUnless { schedule.isTeacher },
                        also = same.drop(1),
                        // Отменённая половинка — строкой «Отменена»: её подгруппа
                        // иначе пошла бы на чужую пару, приняв её за свою.
                        off = day.lessons.filter {
                            it.number == lesson.number && it.isCancelled && !schedule.isNeighbours(it)
                        },
                    ),
                )
            }
        }
        return out.sortedBy { it.at }
    }

    data class Alarm(
        val at: LocalDateTime,
        val lesson: LessonDto,
        val minutes: Int,
        val day: String,
        /** Конец пары — к нему напоминание снимается само. */
        val end: LocalDateTime? = null,
        /** Своя группа студента: её имя в напоминании не пишется. */
        val ownGroup: String? = null,
        /**
         * Другие пары того же номера: подгруппы в разных кабинетах или у
         * преподавателя одна пара в двух залах.
         */
        val also: List<LessonDto> = emptyList(),
        /** Отменённые пары того же номера — половинка блока, которую сняли. */
        val off: List<LessonDto> = emptyList(),
    ) {
        /** Когда пара начнётся: будильник стоит настолько же раньше. */
        val start: LocalDateTime get() = at.plusMinutes(minutes.toLong())

        val lessons: List<LessonDto> get() = listOf(lesson) + also

        /** Для заголовка: «Немецкий / Английский»; одинаковые — один раз. */
        val subject: String get() = joinSubjects(distinctSubjects(lessons))
    }

    private fun schedule(context: Context, index: Int, alarm: Alarm) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, LessonAlarmReceiver::class.java)
            // Предмет и время начала, а не готовый заголовок: см. title().
            .putExtra(EXTRA_SUBJECT, alarm.subject)
            .putExtra(EXTRA_KEY, alarm.lessons.joinToString(KEY_SEPARATOR) { it.subject })
            .putExtra(EXTRA_START, alarm.start.toString())
            .putExtra(EXTRA_TEXT, text(alarm))
            .putExtra(EXTRA_DAY, alarm.day)
            .putExtra(EXTRA_END, alarm.end?.toString())
        val pending = PendingIntent.getBroadcast(
            context,
            index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Время колледжа, не телефона: millisOf закреплён тестом в чужом поясе.
        val millis = ru.whensclass.widget.millisOf(alarm.at)
        if (exactAllowed(context)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        } else {
            // Запасной путь: разрешение на точное время не выдали. Напоминание
            // придёт, но система вправе его отложить.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        }
    }

    /**
     * Разрешено ли будить телефон в точное время. До Android 12 отдельного
     * разрешения нет, на 12-м его выдаёт человек, с 13-го — USE_EXACT_ALARM
     * при установке.
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
     * Заголовок напоминания: время начала, а не «через 20 минут» — уведомление
     * висит, и относительное время быстро врёт. Что пара уже идёт — считается
     * при показе: будильник может сработать позже назначенного.
     */
    fun title(
        subject: String,
        start: LocalDateTime,
        now: LocalDateTime = ru.whensclass.widget.collegeNow(),
    ): String = if (now.isAfter(start)) {
        "Пара уже идёт — $subject"
    } else {
        start.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + " — $subject"
    }

    /**
     * Текст напоминания. Место — первым: напоминание читают по пути, и
     * главное в нём — куда идти.
     */
    fun text(alarm: Alarm): String {
        if (alarm.also.isNotEmpty() || alarm.off.isNotEmpty()) {
            // Номер — первой строкой, дальше строка на пару, место — первым;
            // предмет — только если они разные: время и предмет — в заголовке.
            val lessons = alarm.lessons
            val kinds = lessons.map { kindName(it.kind) }.distinct()
            val number = "${alarm.lesson.number} пара" +
                (kinds.singleOrNull()?.let { ", ${it.lowercase()}" } ?: "")
            val withSubject = distinctSubjects(lessons + alarm.off).size > 1
            return (listOf(number) + lessons.map { lesson ->
                sentences(
                    listOfNotNull(
                        listOfNotNull(place(lesson), lesson.subject.takeIf { withSubject })
                            .joinToString(" — ").takeIf { it.isNotEmpty() },
                        lesson.teachers.firstOrNull(),
                        groupsOf(lesson, alarm.ownGroup),
                    ),
                )
            } + alarm.off.map { lesson ->
                sentences(
                    listOfNotNull(
                        "Отменена" + (if (withSubject) " — ${lesson.subject}" else ""),
                        lesson.teachers.firstOrNull(),
                        groupsOf(lesson, alarm.ownGroup),
                    ),
                )
            }).joinToString("\n")
        }
        val lesson = alarm.lesson
        return sentences(
            listOfNotNull(
                place(lesson),
                "${lesson.number} пара" + (kindName(lesson.kind)?.let { ", ${it.lowercase()}" } ?: ""),
                lesson.teachers.firstOrNull(),
                groupsOf(lesson, alarm.ownGroup),
            ),
        )
    }

    private fun place(lesson: LessonDto): String? = if (lesson.isOnline) {
        "Онлайн" + (lesson.room?.trim()?.takeIf { it.isNotEmpty() }?.let { ", комната $it" } ?: "")
    } else {
        roomLabel(lesson.room)?.replaceFirstChar { it.uppercase() }
    }

    // Чья пара: у преподавателя — каким группам он идёт читать. Свою группу
    // студенту не подписываем (склейка ставит её имя на общий номер).
    private fun groupsOf(lesson: LessonDto, ownGroup: String?): String? =
        lesson.groups?.trim()?.takeIf { it.isNotEmpty() && it != ownGroup }

    // «Нечаев С. А.» уже кончается точкой — вторую не ставить.
    private fun sentences(parts: List<String>): String =
        parts.fold("") { acc, part ->
            if (acc.isEmpty()) part else acc + (if (acc.endsWith(".")) " " else ". ") + part
        }

    const val EXTRA_SUBJECT = "subject"
    const val EXTRA_KEY = "key"
    private const val KEY_SEPARATOR = "\u001f"
    const val EXTRA_START = "start"
    const val EXTRA_TEXT = "text"
    const val EXTRA_DAY = "day"
    const val EXTRA_END = "end"
}

/** Показывает напоминание и заодно переставляет будильники на следующие пары. */
class LessonAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val subject = intent?.getStringExtra(LessonAlarms.EXTRA_SUBJECT) ?: return
        val start = intent.getStringExtra(LessonAlarms.EXTRA_START)
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return
        val text = intent.getStringExtra(LessonAlarms.EXTRA_TEXT).orEmpty()
        val end = intent.getStringExtra(LessonAlarms.EXTRA_END)
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        Notifications.lessonSoon(
            context,
            LessonAlarms.title(subject, start),
            text,
            intent.getStringExtra(LessonAlarms.EXTRA_DAY),
            until = end?.let { ru.whensclass.widget.millisOf(it) },
            key = "$start|" + (intent.getStringExtra(LessonAlarms.EXTRA_KEY) ?: subject),
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                LessonAlarms.rescheduleNow(context)
            } finally {
                pending.finish()
            }
        }
    }
}
