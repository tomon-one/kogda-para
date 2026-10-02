package ru.whensclass.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import java.time.LocalDate
import java.time.LocalDateTime
import ru.whensclass.AppContainer
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.UnreadDay
import ru.whensclass.data.isKnownWebinar
import ru.whensclass.ui.MainActivity

/**
 * Маленький виджет: одна пара — та, что идёт сейчас, или ближайшая следующая.
 * Для тех, у кого на домашнем экране мало места, а оболочка не меняет размер
 * виджетов.
 */
class NextLessonWidget : GlanceAppWidget() {

    // Явно, как у двух других: сюда пишутся «обновляю…» и KEY_TICK.
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Неудачное чтение хранилища — не повод ронять перерисовку.
        val store = AppContainer.store(context)
        val first = runCatching { store.widgetState() }.getOrNull()

        provideContent {
            // Потоком, а не разовым чтением: см. [ScheduleStore.widgetStates].
            val state by store.widgetStates.collectAsState(initial = first)
            val schedule = ScheduleWidget.parse(state?.scheduleJson)
            val colors = WidgetColors.resolve(context, ThemeChoice.from(state?.theme))
            // Часы — в корне и дальше параметром: корень перекомпонуется на
            // каждую перерисовку, вложенное — нет.
            val now = moment(currentState(ScheduleWidget.KEY_TICK))
            val today = now.toLocalDate()
            val next = nextLesson(schedule, now)
            val lesson = next?.lesson
            val broken = state?.serverBroken == true
            val gone = state?.gone == true
            val unsupported = state?.unsupported == true

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(colors.background)
                    .cornerRadius(16.dp)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .clickable(
                        // Приложение — сразу на том дне, о котором говорит виджет.
                        actionStartActivity(
                            Intent(LocalContext.current, MainActivity::class.java)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                )
                                .putExtra(MainActivity.EXTRA_DAY, next?.day?.toString()),
                        ),
                    ),
            ) {
                if (lesson == null || next == null) {
                    Text(
                        noNextLesson(
                            state?.groupName, schedule, today, broken,
                            checked = checkedToday(state?.fetchedAt ?: 0L, today),
                            unsupported = unsupported,
                        ),
                        style = TextStyle(fontSize = 13.sp, color = colors.textDim),
                    )
                    if (state?.groupName == null) {
                        // Группа не выбрана — обновлять нечего.
                        return@Column
                    }
                    // Отклик на ⟳ — как в шапках двух других виджетов.
                    val busy = refreshing()
                    val failed = currentState(ScheduleWidget.KEY_FAILED) == true
                    val done = currentState(ScheduleWidget.KEY_DONE) == true
                    Text(
                        when {
                            busy -> "обновление…"
                            failed -> "не обновилось ⟳"
                            gone -> "нет в таблице"
                            unsupported -> "обновите приложение"
                            broken -> "сбой ⟳"
                            done -> "обновлено"
                            else -> formatFetchedShort(state?.fetchedAt ?: 0L) + " ⟳"
                        },
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = when {
                                failed || broken || gone -> colors.error
                                busy || done -> colors.accent
                                else -> colors.textDim
                            },
                        ),
                        // Группы нет в таблице — обновление ничего не даст: в
                        // приложение, к «выбрать заново».
                        modifier = GlanceModifier.clickable(
                            if (gone || unsupported) {
                                actionStartActivity(openDay(LocalContext.current, today))
                            } else {
                                actionRunCallback<RefreshAction>()
                            },
                        ),
                    )
                    return@Column
                }

                val bells = schedule?.bells.orEmpty()
                val ongoing = next.day == today && currentLessonNumber(bells, today, now) == lesson.number
                val time = lessonStart(bells, lesson.number) ?: "${lesson.number} пара"
                // Про завтрашнюю пару тоже говорим: «пар больше нет» легко
                // прочесть как «пар нет вообще».
                val when_ = when {
                    ongoing -> "идёт сейчас"
                    next.day == today -> "сегодня"
                    next.day == today.plusDays(1) -> "завтра"
                    else -> formatDayTitleShort(next.day)
                }
                // Сбой и пропажа — красным и первым словом: в хвосте
                // однострочной шапки причина ушла бы в многоточие. Старые данные
                // без сбоя (телефон долго без сети) не красные.
                val status = when {
                    gone -> "нет в таблице · "
                    unsupported -> "обновите приложение · "
                    broken -> "сбой · "
                    next.unreadOn == next.day -> "не прочитан · "
                    next.unreadOn != null -> formatDayTitleShort(next.unreadOn).substringBefore(",") +
                        " не прочитан · "
                    else -> ""
                }
                // В низкой клетке при крупном шрифте три строки не влезают:
                // тогда место пары переезжает в шапку.
                val scale = fontScale()
                val tight = LocalSize.current.height < (TIGHT_HEIGHT_SP * scale + 14).dp
                val head = status + nextLessonHead(
                    time, when_, ongoing, lesson.groups,
                    // Без «⧉»: нажатие на шапку открывает приложение, а не копирует.
                    place = if (tight) places(next) else null,
                )
                Text(
                    head,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = when {
                            broken || gone -> colors.error
                            ongoing -> colors.accent
                            else -> colors.textDim
                        },
                    ),
                )
                Text(
                    distinctSubjects(next.lessons).joinToString(" / "),
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.text,
                        textDecoration =
                        if (lesson.isCancelled) TextDecoration.LineThrough else null,
                    ),
                    modifier = GlanceModifier.fillMaxWidth(),
                )
                // Ссылка на чужой адрес не копируется одним нажатием: нажатие
                // ведёт на экран пары, где хост назван.
                val foreign = lesson.url?.let { !isKnownWebinar(it) } == true
                // Две пары одного номера — оба места, без копирования ссылки:
                // нажатие открывает день, где у каждой пары своя.
                val pair = next.also.isNotEmpty()
                if (!tight) Text(
                    when {
                        pair -> places(next)
                        foreign -> "⚠ чужая ссылка · " + place(lesson).removeSuffix("  ⧉")
                        else -> place(lesson)
                    },
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            pair -> colors.text
                            foreign -> colors.error
                            lesson.isOnline -> colors.accent
                            else -> colors.text
                        },
                    ),
                    modifier = lesson.url?.takeUnless { foreign || pair }?.let { url ->
                        val context = LocalContext.current
                        GlanceModifier.fillMaxWidth()
                            .clickable(actionStartActivity(CopyLinkActivity.intent(context, url)))
                    } ?: GlanceModifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Пара и день, на который она приходится. [also] — другие пары того же номера:
 * блок пополам, две пары в разных кабинетах, и какая из них его, неизвестно.
 */
