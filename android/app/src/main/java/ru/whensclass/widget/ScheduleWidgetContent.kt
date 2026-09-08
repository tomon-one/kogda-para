package ru.whensclass.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import android.content.Intent
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
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
import java.time.LocalTime
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
    val size = LocalSize.current
    // Оболочки вроде Nova дают сжать виджет ниже объявленного минимума. Ругаться
    // на это некому — просто убираем то, без чего можно, начиная с логотипа.
    val fit = Fit(narrow = size.width < 220.dp, dense = size.height < 120.dp)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .cornerRadius(16.dp)
            .padding(horizontal = if (fit.narrow) 6.dp else 10.dp)
            .padding(vertical = if (fit.dense) 4.dp else 8.dp),
    ) {
        Header(
            groupName ?: "Расписание",
            day,
            offset,
            fetchedAt,
            firstOffset(schedule),
            lastOffset(schedule),
            busy,
            fit,
            colors,
        )
        Spacer(GlanceModifier.height(if (fit.dense) 3.dp else 6.dp))

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
                fit,
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
    fit: Fit,
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
        // цветом темы: фирменный красный на чёрном сливается с фоном. На узком
        // виджете он уходит первым: место нужнее дню и стрелкам.
        if (!fit.narrow) {
            Image(
                provider = ImageProvider(R.drawable.logo_ngok),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.logo),
                modifier = GlanceModifier.size(width = 26.dp, height = 14.dp),
            )
            Spacer(GlanceModifier.width(6.dp))
        }

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
                    if (busy) " · обновляю…" else " · " + formatFetchedShort(fetchedAt),
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = when {
                            busy -> colors.accent
                            isStale(fetchedAt) -> colors.error
                            else -> colors.textDim
                        },
                    ),
                )
            }
        }
        RefreshButton(busy, colors)
        Spacer(GlanceModifier.width(4.dp))
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
    fit: Fit,
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
    // Обычный список, не ленивый. Ленивый прокручивался пальцем, но жил только
    // пока жив процесс приложения: система выгружала его — и виджет чернел
    // насовсем, не оживая ни обновлением, ни запуском приложения.
    val rowHeight = if (fit.dense) 50.dp else 58.dp
    // Шапка с группой и стрелками плюс строка «ещё N»: их место списку не
    // достаётся. Раньше «ещё» отнимало строку у пары, и вместо двух занятий
    // виджет показывал одно — хуже, чем не показать остаток вовсе.
    val free = LocalSize.current.height - (if (fit.dense) 48.dp else 58.dp) - 16.dp
    val fits = (free / rowHeight).toInt().coerceAtLeast(2).coerceAtMost(lessons.size)
    val start = windowStart(lessons, bells, day, fits)
    val shown = lessons.subList(start, minOf(lessons.size, start + fits))
    val rest = lessons.size - start - shown.size

    Column(modifier = modifier) {
        shown.forEach { lesson ->
            LessonRow(lesson, bells, isNow = lesson.number == current, fit, colors)
            Spacer(GlanceModifier.height(if (fit.dense) 3.dp else 4.dp))
        }
        if (rest > 0) {
            val context = LocalContext.current
            Text(
                morePairs(rest),
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(start = 4.dp)
                    .clickable(actionStartActivity(openDay(context, day))),
            )
        }
    }
}

/**
 * С какой пары начинать список, когда влезают не все.
 *
 * К обеду первые пары уже не нужны, а последние не видны. Поэтому сегодняшний
 * список начинается с той пары, которая ещё не кончилась, и съезжает вниз сам
 * собой в течение дня. Прошлые и будущие дни показываются с начала.
 */
private fun windowStart(
    lessons: List<LessonDto>,
    bells: Map<String, List<String>>,
    day: LocalDate,
    fits: Int,
): Int {
    if (day != LocalDate.now()) return 0
    val now = LocalTime.now()
    val index = lessons.indexOfFirst { lesson ->
        val end = bells[lesson.number.toString()]?.getOrNull(1)
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        end == null || !now.isAfter(end)
    }
    if (index <= 0) return 0
    // У конца дня не оставляем пустоту снизу: окно упирается в последнюю пару.
    return minOf(index, maxOf(0, lessons.size - fits))
}

/** Насколько тесно виджету — от этого зависит, что показывать. */
private data class Fit(val narrow: Boolean, val dense: Boolean)

/** Приложение открывается на том же дне, что показывает виджет. */
internal fun openDay(context: android.content.Context, day: LocalDate): Intent =
    Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_DAY, day.toString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

@Composable
private fun LessonRow(
    lesson: LessonDto,
    bells: Map<String, List<String>>,
    isNow: Boolean,
    fit: Fit,
    colors: Palette,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(if (isNow) colors.nowSurface else colors.surface)
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = if (fit.dense) 3.dp else 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Ширины хватает на «09:00–10:30» одной строкой: время, переносимое
        // пополам, читается как опечатка. На узком виджете диапазон не влезает —
        // тогда показываем только начало пары.
        Column(modifier = GlanceModifier.width(if (fit.narrow) 48.dp else 72.dp)) {
            Text(
                "${lesson.number} пара",
                maxLines = 1,
                style = TextStyle(fontSize = 10.sp, color = colors.textDim),
            )
            val time = if (fit.narrow) {
                lessonStart(bells, lesson.number)
            } else {
                lessonTime(bells, lesson.number)
            }
            time?.let { time ->
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
    // Одной строкой, а не тремя. Раньше тип, место и преподаватель занимали по
    // строке каждый, пара выходила в четыре строки высотой, и в виджет помещалась
    // одна — при том что смотрят в него ради двух ближайших.
    val parts = buildList {
        if (lesson.isCancelled) add(lesson.note?.let { "отменена — $it" } ?: "отменена")
        kindName(lesson.kind)?.let { add(it) }
        add(if (lesson.url != null) "онлайн" else roomLabel(lesson.room) ?: "места нет")
        // В расписании преподавателя вместо его имени — группы, которым читается
        // пара: сам он и так знает, кто ведёт.
        (lesson.groups ?: lesson.teachers.firstOrNull()?.let(::shortenName))?.let { add(it) }
    }
    if (parts.isEmpty()) return

    val line = GlanceModifier.fillMaxWidth()
    Text(
        if (lesson.url != null) parts.joinToString(" · ") + "  ⧉" else parts.joinToString(" · "),
        maxLines = 1,
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                lesson.isCancelled -> colors.error
                lesson.url != null -> colors.accent
                else -> colors.text
            },
        ),
        modifier = lesson.url?.let { url ->
            val context = LocalContext.current
            line.clickable(actionStartActivity(CopyLinkActivity.intent(context, url)))
        } ?: line,
    )
}

/**
 * Кнопка обновления.
 *
 * Была значком ⟳ внутри служебной строки — её не находили глазами. Теперь это
 * такая же площадка, как стрелки листания, и пока идёт запрос она показывает
 * многоточие: анимации виджет не умеет, а обратная связь нужна.
 */
@Composable
internal fun RefreshButton(busy: Boolean, colors: Palette) {
    Text(
        if (busy) "•••" else "⟳",
        maxLines = 1,
        style = TextStyle(
            fontSize = 15.sp,
            color = if (busy) colors.accent else colors.text,
        ),
        modifier = GlanceModifier
            .background(colors.button)
            .cornerRadius(8.dp)
            .clickable(actionRunCallback<RefreshAction>())
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
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
