package ru.whensclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.Image
import androidx.glance.ColorFilter
import androidx.glance.LocalContext
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
import androidx.glance.text.TextStyle
import java.time.LocalDate
import ru.whensclass.AppContainer
import ru.whensclass.R
import ru.whensclass.data.DayDto
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
                    // «не загружено» или «не опубликовано» с выходом к таблице.
                    // Раньше всегда «нажмите на время в шапке», хотя при сбое и
                    // при неопубликованном листе обновление не поможет.
                    weekDays(days, today).isEmpty() -> {
                        val missing = missingDay(
                            schedule, today, state?.serverBroken == true, week = true,
                            checked = checkedToday(state?.fetchedAt ?: 0L, today),
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
                            failed || serverBroken || gone -> colors.error
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
