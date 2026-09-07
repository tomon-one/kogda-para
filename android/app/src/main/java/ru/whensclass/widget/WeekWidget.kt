package ru.whensclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import java.time.LocalDate
import ru.whensclass.AppContainer
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
                Header(state?.groupName, state?.fetchedAt ?: 0L, colors)
                Spacer(GlanceModifier.height(6.dp))

                val days = schedule?.days.orEmpty()
                when {
                    state?.groupName == null ->
                        Hint("Откройте приложение и выберите свою группу", colors)

                    schedule == null -> Hint("Расписание ещё не загружено", colors)
                    days.isEmpty() -> Hint("На эту неделю расписания нет", colors)
                    else -> Week(days, schedule.bells, colors)
                }
            }
        }
    }
}

@Composable
private fun Header(groupName: String?, fetchedAt: Long, colors: Palette) {
    val context = LocalContext.current
    val today = LocalDate.now()

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                "Неделя",
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
            formatFetchedShort(fetchedAt) + " ⟳",
            maxLines = 1,
            style = TextStyle(
                fontSize = 11.sp,
                color = if (isStale(fetchedAt)) colors.error else colors.textDim,
            ),
            modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

/**
 * Неделя одним списком.
 *
 * Дни и пары идут вперемешку в одном ленивом списке: вложенные списки виджет
 * рисовать не умеет, а прокрутка нужна — шесть дней с парами на экран не
 * помещаются.
 */
@Composable
private fun Week(days: List<DayDto>, bells: Map<String, List<String>>, colors: Palette) {
    val rows = remember(days) { rowsOf(days) }
    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        items(rows, itemId = { it.id }) { row ->
            when (row) {
                is WeekRow.Title -> DayTitle(row, colors)
                is WeekRow.Lesson -> LessonLine(row, bells, colors)
                is WeekRow.Empty -> Text(
                    "пар нет",
                    maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                    modifier = GlanceModifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
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
            .padding(top = 4.dp, bottom = 2.dp)
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
            .padding(bottom = 3.dp)
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
                fontSize = 12.sp,
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

private fun rowsOf(days: List<DayDto>): List<WeekRow> {
    val today = LocalDate.now()
    val out = mutableListOf<WeekRow>()
    for (day in days) {
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: continue
        val past = date.isBefore(today)
        out += WeekRow.Title(
            id = out.size.toLong(),
            date = date,
            title = formatWeekDay(date),
            isToday = date == today,
            isPast = past,
        )
        if (day.lessons.isEmpty()) {
            out += WeekRow.Empty(out.size.toLong())
        } else {
            day.lessons.forEach { lesson ->
                out += WeekRow.Lesson(out.size.toLong(), date, lesson, past)
            }
        }
    }
    return out
}
