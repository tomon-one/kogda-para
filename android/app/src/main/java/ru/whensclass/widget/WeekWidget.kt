package ru.whensclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.currentState
import androidx.glance.LocalSize
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
                Header(
                    state?.groupName,
                    state?.fetchedAt ?: 0L,
                    currentState(ScheduleWidget.KEY_BUSY) == true,
                    colors,
                )
                Spacer(GlanceModifier.height(6.dp))

                val days = schedule?.days.orEmpty()
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
private fun Header(groupName: String?, fetchedAt: Long, busy: Boolean, colors: Palette) {
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
            if (busy) "обновляю…" else formatFetchedShort(fetchedAt),
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
        Spacer(GlanceModifier.width(6.dp))
        RefreshButton(busy, colors)
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
    val context = LocalContext.current
    val rows = remember(days) { rowsOf(days) }
    val height = LocalSize.current.height
    val shown = remember(rows, height) { fitRows(rows, height - HEADER_SPACE) }
    val hidden = rows.drop(shown.size).count { it is WeekRow.Lesson }

    Column(modifier = modifier) {
        shown.forEach { row ->
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
        if (hidden > 0) {
            Text(
                morePairs(hidden),
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .clickable(actionStartActivity(openDay(context, LocalDate.now()))),
            )
        }
    }
}

/** Шапка со строкой группы и отступы — то, что списку не достаётся. */
private val HEADER_SPACE = 46.dp

/**
 * Высота строки на глаз: точной разметки виджет не сообщает.
 *
 * Числа занижены намеренно. Ошибка вверх обрывает список раньше времени и
 * оставляет под ним пустоту — это видно и злит; ошибка вниз лишь прижимает
 * последнюю строку к краю.
 */
private fun rowHeight(row: WeekRow): Dp = when (row) {
    is WeekRow.Title -> 20.dp
    is WeekRow.Lesson -> 18.dp
    is WeekRow.Empty -> 16.dp
}

/**
 * Сколько строк поместится.
 *
 * Под «ещё N» оставляется место заранее, иначе строка вытеснила бы последнюю
 * пару и соврала бы на единицу. Заголовок дня, под которым не осталось ни
 * одной пары, отбрасывается: день без содержимого выглядит обрывом.
 */
private fun fitRows(rows: List<WeekRow>, free: Dp): List<WeekRow> {
    var used = 14.dp
    var count = 0
    for (row in rows) {
        val next = used + rowHeight(row)
        if (next > free) break
        used = next
        count++
    }
    while (count > 0 && rows[count - 1] is WeekRow.Title) count--
    return rows.take(count)
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
        // Прожитые дни в недельном виджете не показываем: места мало, а к
        // пятнице понедельник занимает верх экрана и вытесняет нужное.
        if (date.isBefore(today)) continue
        val past = false
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
