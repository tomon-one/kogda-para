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
import ru.whensclass.data.isKnownWebinar
import ru.whensclass.ui.MainActivity

/**
 * Маленький виджет: одна пара — та, что идёт сейчас, или ближайшая следующая.
 *
 * Нужен потому, что менять размер виджета умеет не всякая оболочка, а места на
 * домашнем экране обычно нет. Здесь ровно один ответ на вопрос «куда идти».
 */
class NextLessonWidget : GlanceAppWidget() {

    // Явно, как у двух других: сюда пишутся «обновляю…» и KEY_TICK.
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Как и у двух других виджетов: чтение хранилища может не удаться, и
        // ронять из-за этого перерисовку незачем — лучше показать подсказку.
        val store = AppContainer.store(context)
        val first = runCatching { store.widgetState() }.getOrNull()

        provideContent {
            // См. ScheduleWidget: прочитанное до provideContent живёт до
            // конца сессии, и updateAll его не обновляет.
            val state by store.widgetStates.collectAsState(initial = first)
            val schedule = ScheduleWidget.parse(state?.scheduleJson)
            val colors = WidgetColors.resolve(context, ThemeChoice.from(state?.theme))
            // Часы — в корне и дальше параметром, как в остальных виджетах:
            // корень перекомпонуется на каждый updateAll, вложенное — нет.
            val now = moment(currentState(ScheduleWidget.KEY_TICK))
            val today = now.toLocalDate()
            val next = nextLesson(schedule, now)
            val lesson = next?.lesson
            val broken = state?.serverBroken == true
            val gone = state?.gone == true

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(colors.background)
                    .cornerRadius(16.dp)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .clickable(
                        // Открываем приложение сразу на том дне, о котором
                        // говорит виджет: иначе после нажатия ещё листать.
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
                            state?.groupName, schedule, today, state?.fetchedAt ?: 0L, broken,
                        ),
                        style = TextStyle(fontSize = 13.sp, color = colors.textDim),
                    )
                    if (state?.groupName == null) {
                        // Группа не выбрана — обновлять нечего: нажатие ведёт в
                        // приложение, а не в «обновлено» (третий аудит, М73
                        // прогона 2).
                        return@Column
                    }
                    // Отклик на ⟳ — как в шапках двух других виджетов: раньше
                    // маленький виджет нажатие не замечал ничем (второй аудит, М17).
                    val busy = refreshing()
                    val failed = currentState(ScheduleWidget.KEY_FAILED) == true
                    val done = currentState(ScheduleWidget.KEY_DONE) == true
                    Text(
                        when {
                            busy -> "обновляю…"
                            failed -> "не вышло ⟳"
                            gone -> "нет в таблице"
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
                        // приложение, к «выбрать заново», как на дневном
                        // виджете (третий аудит, М40 прогона 2).
                        modifier = GlanceModifier.clickable(
                            if (gone) {
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
                // Про завтрашнюю пару тоже говорим: «пар больше нет» слишком
                // легко прочесть как «пар нет вообще» и расслабиться.
                val when_ = when {
                    ongoing -> "идёт сейчас"
                    next.day == today -> "сегодня"
                    next.day == today.plusDays(1) -> "завтра"
                    else -> formatDayTitleShort(next.day)
                }
                // Сбой на сервере — тем же красным, что у остальных виджетов:
                // пара на экране в этот момент из прежнего снимка, и промолчать
                // здесь значит соврать. Первым словом, а не хвостом: в хвосте
                // однострочной шапки «сбой» уходил в многоточие, и оставался
                // красный цвет без причины (третий аудит, В6 прогона 2).
                val status = when {
                    gone -> "нет в таблице · "
                    broken -> "сбой · "
                    else -> ""
                }
                val head = status + nextLessonHead(time, when_, ongoing, lesson.groups)
                // Три строки не влезают в низкую клетку при крупном шрифте, и
                // корпус срезал нижнюю — место. Тогда место — в шапку, а не
                // долой (третий аудит, М26 прогона 2).
                val scale = fontScale()
                val tight = LocalSize.current.height < (TIGHT_HEIGHT_SP * scale + 14).dp
                Text(
                    if (tight) "$head · ${place(lesson, withKind = false)}" else head,
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
                    lesson.subject,
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
                // ведёт на экран пары, где хост назван (третий аудит, М40
                // прогона 1).
                val foreign = lesson.url?.let { !isKnownWebinar(it) } == true
                if (!tight) Text(
                    if (foreign) "⚠ чужой адрес · " + place(lesson).removeSuffix("  ⧉") else place(lesson),
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            foreign -> colors.error
                            lesson.isOnline -> colors.accent
                            else -> colors.text
                        },
                    ),
                    modifier = lesson.url?.takeUnless { foreign }?.let { url ->
                        val context = LocalContext.current
                        GlanceModifier.fillMaxWidth()
                            .clickable(actionStartActivity(CopyLinkActivity.intent(context, url)))
                    } ?: GlanceModifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Пара и день, на который она приходится. */
data class NextLesson(val day: LocalDate, val lesson: LessonDto)

/**
 * Идущая сейчас пара; если её нет — ближайшая из оставшихся сегодня; если и
 * таких нет — первая пара следующего учебного дня.
 */
fun nextLesson(schedule: ScheduleDto?, now: LocalDateTime): NextLesson? {
    if (schedule == null) return null
    val bells = schedule.bells
    val today = now.toLocalDate()
    // Только свои и не отменённые: отменённая пара во время своего слота была
    // «идёт сейчас», а накануне — «завтра · 09:00» зачёркнутой вместо
    // настоящей первой (третий аудит, В1 прогона 2); пара соседней подгруппы
    // выдавалась за свою ближайшую (В5). У преподавателя подпись группы — у
    // каждой пары, там свои все.
    fun mine(lesson: LessonDto) = !lesson.isCancelled && !schedule.isNeighbours(lesson)
    val todayLessons = schedule.days.firstOrNull { it.date == today.toString() }
        ?.lessons.orEmpty().filter(::mine)

    currentLessonNumber(bells, today, now)
        ?.let { number -> todayLessons.firstOrNull { it.number == number } }
        ?.let { return NextLesson(today, it) }

    val time = now.toLocalTime()
    todayLessons.firstOrNull { lesson ->
        val start = parseTime(bells[lesson.number.toString()]?.getOrNull(0))
        start == null || start > time
    }?.let { return NextLesson(today, it) }

    // Сегодня всё — ищем ближайший день, где пары есть.
    for (day in schedule.days) {
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: continue
        if (date <= today) continue
        day.lessons.firstOrNull(::mine)?.let { return NextLesson(date, it) }
    }
    return null
}

/**
 * Что сказать, когда ближайшей пары нет. «Дальше пар нет» — только если лист
 * покрывает неделю вперёд: иначе это «ещё не опубликовано», «устарели» или
 * «сбой», как у дневного виджета. Раньше «дальше пар нет» стояло и в субботу
 * перед неопубликованной неделей — ровно то, от чего чинили 24 сентября
 * (третий аудит, В12 прогона 1).
 */
internal fun noNextLesson(
    groupName: String?,
    schedule: ScheduleDto?,
    today: LocalDate,
    fetchedAt: Long,
    broken: Boolean,
): String {
    if (groupName == null) return "Откройте приложение и выберите свою группу"
    if (schedule == null) return "Расписание не загружено"
    if (broken) return "Сбой: расписание не обновляется"
    if (isStale(fetchedAt)) return "Данные устарели"
    val end = schedule.coverage.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (end == null || end.isBefore(today.plusDays(6))) return "Дальше расписание ещё не опубликовано"
    return "Дальше пар нет"
}

/**
 * Шапка маленького виджета: время — первым. Строка одна и режется справа, и
 * «пн, 28 сентября · 14:20» на узком виджете выходило «пн, 28 сентября ·
 * 1…» — пропадало время, ради которого виджет и смотрят (проверка сборки 82
 * на телефоне, 26.09). «Идёт сейчас» — всё же первым: это главное.
 */
internal fun nextLessonHead(time: String, whenWord: String, ongoing: Boolean, groups: String?): String {
    val core = if (ongoing) "$whenWord · $time" else "$time · $whenWord"
    return groups?.let { "$core · $it" } ?: core
}

/** Три строки маленького виджета — в sp, без отступов. */
private const val TIGHT_HEIGHT_SP = 52

private fun place(lesson: LessonDto, withKind: Boolean = true): String = buildString {
    // Место — первым, тип — после: строка одна и обрезается справа, и
    // «Практика · …» уводила в многоточие номер кабинета (третий аудит, В7
    // прогона 2).
    if (lesson.isOnline) {
        // Значок обещает, что по нажатию скопируется ссылка. Пары без
        // ссылки помечены тем же словом, но нажимать там нечего.
        append(onlineLabel(lesson))
        if (lesson.url != null) append("  ⧉")
    } else {
        append(roomLabel(lesson.room) ?: "не указано")
    }
    if (withKind) kindName(lesson.kind)?.let { append(" · ").append(it) }
}