data class NextLesson(
    val day: LocalDate,
    val lesson: LessonDto,
    val also: List<LessonDto> = emptyList(),
    /**
     * Ближайший день до этой пары включительно, который сервер не прочитал:
     * у её дня пары прежние, у дня раньше — пар может не хватать.
     */
    val unreadOn: LocalDate? = null,
) {
    val lessons: List<LessonDto> get() = listOf(lesson) + also
}

/**
 * Идущая сейчас пара; если её нет — ближайшая из оставшихся сегодня; если и
 * таких нет — первая пара следующего учебного дня.
 */
fun nextLesson(schedule: ScheduleDto?, now: LocalDateTime): NextLesson? {
    val next = findNext(schedule ?: return null, now) ?: return null
    val today = now.toLocalDate()
    val unreadOn = schedule.days.filter { it.unread != null }
        .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        .filter { !it.isBefore(today) && !it.isAfter(next.day) }
        .minOrNull()
    return next.copy(unreadOn = unreadOn)
}

private fun findNext(schedule: ScheduleDto, now: LocalDateTime): NextLesson? {
    val bells = schedule.bells
    val today = now.toLocalDate()
    // Только свои и не отменённые: иначе ближайшей оказалась бы отменённая
    // пара или пара соседней подгруппы. У преподавателя свои все.
    fun mine(lesson: LessonDto) = !lesson.isCancelled && !schedule.isNeighbours(lesson)
    val todayLessons = schedule.days.firstOrNull { it.date == today.toString() }
        ?.lessons.orEmpty().filter(::mine)

    fun found(date: LocalDate, lesson: LessonDto, day: List<LessonDto>) =
        NextLesson(date, lesson, day.filter { it !== lesson && it.number == lesson.number })

    currentLessonNumber(bells, today, now)
        ?.let { number -> todayLessons.firstOrNull { it.number == number } }
        ?.let { return found(today, it, todayLessons) }

    val time = now.toLocalTime()
    todayLessons.firstOrNull { lesson ->
        val start = parseTime(bells[lesson.number.toString()]?.getOrNull(0))
        start == null || start > time
    }?.let { return found(today, it, todayLessons) }

    // Сегодня всё — ищем ближайший день, где пары есть.
    for (day in schedule.days) {
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: continue
        if (date <= today) continue
        val lessons = day.lessons.filter(::mine)
        lessons.firstOrNull()?.let { return found(date, it, lessons) }
    }
    return null
}

