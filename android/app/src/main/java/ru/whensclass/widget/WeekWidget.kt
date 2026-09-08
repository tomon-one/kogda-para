package ru.whensclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.Image
import androidx.glance.ColorFilter
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.currentState
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import java.time.LocalDate
import ru.whensclass.AppContainer
import ru.whensclass.R
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto

/**
 * Виджет на неделю целиком.
 *
 * Большой виджет отвечает на вопрос «что сегодня», а этот — на «когда у меня
 * окно» и «что в четверг»: всю неделю видно сразу, без листания. Поэтому
 * строка пары здесь короткая — номер, начало, название и кабинет.
 */
class WeekWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = runCatching { AppContainer.get(context).store.widgetState() }.getOrNull()
        val schedule = ScheduleWidget.parse(state?.scheduleJson)
        val colors = WidgetColors.resolve(context, ThemeChoice.from(state?.theme))

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(colors.background)
                    .cornerRadius(16.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                val days = schedule?.days.orEmpty()
                Header(
                    days,
                    state?.groupName,
                    state?.fetchedAt ?: 0L,
                    currentState(ScheduleWidget.KEY_BUSY) == true,
                    currentState(ScheduleWidget.KEY_DONE) == true,
                    colors,
                )
                Spacer(GlanceModifier.height(6.dp))

                when {
                    state?.groupName == null ->
                        Hint("Откройте приложение и выберите свою группу", colors)

                    schedule == null -> Hint("Расписание ещё не загружено", colors)
                    days.isEmpty() -> Hint("На эту неделю расписания нет", colors)
                    // Долю высоты список получает здесь, из Column:
                    // без неё он в некоторых оболочках схлопывается в
                    // ноль, и под шапкой остаётся пустота.
                    else -> Week(
                        days,
                        schedule.bells,
                        colors,
                        GlanceModifier.fillMaxWidth().defaultWeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    days: List<DayDto>,
    groupName: String?,
    fetchedAt: Long,
    busy: Boolean,
    done: Boolean,
    colors: Palette,
) {
    val context = LocalContext.current
    val today = LocalDate.now()

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.logo_ngok),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.logo),
            modifier = GlanceModifier
                .size(width = 26.dp, height = 14.dp)
                .clickable(actionStartActivity(openDay(context, today))),
        )
        Spacer(GlanceModifier.width(6.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                weekTitle(days),
                maxLines = 1,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                ),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .clickable(actionStartActivity(openDay(context, today))),
            )
            groupName?.let {
                Text(
                    if (it.count { c -> c == ' ' } >= 2) shortenName(it) else it,
                    maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                )
            }
        }
        Text(
            when {
                busy -> "обновляю…"
                done -> "обновлено"
                else -> formatFetchedShort(fetchedAt) + " ⟳"
            },
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                color = when {
                    busy || done -> colors.accent
                    isStale(fetchedAt) -> colors.error
                    else -> colors.textDim
                },
            ),
            modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

/**
 * Неделя одним списком.
 *
 * Дни и пары идут вперемешку: вложенные списки виджет рисовать не умеет.
 * Список обычный, не ленивый — ленивый прокручивался, но пустел насовсем,
 * стоило системе выгрузить приложение. Поэтому показываем столько, сколько
 * помещается, а остаток считаем последней строкой.
 */
@Composable
private fun Week(
    days: List<DayDto>,
    bells: Map<String, List<String>>,
    colors: Palette,
    modifier: GlanceModifier,
) {
    val week = remember(days) { weekDays(days) }
    val height = LocalSize.current.height
    val open = remember(week, height) { openCount(week, height - HEADER_SPACE) }

    // Каждый день — свой контейнер. Плоским списком дни рисоваться не могут:
    // разметка виджета собрана заранее и вмещает не больше десяти детей.
    Column(modifier = modifier) {
        week.forEachIndexed { index, day ->
            if (index < open) {
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    DayTitle(day, colors)
                    if (day.lessons.isEmpty()) {
                        EmptyLine(colors)
                    } else {
                        day.lessons.forEach { LessonLine(day.date, it, bells, colors) }
                    }
                }
            } else {
                DaySummary(day, bells, colors)
            }
        }
    }
}

