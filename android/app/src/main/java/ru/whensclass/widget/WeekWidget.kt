package ru.whensclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.LocalDateTime
import ru.whensclass.AppContainer
import ru.whensclass.R
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.sheetLink
import ru.whensclass.ui.daysWithGaps

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
        val store = AppContainer.get(context).store
        val first = runCatching { store.widgetState() }.getOrNull()

        provideContent {
            // См. ScheduleWidget: читать хранилище до provideContent мало —
            // прочитанное живёт до конца сессии и не меняется от updateAll.
            val state by store.widgetStates.collectAsState(initial = first)
            val schedule = ScheduleWidget.parse(state?.scheduleJson)
            val colors = WidgetColors.resolve(context, ThemeChoice.from(state?.theme))
            // Часы — только здесь. Этот корень перекомпонуется на каждый
            // updateAll (звонок, полночь) и на каждое изменение хранилища,
            // а вложенные функции — лишь когда меняются их параметры.
            // Разобранное расписание кэшируется по тексту, палитра одна на
            // всех, и Week с теми же входами Compose пропускал целиком: 14
            // сентября 2026 виджет с 15:50 до ночи подсвечивал кончившуюся
            // пару при честном времени в шапке. Момент идёт параметром вниз.
            val now = moment(currentState(ScheduleWidget.KEY_TICK))
            val today = now.toLocalDate()
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    // Нажатие по пустому месту открывает приложение —
                    // так же, как по дню или по шапке.
                    .clickable(actionStartActivity(openDay(context, today)))
                    .background(colors.background)
                    .cornerRadius(16.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                // С дырами, как на экране: воскресенье и будень без строки
                // внутри покрытия — «выходной», а не пропуск без слова.
                val days = schedule?.let { daysWithGaps(it) }.orEmpty()
                Header(
                    days,
                    today,
                    state?.groupName,
                    state?.fetchedAt ?: 0L,
                    millisOf(now),
                    refreshing(),
                    currentState(ScheduleWidget.KEY_DONE) == true,
                    currentState(ScheduleWidget.KEY_FAILED) == true,
                    state?.serverBroken == true,
                    state?.gone == true,
                    colors,
                )
                Spacer(GlanceModifier.height(6.dp))

                when {
                    // Обновлять нечего — нажатие ведёт в приложение.
                    state?.groupName == null -> MissingHint(
                        "Откройте приложение и выберите группу или себя", colors,
                        open = actionStartActivity(openDay(context, today)),
                    )

                    schedule == null -> MissingHint("Расписание ещё не загружено", colors)
                    // Проверяем то, что рисуется, а не то, что пришло: дни
                    // старше сегодняшнего виджет выбрасывает, и при непустом
                    // days под шапкой оставалась пустота без единого слова.
                    // Пустая неделя — как пустой день у дневного виджета: сбой,
                    // устаревшие данные или «не опубликовано» с выходом к таблице.
                    // Раньше всегда «нажмите на время в шапке», хотя при сбое и
                    // при неопубликованном листе обновление не поможет.
                    weekDays(days, today).isEmpty() -> {
                        val missing = missingDay(
                            schedule, today, state?.fetchedAt ?: 0L,
                            state?.serverBroken == true, week = true,
                        )
                        MissingHint(
                            missing.text, colors,
                            if (missing.toSource) sheetLink(schedule, today, state?.sourceUrl) else null,
                            open = if (missing.off) actionStartActivity(openDay(context, today)) else null,
                        )
                    }
                    // Долю высоты список получает здесь, из Column:
                    // без неё он в некоторых оболочках схлопывается в
                    // ноль, и под шапкой остаётся пустота.
                    else -> Week(
                        days,
                        schedule.bells,
                        now,
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
    today: LocalDate,
    groupName: String?,
    fetchedAt: Long,
    nowMillis: Long,
    busy: Boolean,
    done: Boolean,
    failed: Boolean,
    serverBroken: Boolean,
    gone: Boolean,
    colors: Palette,
) {
    val context = LocalContext.current
    val openApp = actionStartActivity(openDay(context, today))

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
                .clickable(openApp),
        )
        Spacer(GlanceModifier.width(6.dp))

        // Статус — второй строкой, рядом с группой, как в дневном виджете.
        // В одной строке с заголовком он побеждал: «Неделя 28 сент. – …».
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                weekTitle(days, today),
                maxLines = 1,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                ),
                modifier = GlanceModifier.fillMaxWidth().clickable(openApp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    groupName?.let { if (it.count { c -> c == ' ' } >= 2) shortenName(it) else it }
                        .orEmpty(),
                    maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                    modifier = GlanceModifier.defaultWeight().clickable(openApp),
                )
                Text(
                    when {
                        busy -> " · обновление…"
                        failed -> " · не обновилось"
                        // Сбой и пропажа группы — раньше «обновлено»: см. дневной виджет.
                        gone -> " · нет в таблице"
                        // О сбое на сервере говорят все три виджета, а не только
                        // дневной: неделя на экране в этот момент прежняя.
                        serverBroken -> " · сбой"
                        done -> " · обновлено"
                        else -> " · " + formatFetchedShort(fetchedAt, nowMillis)
                    },
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = when {
                            failed || serverBroken || gone || isStale(fetchedAt, nowMillis) -> colors.error
                            busy || done -> colors.accent
                            else -> colors.textDim
                        },
                    ),
                    // Группы нет в таблице — обновление ничего не даст: в
                    // приложение, к «выбрать заново», как на дневном
                    // виджете.
                    modifier = GlanceModifier.clickable(
                        if (gone) openApp else actionRunCallback<RefreshAction>(),
                    ),
                )
                // Всегда, а не кроме «обновляю»: пропадая, он менял ширину
                // строки, и статус прыгал.
                Text(
                    " ↻",
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = if (busy || done) colors.accent else colors.textDim,
                    ),
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
                )
            }
        }
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
    // Даже строки-сводки помещаются не всегда: семь дней или крупный шрифт —
    // и последний день обрезал корпус, хотя обещано «день не пропадает никогда».
    // Тогда хвост недели — одной строкой «и ещё N дней».
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

    // Каждый день — свой контейнер. Плоским списком дни рисоваться не могут:
    // разметка виджета собрана заранее и вмещает не больше десяти детей.
    Column(modifier = modifier) {
        week.forEachIndexed { index, day ->
            if (index < open) {
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    DayTitle(day, colors)
                    if (day.lessons.isEmpty()) {
                        EmptyLine(day.date, colors, day.absent)
                    } else {
                        // Заголовок дня уже занял одного ребёнка, и если пар
                        // окажется больше девяти, всё сверх десятого молча
                        // пропадёт — так когда-то обрывалась неделя на середине
                        // четверга. Девяти пар в дне не бывает, но с включённой
                        // соседней подгруппой их и правда становится до
                        // двенадцати: свои шесть и чужие шесть, когда кабинеты
                        // расходятся. Тогда последняя строка говорит, сколько
                        // не поместилось, вместо того чтобы исчезнуть молча.
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
 * этот день: без своего нажатия оно уходило корню, к сегодняшнему.
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

/** «Неделя 7–12 сент.» по крайним дням расписания; без дат — просто «Неделя». */
internal fun weekTitle(days: List<DayDto>, today: LocalDate): String {
    // Считаем по тем дням, что видны: прожитые виджет не показывает, и «7–12»
    // над списком, который начинается со вторника, сбивает с толку.
    val dates = days
        .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        .filterNot { it.isBefore(today) || it.isAfter(today.plusDays(WEEK_AHEAD)) }
    val from = dates.minOrNull()
    val to = dates.maxOrNull()
    return if (from == null || to == null) "Неделя" else formatWeekRange(from, to)
}

/** Недельный виджет — сегодня и шесть дней вперёд. */
internal const val WEEK_AHEAD = 6L

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

/** Раскладка недели: [open] ближайших дней парами, [shown] дней всего, остальные — «и ещё N дней». */
internal data class WeekFit(val open: Int, val shown: Int)

/**
 * Сколько ближайших дней показать парами и сколько дней — вообще.
 *
 * Раньше дни рисовались подряд, пока не кончится высота, а остаток уходил в
 * «ещё N пар». Для лёгкой недели это работало, для тяжёлой — нет: у самого
 * загруженного преподавателя 31 пара за шесть дней, и он видел бы два дня из
 * шести, не подозревая об остальных.
 *
 * Поэтому день не пропадает никогда: не хватило места на пары — остаётся строка
 * со сводкой, не хватило и на сводки — «и ещё N дней». Разворачиваются
 * ближайшие дни: дальние всё равно уточняют в приложении.
 *
 * Сегодняшний — даже ценой хвоста недели в «и ещё N дней»: сводки всех семи
 * дней резервировались первыми, и в понедельник–среду виджет объявленной
 * высоты не разворачивал ни одного дня — ни пар, ни подсветки идущей.
 * Пустой сегодняшний день ради «пар нет»
 * хвост не сворачивает — сводка скажет то же. [heights] — высота каждого дня
 * парами.
 */
internal fun fitWeek(
    heights: List<Float>,
    free: Float,
    summary: Float,
    firstHasLessons: Boolean = true,
): WeekFit {
    val n = heights.size
    // Сколько дней всего поместится, если первые [open] развернуть: остальные
    // — сводками, а не влезли все — со строкой «и ещё N дней». null — не
    // влезают и развёрнутые.
    fun shown(open: Int): Int? {
        val used = heights.take(open).sum()
        if (used + (n - open) * summary <= free) return n
        val room = ((free - used - summary) / summary).toInt()
        return if (room < 0) null else open + room.coerceAtMost(n - open - 1)
    }
    val whole = (n downTo 0).firstOrNull { shown(it) == n }
    return when {
        whole != null && whole > 0 -> WeekFit(whole, n)
        n > 0 && firstHasLessons && shown(1) != null -> WeekFit(1, shown(1)!!)
        whole != null -> WeekFit(0, n)
        else -> WeekFit(0, (shown(0) ?: 0).coerceAtLeast(1))
    }
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
                if (day.absent) "  выходной" else "  пар нет"
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
        // Подложка у идущей пары — как в дневном виджете. Отступ снаружи неё,
        // иначе подсветка съезжает вниз и задевает соседнюю строку.
        // Подложка идущей пары кладётся первой, поэтому накрывает строку
        // целиком вместе с отступом под ней, а не только высоту букв.
        //
        // Фон задаётся всегда, у остальных строк — прозрачный. Раньше его
        // навешивали условно, и у строки, переставшей быть идущей, модификатора
        // фона не было вовсе: в RemoteViews не уходило ни одной команды про
        // фон, лаунчер накатывал их на прежние вьюхи — и подложка оставалась. К
        // вечеру были выделены все пары дня (копилка, 16 сентября 2026).
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
            // Колонка растёт со шрифтом: «09:00» в sp, колонка в dp, и с
            // крупным шрифтом видно было «09:…».
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
        // серого за секунду не видно, и оставался номер кабинета, куда идти не
        // надо (разбор текстов 27.09).
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

/** Строка списка: заголовок дня, пара или отметка о пустом дне. */
/** День недели в виджете: заголовок и пары, если они есть. */
private class WeekDay(
    val date: LocalDate,
    val title: String,
    val isToday: Boolean,
    val lessons: List<LessonDto>,
    val absent: Boolean = false,
)

private fun weekDays(days: List<DayDto>, today: LocalDate): List<WeekDay> {
    return days.mapNotNull { day ->
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
        // Прожитые дни в недельном виджете не показываем: места мало, а к
        // пятнице понедельник занимает верх экрана и вытесняет нужное.
        if (date.isBefore(today)) return@mapNotNull null
        // И не дальше недели вперёд: на телефоне теперь две недели, а
        // виджет — «Неделя» (27.09).
        if (date.isAfter(today.plusDays(WEEK_AHEAD))) return@mapNotNull null
        WeekDay(date, formatWeekDay(date), date == today, day.lessons, day.absent)
    }
}
