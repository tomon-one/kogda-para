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
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import ru.whensclass.R
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
    busy: Boolean = false,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .cornerRadius(16.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Header(
            groupName ?: "Расписание",
            day,
            offset,
            fetchedAt,
            firstOffset(schedule),
            lastOffset(schedule),
            busy,
            colors,
        )
        Spacer(GlanceModifier.height(6.dp))

        val today = schedule?.days?.firstOrNull { it.date == day.toString() }
        when {
            groupName == null -> Hint("Откройте приложение и выберите свою группу", colors)
            schedule == null -> Hint("Расписание ещё не загружено", colors)
            today == null -> Hint("Расписание на этот день ещё не опубликовано", colors)
            today.lessons.isEmpty() -> Hint("Пар нет", colors)
            else -> Lessons(
                today.lessons,
                schedule.bells,
                day,
                colors,
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            )
        }
    }
}

/**
 * До какого дня вперёд есть данные — в тех же шагах, что и листание.
 *
 * Расписание приходит с понедельника, вместе с прожитыми днями, поэтому
 * считать по длине списка нельзя: выходило, что вперёд листать некуда.
 */
private fun lastOffset(schedule: ScheduleDto?): Int =
    offsets(schedule).maxOrNull()?.coerceIn(0, ScheduleWidget.MAX_OFFSET) ?: 0

/**
 * Насколько далеко назад есть данные.
 *
 * Расписание приходит с понедельника: прожитые дни уже лежат на телефоне, и
 * запрещать их листать незачем — на экране приложения они тоже остаются.
 */
private fun firstOffset(schedule: ScheduleDto?): Int =
    offsets(schedule).minOrNull()?.coerceIn(-ScheduleWidget.MAX_OFFSET, 0) ?: 0

private fun offsets(schedule: ScheduleDto?): List<Int> {
    val today = LocalDate.now()
    return schedule?.days
        ?.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        ?.map { ChronoUnit.DAYS.between(today, it).toInt() }
        .orEmpty()
}

@Composable
private fun Header(
    groupName: String,
    day: LocalDate,
    offset: Int,
    fetchedAt: Long,
    firstDay: Int,
    lastDay: Int,
    busy: Boolean,
    colors: Palette,
) {
    val context = LocalContext.current
    val openApp = actionStartActivity(openDay(context, day))

    // Крупно — день, ради которого виджет и ставят. Группа своя, её и так
    // знают наизусть, поэтому она уехала во вторую строку.
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Логотип колледжа — одни контуры, без своего фона. Красим его
        // цветом темы: фирменный красный на чёрном сливается с фоном.
        Image(
            provider = ImageProvider(R.drawable.logo_ngok),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.logo),
            modifier = GlanceModifier.size(width = 26.dp, height = 14.dp),
        )
        Spacer(GlanceModifier.width(6.dp))

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
                    // ФИО целиком не влезает рядом со временем проверки.
                    if (groupName.count { it == ' ' } >= 2) shortenName(groupName) else groupName,
                    maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                    modifier = GlanceModifier.clickable(openApp),
                )
                // Время последней проверки — служебная мелочь, поэтому тем же
                // приглушённым цветом; краснеет, только когда данные протухли.
                Text(
                    if (busy) " · обновляю…" else " · " + formatFetchedShort(fetchedAt) + " ⟳",
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = when {
                            busy -> colors.accent
                            isStale(fetchedAt) -> colors.error
                            else -> colors.textDim
                        },
                    ),
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
                )
            }
        }
        ArrowButton("‹", step = -1, enabled = offset > firstDay, colors = colors)
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
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    if (lessons.isEmpty()) {
        // Подстраховка: список без строк оставлял виджет пустым, и человек
        // видел только шапку на чёрном фоне.
        Hint("Пар нет", colors)
        return
    }
    val current = currentLessonNumber(bells, day)
    // Ленивый список — единственное, что в виджете прокручивается пальцем:
    // в высокую пару дней шесть занятий не влезают, а листать их надо.
    LazyColumn(modifier = modifier) {
        items(lessons, itemId = { it.number.toLong() }) { lesson ->
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                LessonRow(lesson, bells, isNow = lesson.number == current, colors = colors)
                Spacer(GlanceModifier.height(6.dp))
            }
        }
    }
}

/** Приложение открывается на том же дне, что показывает виджет. */
private fun openDay(context: android.content.Context, day: LocalDate): Intent =
    Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_DAY, day.toString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
            if (isNotEmpty()) append(" · ")
            append(roomLabel(lesson.room) ?: "места нет")
        }
    }

    if (place.isNotEmpty()) {
        val row = GlanceModifier.fillMaxWidth()
        Text(
            if (lesson.url != null) "$place  ⧉" else place,
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

    // В расписании преподавателя вместо его имени — группы, которым читается
    // пара: сам он и так знает, кто ведёт.
    val who = lesson.groups ?: lesson.teachers.firstOrNull()?.let(::shortenName)
    who?.let {
        Text(it, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = colors.textDim))
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