/** «Неделя 7–12 сент.» по крайним дням расписания; без дат — просто «Неделя». */
private fun weekTitle(days: List<DayDto>): String {
    // Считаем по тем дням, что видны: прожитые виджет не показывает, и «7–12»
    // над списком, который начинается со вторника, сбивает с толку.
    val today = LocalDate.now()
    val dates = days
        .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        .filterNot { it.isBefore(today) }
    val from = dates.minOrNull()
    val to = dates.maxOrNull()
    return if (from == null || to == null) "Неделя" else formatWeekRange(from, to)
}

/** Шапка с логотипом и группой плюс отступы — то, что списку не достаётся. */
private val HEADER_SPACE = 62.dp

/**
 * Высоты строк.
 *
 * Сняты с живого экрана: разметку виджет собирает сам и о размерах не сообщает.
 */
private val TITLE_ROW = 21.dp
private val LESSON_ROW = 19.dp
private val EMPTY_ROW = 17.dp
private val SUMMARY_ROW = 21.dp

/**
 * Сколько ближайших дней показать парами.
 *
 * Раньше дни рисовались подряд, пока не кончится высота, а остаток уходил в
 * «ещё N пар». Для лёгкой недели это работало, для тяжёлой — нет: у самого
 * загруженного преподавателя 31 пара за шесть дней, и он видел бы два дня из
 * шести, не подозревая об остальных.
 *
 * Поэтому день не пропадает никогда: не хватило места на пары — остаётся строка
 * со сводкой. Разворачиваются ближайшие дни: дальние всё равно уточняют в
 * приложении.
 */
private fun openCount(week: List<WeekDay>, free: Dp): Int {
    var used = SUMMARY_ROW * week.size
    var count = 0
    for (day in week) {
        val grown = dayHeight(day) - SUMMARY_ROW
        if (used + grown > free) break
        used += grown
        count++
    }
    return count
}

private fun dayHeight(day: WeekDay): Dp =
    TITLE_ROW + if (day.lessons.isEmpty()) EMPTY_ROW else LESSON_ROW * day.lessons.size

@Composable
private fun DayTitle(day: WeekDay, colors: Palette) {
    val context = LocalContext.current
    Text(
        day.title,
        maxLines = 1,
        style = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            // Сегодняшний день выделен цветом: неделя читается с одного взгляда,
            // без поиска даты глазами.
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
                "  пар нет"
            } else {
                "  " + pairsCount(day.lessons.size) + (span(day, bells)?.let { " · $it" } ?: "")
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
private fun EmptyLine(colors: Palette) {
    Text(
        "пар нет",
        maxLines = 1,
        style = TextStyle(fontSize = 11.sp, color = colors.textDim),
        modifier = GlanceModifier.padding(start = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun LessonLine(
    date: LocalDate,
    lesson: LessonDto,
    bells: Map<String, List<String>>,
    colors: Palette,
) {
    val context = LocalContext.current
    val dim = lesson.isCancelled

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
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
            modifier = GlanceModifier.width(42.dp),
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
        Text(
            if (lesson.url != null) "онлайн" else lesson.room?.trim().orEmpty(),
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                color = if (lesson.url != null) colors.accent else colors.textDim,
            ),
            modifier = GlanceModifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun Hint(text: String, colors: Palette) {
    Text(
        text,
        style = TextStyle(fontSize = 12.sp, color = colors.textDim),
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(actionRunCallback<RefreshAction>()),
    )
}

/** Строка списка: заголовок дня, пара или отметка о пустом дне. */
/** День недели в виджете: заголовок и пары, если они есть. */
private class WeekDay(
    val date: LocalDate,
    val title: String,
    val isToday: Boolean,
    val lessons: List<LessonDto>,
)

private fun weekDays(days: List<DayDto>): List<WeekDay> {
    val today = LocalDate.now()
    return days.mapNotNull { day ->
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
        // Прожитые дни в недельном виджете не показываем: места мало, а к
        // пятнице понедельник занимает верх экрана и вытесняет нужное.
        if (date.isBefore(today)) return@mapNotNull null
        WeekDay(date, formatWeekDay(date), date == today, day.lessons)
    }
}
