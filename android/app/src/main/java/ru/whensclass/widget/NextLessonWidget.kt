package ru.whensclass.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import java.time.LocalDate
import java.time.LocalDateTime
import ru.whensclass.AppContainer
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.ui.MainActivity

/**
 * Маленький виджет: одна пара — та, что идёт сейчас, или ближайшая следующая.
 *
 * Нужен потому, что менять размер виджета умеет не всякая оболочка, а места на
 * домашнем экране обычно нет. Здесь ровно один ответ на вопрос «куда идти».
 */
class NextLessonWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Как и у двух других виджетов: чтение хранилища может не удаться, и
        // ронять из-за этого перерисовку незачем — лучше показать подсказку.
        val store = AppContainer.store(context)
        val first = runCatching { store.widgetState() }.getOrNull()

        provideContent {
            // См. ScheduleWidget: прочитанное до provideContent живёт до
            // конца сессии, и updateAll его не обновляет.
            val state by store.widgetStates.collectAsState(initial = first)
            val schedule = ScheduleWidget.parse(state?.scheduleJson)
            val colors = WidgetColors.resolve(context, ThemeChoice.from(state?.theme))
            // Часы — в корне и дальше параметром, как в остальных виджетах:
            // корень перекомпонуется на каждый updateAll, вложенное — нет.
            val now = LocalDateTime.now()
            val today = now.toLocalDate()
            val next = nextLesson(schedule, now)
            val lesson = next?.lesson
            val broken = state?.serverBroken == true
            val gone = state?.gone == true

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(colors.background)
                    .cornerRadius(16.dp)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .clickable(
                        // Открываем приложение сразу на том дне, о котором
                        // говорит виджет: иначе после нажатия ещё листать.
                        actionStartActivity(
                            Intent(LocalContext.current, MainActivity::class.java)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                                )
                                .putExtra(MainActivity.EXTRA_DAY, next?.day?.toString()),
                        ),
                    ),
            ) {
                if (lesson == null || next == null) {
                    Text(
                        if (schedule == null) "Расписание не загружено"
                        else "Дальше пар нет",
                        style = TextStyle(fontSize = 13.sp, color = colors.textDim),
                    )
                    Text(
                        (when {
                            gone -> "нет в таблице"
                            broken -> "сбой у нас"
                            else -> formatFetchedShort(state?.fetchedAt ?: 0L)
                        }) + " ⟳",
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = if (broken || gone) colors.error else colors.textDim,
                        ),
                        modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
                    )
                    return@Column
                }

                val bells = schedule?.bells.orEmpty()
                val ongoing = next.day == today && currentLessonNumber(bells, today, now) == lesson.number
                val time = lessonStart(bells, lesson.number) ?: "${lesson.number} пара"
                // Про завтрашнюю пару тоже говорим: «пар больше нет» слишком
                // легко прочесть как «пар нет вообще» и расслабиться.
                val when_ = when {
                    ongoing -> "идёт сейчас"
                    next.day == today -> "сегодня"
                    next.day == today.plusDays(1) -> "завтра"
                    else -> formatDayTitleShort(next.day)
                }
                // Сбой на сервере — в ту же строку и тем же красным, что у
                // остальных виджетов: пара на экране в этот момент из
                // прежнего снимка, и промолчать здесь значит соврать.
                val head = lesson.groups?.let { "$when_ · $time · $it" } ?: "$when_ · $time"
                Text(
                    when {
                        gone -> "$head · нет в таблице"
                        broken -> "$head · сбой у нас"
                        else -> head
                    },
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = when {
                            broken || gone -> colors.error
                            ongoing -> colors.accent
                            else -> colors.textDim
                        },
                    ),
                )
                Text(
                    lesson.subject,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.text,
                        textDecoration =
                        if (lesson.isCancelled) TextDecoration.LineThrough else null,
                    ),
                    modifier = GlanceModifier.fillMaxWidth(),
                )
                Text(
                    place(lesson),
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (lesson.isOnline) colors.accent else colors.text,
                    ),
                    modifier = lesson.url?.let { url ->
                        val context = LocalContext.current
                        GlanceModifier.fillMaxWidth()
                            .clickable(actionStartActivity(CopyLinkActivity.intent(context, url)))
                    } ?: GlanceModifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Пара и день, на который она приходится. */
data class NextLesson(val day: LocalDate, val lesson: LessonDto)

/**
 * Идущая сейчас пара; если её нет — ближайшая из оставшихся сегодня; если и
 * таких нет — первая пара следующего учебного дня.
 */
fun nextLesson(schedule: ScheduleDto?, now: LocalDateTime): NextLesson? {
    if (schedule == null) return null
    val bells = schedule.bells
    val today = now.toLocalDate()
    val todayLessons = schedule.days.firstOrNull { it.date == today.toString() }?.lessons.orEmpty()

    currentLessonNumber(bells, today, now)
        ?.let { number -> todayLessons.firstOrNull { it.number == number } }
        ?.let { return NextLesson(today, it) }

    val time = now.toLocalTime()
    todayLessons.firstOrNull { lesson ->
        val start = parseTime(bells[lesson.number.toString()]?.getOrNull(0))
        start == null || start > time
    }?.let { return NextLesson(today, it) }

    // Сегодня всё — ищем ближайший день, где пары есть.
    for (day in schedule.days) {
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: continue
        if (date <= today) continue
        day.lessons.firstOrNull()?.let { return NextLesson(date, it) }
    }
    return null
}

private fun place(lesson: LessonDto): String = buildString {
    kindName(lesson.kind)?.let { append(it) }
    if (lesson.isOnline) {
        if (isNotEmpty()) append(" · ")
        // Значок обещает, что по нажатию скопируется ссылка. Пары без
        // ссылки помечены тем же словом, но нажимать там нечего.
        append(onlineLabel(lesson))
        if (lesson.url != null) append("  ⧉")
    } else {
        if (isNotEmpty()) append(" · ")
        append(roomLabel(lesson.room) ?: "не указано")
    }
}
