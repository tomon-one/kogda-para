package ru.whensclass.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        val state = AppContainer.store(context).widgetState()
        val schedule = ScheduleWidget.parse(state.scheduleJson)
        val colors = WidgetColors.resolve(context, ThemeChoice.from(state.theme))

        provideContent {
            val today = LocalDate.now()
            val next = nextLesson(schedule, today)
            val lesson = next?.lesson

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
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                        formatFetchedShort(state.fetchedAt) + " ⟳",
                        style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                        modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
                    )
                    return@Column
                }

                val bells = schedule?.bells.orEmpty()
                val now = next.day == today && currentLessonNumber(bells, today) == lesson.number
                val time = lessonStart(bells, lesson.number) ?: "${lesson.number} пара"
                // Про завтрашнюю пару тоже говорим: «пар больше нет» слишком
                // легко прочесть как «пар нет вообще» и расслабиться.
                val when_ = when {
                    now -> "идёт сейчас"
                    next.day == today -> "сегодня"
                    next.day == today.plusDays(1) -> "завтра"
                    else -> formatDayTitleShort(next.day)
                }
                Text(
                    "$when_ · $time",
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = if (now) colors.accent else colors.textDim,
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
                        color = if (lesson.url != null) colors.accent else colors.text,
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
fun nextLesson(schedule: ScheduleDto?, today: LocalDate): NextLesson? {
    if (schedule == null) return null
    val bells = schedule.bells
    val todayLessons = schedule.days.firstOrNull { it.date == today.toString() }?.lessons.orEmpty()

    currentLessonNumber(bells, today)
        ?.let { number -> todayLessons.firstOrNull { it.number == number } }
        ?.let { return NextLesson(today, it) }

    val now = java.time.LocalTime.now()
    todayLessons.firstOrNull { lesson ->
        val start = parseTime(bells[lesson.number.toString()]?.getOrNull(0))
        start == null || start > now
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
    if (lesson.url != null) {
        if (isNotEmpty()) append(" · ")
        append("онлайн  ⧉")
    } else {
        if (isNotEmpty()) append(" · ")
        append(roomLabel(lesson.room) ?: "места нет")
    }
}