/**
 * Что сказать, когда ближайшей пары нет. «Пар нет» — только если лист
 * покрывает неделю вперёд: иначе это «ещё не опубликовано» или «сбой», как у
 * дневного виджета.
 */
internal fun noNextLesson(
    groupName: String?,
    schedule: ScheduleDto?,
    today: LocalDate,
    broken: Boolean,
    /** Телефон проверял сервер сегодня: иначе «не опубликовано» утверждать нечем. */
    checked: Boolean = true,
    /** Сервер эту сборку не обслуживает (426). */
    unsupported: Boolean = false,
): String {
    // «…группу или себя»: виджет ставит и преподаватель.
    if (groupName == null) return "Откройте приложение и выберите группу или себя"
    if (schedule == null) return "Расписание не загружено"
    if (unsupported) return "Нужно обновить приложение"
    if (broken) return "Сбой: расписание не обновляется"
    // Пар впереди нет, но день сервер не прочитал — это не «пар нет».
    schedule.days.firstOrNull { day ->
        day.unread == UnreadDay.MISSING &&
            runCatching { !LocalDate.parse(day.date).isBefore(today) }.getOrDefault(false)
    }?.let { return "Сервер не прочитал день: " + formatDayTitleShort(LocalDate.parse(it.date)) }
    val end = schedule.coverage.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (end == null || end.isBefore(today.plusDays(6))) {
        return if (checked) "Дальше расписание ещё не опубликовано" else "Дальше расписание не загружено"
    }
    // Пары ищутся только в скачанной неделе, а лист может идти дальше: честно
    // — до какого дня их нет.
    val last = schedule.days.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.maxOrNull()
    if (last == null || last.isBefore(today)) return "Расписание на эти дни не загружено"
    return "Пар нет по ${formatWeekDay(last)}"
}

/**
 * Шапка маленького виджета: время — первым, потому что строка одна и режется
 * справа. «Идёт сейчас» — всё же первым: это главное.
 */
internal fun nextLessonHead(
    time: String,
    whenWord: String,
    ongoing: Boolean,
    groups: String?,
    place: String? = null,
): String {
    // [place] — только в тесной клетке, где третьей строки нет: место сразу
    // за временем, «сегодня» не пишется; у идущей пары — «идёт» и место:
    // время начала опоздавшему ни к чему.
    if (place != null) {
        val parts = if (ongoing) {
            listOf("идёт", place)
        } else {
            listOfNotNull(time, place, whenWord.takeUnless { it == "сегодня" })
        }
        return (parts + listOfNotNull(groups)).joinToString(" · ")
    }
    val core = if (ongoing) "$whenWord · $time" else "$time · $whenWord"
    return listOfNotNull(core, groups).joinToString(" · ")
}

/** Три строки маленького виджета — в sp, без отступов. */
private const val TIGHT_HEIGHT_SP = 52

/** Места всех пар номера без вида: «каб. 55/1 / каб. 467». */
internal fun places(next: NextLesson): String =
    next.lessons.joinToString(" / ") { place(it, withKind = false).removeSuffix("  ⧉") }

private fun place(lesson: LessonDto, withKind: Boolean = true): String = buildString {
    // Замена — первым словом: другой предмет без пометки похож на ошибку виджета.
    if (lesson.replaces != null && !lesson.isCancelled) append("замена · ")
    // Место — первым, тип — после: строка обрезается справа.
    if (lesson.isOnline) {
        // Значок обещает копирование ссылки — только когда она есть.
        append(onlineLabel(lesson))
        if (lesson.url != null) append("  ⧉")
    } else {
        append(roomLabel(lesson.room) ?: "место не указано")
    }
    if (withKind) kindName(lesson.kind)?.let { append(" · ").append(it) }
}
