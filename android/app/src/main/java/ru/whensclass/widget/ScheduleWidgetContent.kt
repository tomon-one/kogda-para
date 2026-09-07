package ru.whensclass.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import android.content.Intent
import androidx.glance.LocalContext
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import java.time.LocalDate
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.ui.MainActivity

/**
 * Содержимое виджета.
 *
 * Вынесено из [ScheduleWidget] отдельной функцией, чтобы её же можно было
 * отрисовать при отладке, не устанавливая приложение на телефон ради каждой
 * правки отступа.
 */
@Composable
fun ScheduleWidgetContent(
    schedule: ScheduleDto?,
    groupName: String?,
    fetchedAt: Long,
    colors: Palette,
    day: LocalDate,
    offset: Int,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .cornerRadius(16.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Header(groupName ?: "Расписание", day, offset, fetchedAt, colors)
        Spacer(GlanceModifier.height(6.dp))

        val today = schedule?.days?.firstOrNull { it.date == day.toString() }
        when {
            groupName == null -> Hint("Откройте приложение и выберите свою группу", colors)
            schedule == null -> Hint("Расписание ещё не загружено", colors)
            today == null -> Hint("На этот день расписание ещё не опубликовано", colors)
            today.lessons.isEmpty() -> Hint("Пар нет", colors)
            else -> Lessons(today.lessons, schedule.bells, day, colors)
        }
    }
}

@Composable
private fun Header(
    groupName: String,
    day: LocalDate,
    offset: Int,
    fetchedAt: Long,
    colors: Palette,
) {
    val context = LocalContext.current
    val openApp = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                groupName,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                ),
                // Нажатие на название группы открывает приложение — там
                // расписание на неделю вперёд и настройки.
                modifier = GlanceModifier.defaultWeight().clickable(openApp),
            )
            ArrowButton("‹", step = -1, enabled = offset > 0, colors = colors)
            Spacer(GlanceModifier.width(4.dp))
            ArrowButton("›", step = 1, enabled = offset < ScheduleWidget.MAX_OFFSET, colors = colors)
        }

        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDayTitle(day),
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                modifier = GlanceModifier.defaultWeight().clickable(openApp),
            )
            // Обновление стоит рядом с временем последнего обновления: там оно
            // понятнее всего и не отнимает ширину у названия группы.
            Text(
                formatFetchedShort(fetchedAt) + " ⟳",
                maxLines = 1,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = if (isStale(fetchedAt)) colors.accent else colors.textDim,
                ),
                modifier = GlanceModifier
                    .clickable(actionRunCallback<RefreshAction>())
                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun ArrowButton(label: String, step: Int, enabled: Boolean, colors: Palette) {
    TapButton(
        label = label,
        colors = colors,
        enabled = enabled,
        action = actionRunCallback<ShiftDayAction>(
            actionParametersOf(ShiftDayAction.KEY_STEP to step),
        ),
    )
}

@Composable
private fun TapButton(
    label: String,
    colors: Palette,
    action: androidx.glance.action.Action,
    enabled: Boolean = true,
) {
    val color = if (enabled) colors.text else colors.textDim
    var modifier = GlanceModifier
        .background(if (enabled) colors.button else colors.buttonDisabled)
        .cornerRadius(8.dp)
    if (enabled) modifier = modifier.clickable(action)
    Text(
        label,
        style = TextStyle(fontSize = 15.sp, color = color),
        // Отступы внутри кликабельной области, иначе нажатие ловит только текст.
        modifier = modifier.padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun Lessons(
    lessons: List<LessonDto>,
    bells: Map<String, List<String>>,
    day: LocalDate,
    colors: Palette,
) {
    val current = currentLessonNumber(bells, day)
    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        items(lessons, itemId = { it.number.toLong() }) { lesson ->
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                LessonRow(lesson, bells, isNow = lesson.number == current, colors = colors)
                Spacer(GlanceModifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun LessonRow(
    lesson: LessonDto,
    bells: Map<String, List<String>>,
    isNow: Boolean,
    colors: Palette,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(if (isNow) colors.nowSurface else colors.surface)
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Ширины хватает на «09:00–10:30» одной строкой: время, переносимое
        // пополам, читается как опечатка.
        Column(modifier = GlanceModifier.width(78.dp)) {
            Text(
                "${lesson.number} пара",
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = colors.textDim),
            )
            lessonTime(bells, lesson.number)?.let { time ->
                Text(
                    time,
                    maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = colors.text),
                )
            }
        }
        Spacer(GlanceModifier.width(6.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                lesson.subject,
                maxLines = 2,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
                ),
            )
            Details(lesson, colors)
        }
    }
}

@Composable
private fun Details(lesson: LessonDto, colors: Palette) {
    val place = buildString {
        lesson.kind?.let { append(it) }
        lesson.room?.let {
            if (isNotEmpty()) append(" · ")
            append(it)
        }
        lesson.teachers.firstOrNull()?.let {
            if (isNotEmpty()) append(" · ")
            append(shortenName(it))
        }
    }
    if (place.isNotEmpty()) {
        Text(
            place,
            maxLines = 1,
            style = TextStyle(fontSize = 11.sp, color = colors.textDim),
        )
    }
    if (lesson.isCancelled) {
        Text(
            lesson.note?.let { "отменена — $it" } ?: "отменена",
            maxLines = 1,
            style = TextStyle(fontSize = 11.sp, color = colors.error),
        )
    }
    lesson.url?.let { url ->
        val context = LocalContext.current
        // Отдельной строкой, а не рядом с преподавателем: рядом они не
        // помещаются и наезжают друг на друга. Саму ссылку не печатаем — она
        // длинная и нечитаемая, нажатие кладёт её в буфер обмена.
        Text(
            "онлайн · копировать ссылку",
            maxLines = 1,
            style = TextStyle(fontSize = 11.sp, color = colors.accent),
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity(CopyLinkActivity.intent(context, url))),
        )
    }
}

@Composable
private fun Hint(text: String, colors: Palette) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionRunCallback<RefreshAction>()),
    ) {
        Text(
            text,
            style = TextStyle(fontSize = 13.sp, color = colors.textDim),
            modifier = GlanceModifier.padding(vertical = 8.dp),
        )
        Text(
            "нажмите, чтобы обновить",
            style = TextStyle(fontSize = 11.sp, color = colors.accent),
        )
    }
}
