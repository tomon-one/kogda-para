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
        val lastDay = schedule?.days?.size?.minus(1)?.coerceIn(0, ScheduleWidget.MAX_OFFSET) ?: 0
        Header(groupName ?: "Расписание", day, offset, fetchedAt, lastDay, colors)
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
    lastDay: Int,
    colors: Palette,
) {
    val context = LocalContext.current
    val openApp = actionStartActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    // Крупно — день, ради которого виджет и ставят. Группа своя, её и так
    // знают наизусть, поэтому она уехала во вторую строку.
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                formatDayTitleShort(day).replaceFirstChar { it.uppercase() },
                maxLines = 1,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                ),
                // Нажатие на шапку открывает приложение: там неделя целиком
                // и настройки.
                modifier = GlanceModifier.fillMaxWidth().clickable(openApp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    groupName,
                    maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                    modifier = GlanceModifier.clickable(openApp),
                )
                // Время последней проверки — служебная мелочь, поэтому тем же
                // приглушённым цветом; краснеет, только когда данные протухли.
                Text(
                    " · " + formatFetchedShort(fetchedAt) + " ⟳",
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = if (isStale(fetchedAt)) colors.error else colors.textDim,
                    ),
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
                )
            }
        }
        ArrowButton("‹", step = -1, enabled = offset > 0, colors = colors)
        Spacer(GlanceModifier.width(4.dp))
        ArrowButton("›", step = 1, enabled = offset < lastDay, colors = colors)
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
    // Отдельной строкой и цветом основного текста: тип занятия и аудитория —
    // то, ради чего в виджет и смотрят. Преподаватель уходит строкой ниже и
    // приглушённым: его имя обычно и так известно.
    val place = buildString {
        kindName(lesson.kind)?.let { append(it) }
        if (lesson.url != null) {
            if (isNotEmpty()) append(" · ")
            append("онлайн")
        } else {
            roomLabel(lesson.room)?.let {
                if (isNotEmpty()) append(" · ")
                append(it)
            }
        }
    }

    if (place.isNotEmpty()) {
        val row = GlanceModifier.fillMaxWidth()
        Text(
            if (lesson.url != null) "$place · копировать ссылку" else place,
            maxLines = 1,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (lesson.url != null) colors.accent else colors.text,
            ),
            modifier = lesson.url?.let { url ->
                val context = LocalContext.current
                row.clickable(actionStartActivity(CopyLinkActivity.intent(context, url)))
            } ?: row,
        )
    }

    lesson.teachers.firstOrNull()?.let {
        Text(
            shortenName(it),
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
