package ru.whensclass.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.LocalDateTime
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto

/**
 * Неделя одним списком. Список обычный, не ленивый (см. [Lessons]), поэтому
 * показываем столько, сколько помещается, а остаток считаем последней строкой.
 */
@Composable
internal fun Week(
    days: List<DayDto>,
    bells: Map<String, List<String>>,
    now: LocalDateTime,
    colors: Palette,
    modifier: GlanceModifier,
) {
    val today = now.toLocalDate()
    // Ключ и по дате: через полночь тот же список дней делится на прожитые
    // и предстоящие заново.
    val all = remember(days, today) { weekDays(days, today) }
    val current = currentLessonNumber(bells, today, now)
    val height = LocalSize.current.height
    val scale = fontScale()
    // Даже строки-сводки помещаются не всегда (семь дней, крупный шрифт):
    // тогда хвост недели — одной строкой «и ещё N дней».
    val fit = remember(all, height, scale) {
        fitWeek(
            all.map { (dayHeight(it) * scale).value },
            (height - HEADER_SPACE * scale).value,
            (SUMMARY_ROW * scale).value,
            firstHasLessons = all.firstOrNull()?.lessons?.isNotEmpty() == true,
        )
    }
    val week = all.take(fit.shown)
    val folded = all.drop(fit.shown)
    val open = fit.open

    // Каждый день — свой контейнер: в одном не больше [MAX_CHILDREN] детей.
    Column(modifier = modifier) {
        week.forEachIndexed { index, day ->
            if (index < open) {
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    DayTitle(day, colors)
                    if (day.lessons.isEmpty()) {
                        EmptyLine(day.date, colors, day.absent)
                    } else {
                        // Заголовок дня уже занял одного ребёнка. С другими
                        // группами пар в дне бывает больше девяти — тогда
                        // последняя строка говорит, сколько не поместилось.
                        val room = MAX_CHILDREN - 1
                        val shown = if (day.lessons.size > room) room - 1 else day.lessons.size
                        day.lessons.take(shown).forEach {
                            LessonLine(
                                day.date,
                                it,
                                bells,
                                colors,
                                // Отменённая — не идущая.
                                isNow = day.isToday && it.number == current && !it.isCancelled,
                            )
                        }
                        val rest = day.lessons.size - shown
                        if (rest > 0) MoreLine(day.date, rest, colors)
                    }
                }
            } else {
                DaySummary(day, bells, colors)
            }
        }
        folded.firstOrNull()?.let { first -> MoreDays(first.date, folded.size, colors) }
    }
}

/** «И ещё 2 дня» — хвост недели, на который не хватило даже строк-сводок. */
@Composable
private fun MoreDays(first: LocalDate, count: Int, colors: Palette) {
    val context = LocalContext.current
    Text(
        "и ещё " + plural(count, "день", "дня", "дней"),
        maxLines = 1,
        style = TextStyle(fontSize = 11.sp, color = colors.textDim),
        modifier = GlanceModifier
            .padding(top = 3.dp, start = 4.dp)
            .clickable(actionStartActivity(openDay(context, first))),
    )
}

/** Фон строки, которая не идёт сейчас: задаётся явно, см. [LessonLine]. */
private val NO_SURFACE = ColorProvider(Color.Transparent)

/**
 * Сколько детей вмещает контейнер Glance. Разметка виджета собирается заранее,
 * и всё сверх десятого пропадает молча — ни ошибки, ни пустого места.
 */
internal const val MAX_CHILDREN = 10

/**
 * «И ещё N» — когда пары в день не поместились в контейнер. Нажатие — на
 * этот день: без своего нажатия оно уходит корню, к сегодняшнему.
 */
@Composable
private fun MoreLine(date: LocalDate, rest: Int, colors: Palette) {
    val context = LocalContext.current
    Text(
        "и ещё " + plural(rest, "пара", "пары", "пар"),
        maxLines = 1,
        style = TextStyle(fontSize = 11.sp, color = colors.textDim),
        modifier = GlanceModifier.padding(start = 4.dp)
            .clickable(actionStartActivity(openDay(context, date))),
    )
}


