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
    val week = remember(days) { rowsOf(days) }
    val height = LocalSize.current.height
    val fitted = remember(week, height) { fitWeek(week, height - HEADER_SPACE) }

    // Каждый день — свой контейнер. Плоским списком дни рисоваться не могут:
    // разметка виджета собрана заранее и вмещает не больше десяти детей, всё
    // сверх десятого молча пропадает.
    Column(modifier = modifier) {
        fitted.days.forEach { day ->
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                day.forEach { row ->
                    when (row) {
                        is WeekRow.Title -> DayTitle(row, colors)
                        is WeekRow.Lesson -> LessonLine(row, bells, colors)
                        is WeekRow.Empty -> Text(
                            "пар нет",
                            maxLines = 1,
                            style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                            modifier = GlanceModifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                }
            }
        }
        if (fitted.hidden > 0) {
            Text(
                morePairs(fitted.hidden),
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .clickable(actionStartActivity(openDay(LocalContext.current, LocalDate.now()))),
            )
        }
    }
}

/** «Неделя 7–12 сент.» по крайним дням расписания; без дат — просто «Неделя». */
private fun weekTitle(days: List<DayDto>): String {
    val dates = days.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
    val from = dates.minOrNull()
    val to = dates.maxOrNull()
    return if (from == null || to == null) "Неделя" else formatWeekRange(from, to)
}

/** Шапка с логотипом и группой плюс отступы — то, что списку не достаётся. */
private val HEADER_SPACE = 70.dp

/**
 * Высота строки.
 *
 * Числа сняты с живого экрана, а не выведены из размеров шрифта: у виджета своя
 * разметка, и посчитать её заранее нельзя. Если промахнуться вверх — список
 * оборвётся раньше времени и оставит под собой пустоту; вниз — последнюю строку
 * срежет нижним краем. Обе ошибки видны, поэтому и калибровали по снимку.
 */
private fun rowHeight(row: WeekRow): Dp = when (row) {
    is WeekRow.Title -> 20.dp
    is WeekRow.Lesson -> 17.dp
    is WeekRow.Empty -> 16.dp
}

/** Строка «ещё N пар» под списком. */
private val MORE_ROW = 16.dp

private class Fitted(val days: List<List<WeekRow>>, val hidden: Int)

/**
 * Что поместится, а что уйдёт в «ещё N пар».
 *
 * Заголовок дня, под которым не осталось ни одной строки, отбрасывается: день
 * без содержимого выглядит обрывом, а не днём.
 */
private fun fitWeek(week: List<List<WeekRow>>, free: Dp): Fitted {
    // Сначала пробуем без места под «ещё N»: если неделя влезает целиком, эта
    // строка не нужна, и незачем ради неё выбрасывать последнюю пару.
    val whole = pack(week, free, reserve = 0.dp)
    return if (whole.hidden == 0) whole else pack(week, free, reserve = MORE_ROW)
}

private fun pack(week: List<List<WeekRow>>, free: Dp, reserve: Dp): Fitted {
    var used = reserve
    var full = false
    var hidden = 0
    val out = mutableListOf<List<WeekRow>>()
    for (day in week) {
        val taken = mutableListOf<WeekRow>()
        for (row in day) {
            if (full) {
                if (row is WeekRow.Lesson) hidden++
                continue
            }
            val next = used + rowHeight(row)
            if (next > free) {
                full = true
                if (row is WeekRow.Lesson) hidden++
            } else {
                used = next
                taken += row
            }
        }
        // Заголовок дня, под которым не осталось ни одной строки, — обрыв, а не день.
        if (taken.size > 1) out += taken else taken.forEach { used -= rowHeight(it) }
    }
    return Fitted(out, hidden)
}

@Composable
private fun DayTitle(row: WeekRow.Title, colors: Palette) {
    val context = LocalContext.current
    Text(
        row.title,
        maxLines = 1,
        style = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            // Сегодняшний день выделен цветом, прожитые — приглушены: неделя
            // читается с одного взгляда, без поиска даты глазами.
            color = when {
                row.isToday -> colors.accent
                row.isPast -> colors.textDim
                else -> colors.text
            },
        ),
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 1.dp)
            .clickable(actionStartActivity(openDay(context, row.date))),
    )
}

@Composable
private fun LessonLine(row: WeekRow.Lesson, bells: Map<String, List<String>>, colors: Palette) {
    val context = LocalContext.current
    val lesson = row.lesson
    val dim = row.isPast || lesson.isCancelled

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clickable(actionStartActivity(openDay(context, row.date))),
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
private sealed interface WeekRow {
    val id: Long

    data class Title(
        override val id: Long,
        val date: LocalDate,
        val title: String,
        val isToday: Boolean,
        val isPast: Boolean,
    ) : WeekRow

    data class Lesson(
        override val id: Long,
        val date: LocalDate,
        val lesson: LessonDto,
        val isPast: Boolean,
    ) : WeekRow

    data class Empty(override val id: Long) : WeekRow
}

private fun rowsOf(days: List<DayDto>): List<List<WeekRow>> {
    val today = LocalDate.now()
    val out = mutableListOf<List<WeekRow>>()
    var id = 0L
    for (day in days) {
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: continue
        // Прожитые дни в недельном виджете не показываем: места мало, а к
        // пятнице понедельник занимает верх экрана и вытесняет нужное.
        if (date.isBefore(today)) continue
        val rows = mutableListOf<WeekRow>()
        rows += WeekRow.Title(
            id = id++,
            date = date,
            title = formatWeekDay(date),
            isToday = date == today,
            isPast = false,
        )
        if (day.lessons.isEmpty()) {
            rows += WeekRow.Empty(id++)
        } else {
            day.lessons.forEach { lesson -> rows += WeekRow.Lesson(id++, date, lesson, false) }
        }
        out += rows
    }
    return out
}