@Composable
private fun DayTitle(day: WeekDay, colors: Palette) {
    val context = LocalContext.current
    Text(
        day.title,
        maxLines = 1,
        style = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            // Сегодняшний день выделен цветом.
            color = if (day.isToday) colors.accent else colors.text,
        ),
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 1.dp)
            .clickable(actionStartActivity(openDay(context, day.date))),
    )
}

/** День, на пары которого не хватило высоты: сколько их и с какого по какое. */
@Composable
private fun DaySummary(day: WeekDay, bells: Map<String, List<String>>, colors: Palette) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 1.dp)
            .clickable(actionStartActivity(openDay(context, day.date))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            day.title,
            maxLines = 1,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (day.isToday) colors.accent else colors.text,
            ),
        )
        Text(
            if (day.lessons.isEmpty()) {
                if (day.absent) "  выходной" else "  пар нет"
            } else {
                "  " + pairsCount(numbers(day.lessons)) + (span(day, bells)?.let { " · $it" } ?: "")
            },
            maxLines = 1,
            style = TextStyle(fontSize = 11.sp, color = colors.textDim),
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

/** «12:30–19:10»: от начала первой пары до конца последней. */
private fun span(day: WeekDay, bells: Map<String, List<String>>): String? {
    val first = day.lessons.minByOrNull { it.number } ?: return null
    val last = day.lessons.maxByOrNull { it.number } ?: return null
    val from = lessonStart(bells, first.number) ?: return null
    val to = bells[last.number.toString()]?.getOrNull(1) ?: return null
    return "$from–$to"
}

@Composable
private fun EmptyLine(date: LocalDate, colors: Palette, absent: Boolean = false) {
    val context = LocalContext.current
    Text(
        if (absent) "выходной" else "пар нет",
        maxLines = 1,
        style = TextStyle(fontSize = 11.sp, color = colors.textDim),
        // Свой день, а не сегодняшний.
        modifier = GlanceModifier.padding(start = 4.dp, bottom = 2.dp)
            .clickable(actionStartActivity(openDay(context, date))),
    )
}

@Composable
private fun LessonLine(
    date: LocalDate,
    lesson: LessonDto,
    bells: Map<String, List<String>>,
    colors: Palette,
    isNow: Boolean = false,
) {
    val context = LocalContext.current
    val dim = lesson.isCancelled

    Row(
        // Подложка у идущей пары — как в дневном виджете; кладётся первой и
        // накрывает строку вместе с отступом под ней.
        //
        // Фон задаётся всегда, у остальных строк — прозрачный: без модификатора
        // в RemoteViews не уходит команды про фон, лаунчер накатывает их на
        // прежние вьюхи, и подложка бывшей идущей пары остаётся.
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(if (isNow) colors.nowSurface else NO_SURFACE)
            .cornerRadius(6.dp)
            .padding(horizontal = 4.dp)
            .padding(bottom = 2.dp)
            .clickable(actionStartActivity(openDay(context, date))),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            lessonStart(bells, lesson.number) ?: "${lesson.number}",
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                color = if (dim) colors.textDim else colors.text,
            ),
            // Колонка растёт со шрифтом: «09:00» в sp, колонка в dp.
            modifier = GlanceModifier.width(42.dp * fontScale()),
        )
        Text(
            lesson.subject,
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                color = if (dim) colors.textDim else colors.text,
                textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        // У отменённой — «отменена», а не прежний кабинет: тонкое зачёркивание
        // серого за секунду не видно.
        Text(
            when {
                lesson.isCancelled -> "отменена"
                lesson.isOnline -> onlineLabel(lesson)
                else -> lesson.room?.trim().orEmpty()
            },
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                color = when {
                    lesson.isCancelled -> colors.error
                    lesson.isOnline -> colors.accent
                    else -> colors.textDim
                },
            ),
            modifier = GlanceModifier.padding(start = 4.dp),
        )
    }
}
