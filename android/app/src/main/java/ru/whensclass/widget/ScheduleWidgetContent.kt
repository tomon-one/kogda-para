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
import androidx.glance.text.TextStyle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import ru.whensclass.R
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.sheetLink
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
    now: LocalDateTime,
    day: LocalDate,
    offset: Int,
    busy: Boolean = false,
    done: Boolean = false,
    failed: Boolean = false,
    serverBroken: Boolean = false,
    gone: Boolean = false,
    sourceUrl: String? = null,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    // Оболочки вроде Nova дают сжать виджет ниже объявленного минимума. Ругаться
    // на это некому — просто убираем то, без чего можно, начиная с логотипа.
    // Узко — и от крупного шрифта: время в sp, колонка в dp, и конец пары
    // уходил в многоточие.
    val scale = fontScale()
    val fit = Fit(narrow = size.width < 220.dp * scale, dense = size.height < 120.dp, scale = scale)

    Column(
        modifier = modifier
            .fillMaxSize()
            // Нажатие по пустому месту открывает приложение. Стрелки,
            // обновление и копирование ссылки перехватывают своё сами:
            // у Glance ближний обработчик выигрывает у дальнего.
            .clickable(actionStartActivity(openDay(context, day)))
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
            millisOf(now),
            firstOffset(schedule, now.toLocalDate()),
            lastOffset(schedule, now.toLocalDate()),
            busy,
            done,
            failed,
            serverBroken,
            gone,
            fit,
            colors,
        )
        Spacer(GlanceModifier.height(if (fit.dense) 3.dp else 6.dp))

        val today = schedule?.days?.firstOrNull { it.date == day.toString() }
        when {
            // Обновлять нечего: нажатие ведёт в приложение, а не в
            // «обновлено».
            groupName == null -> MissingHint(
                "Откройте приложение и выберите группу или себя", colors,
                open = actionStartActivity(openDay(context, day)),
            )
            schedule == null -> MissingHint("Расписание ещё не загружено", colors)
            today == null -> {
                val missing = missingDay(
                    schedule, day, serverBroken, checked = checkedToday(fetchedAt, now.toLocalDate()),
                )
                // К своей колонке и к этому дню, а не в книгу целиком.
                val link = sheetLink(schedule, day, sourceUrl)
                MissingHint(
                    missing.text, colors, if (missing.toSource) link else null,
                    // Выходной — не повод «нажмите, чтобы обновить».
                    open = if (missing.off) actionStartActivity(openDay(context, day)) else null,
                )
            }
            // Свободный день: подсказка «нажмите, чтобы обновить» читалась как
            // «не загрузилось» — нажатие ведёт в приложение.
            today.lessons.isEmpty() -> MissingHint(
                "Пар нет", colors, open = actionStartActivity(openDay(context, day)),
            )
            else -> Lessons(
                today.lessons,
                schedule.bells,
                day,
                now,
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
internal fun lastOffset(schedule: ScheduleDto?, today: LocalDate): Int =
    offsets(schedule, today).maxOrNull()?.coerceIn(0, ScheduleWidget.MAX_OFFSET) ?: 0

/**
 * Насколько далеко назад есть данные.
 *
 * Расписание приходит с понедельника: прожитые дни уже лежат на телефоне, и
 * запрещать их листать незачем — на экране приложения они тоже остаются.
 */
internal fun firstOffset(schedule: ScheduleDto?, today: LocalDate): Int =
    offsets(schedule, today).minOrNull()?.coerceIn(-ScheduleWidget.MAX_OFFSET, 0) ?: 0

private fun offsets(schedule: ScheduleDto?, today: LocalDate): List<Int> {
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
    nowMillis: Long,
    firstDay: Int,
    lastDay: Int,
    busy: Boolean,
    done: Boolean,
    failed: Boolean,
    serverBroken: Boolean,
    gone: Boolean,
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
                // По логотипу тоже открывается приложение: он выглядит как
                // кнопка, и нажимали на него именно с этим ожиданием.
                modifier = GlanceModifier
                    .size(width = 26.dp, height = 14.dp)
                    .clickable(openApp),
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
                    // Имя уступает: статус и ⟳ меряются первыми. Раньше имя
                    // шло без веса, и на узком виджете «нет в таблице»
                    // резалось, а ⟳ пропадал вовсе. Своё имя и так знают наизусть.
                    modifier = GlanceModifier.defaultWeight().clickable(openApp),
                )
                // Время последней проверки — служебная мелочь, поэтому тем же
                // приглушённым цветом; краснеет, только когда данные протухли.
                Text(
                    when {
                        busy -> " · обновление…"
                        failed -> " · не обновилось"
                        // Сбой и пропажа группы — раньше «обновлено»: ответ
                        // сервера пришёл, но расписание в нём прежнее, и
                        // «обновлено» на секунду перед «сбой» читалось как
                        // «всё в порядке» (учебная тревога 14 сентября).
                        // Группы в таблице больше нет: нажатие ведёт в
                        // приложение, где плашка и кнопка «выбрать заново».
                        gone -> " · нет в таблице"
                        // Сбой на сервере надо показывать и тогда, когда пары
                        // на экране есть: снимок в этот момент прежний, а не
                        // сегодняшний. Раньше про сбой говорила только надпись
                        // вместо дня — то есть лишь на краю листа, а в середине
                        // недели виджет молчал, пока экран уже говорил.
                        // Коротко: в шапке тесно, а «у нас» договаривает плашка
                        // в приложении.
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
                    modifier = GlanceModifier.clickable(
                        if (gone) openApp else actionRunCallback<RefreshAction>(),
                    ),
                )
                // Отдельной строкой, а не хвостом времени: внутри одного текста
                // значок вставал по базовой линии букв и висел выше цифр. Размер
                // тот же, что у времени, и показывается он всегда — пропадая на
                // время запроса, он менял высоту строки, и виджет подпрыгивал.
                Text(
                    " ⟳",
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = when {
                            failed -> colors.error
                            busy || done -> colors.accent
                            else -> colors.textDim
                        },
                    ),
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
                )
            }
        }
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


/** Насколько тесно виджету — от этого зависит, что показывать. */
internal data class Fit(val narrow: Boolean, val dense: Boolean, val scale: Float = 1f)

/** Приложение открывается на том же дне, что показывает виджет. */
internal fun openDay(context: android.content.Context, day: LocalDate): Intent =
    Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_DAY, day.toString())
        // CLEAR_TOP, а не один NEW_TASK: без него каждое нажатие на виджет
        // клало в стек ещё одну копию экрана, и «назад» пришлось бы жать
        // столько раз, сколько раз человек за день заглянул в виджет. И
        // SINGLE_TOP: без него CLEAR_TOP уничтожал живой экран и создавал
        // заново.
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )


/**
 * Во сколько раз система увеличила шрифт.
 *
 * Бюджет «сколько строк влезет» считается в dp, а текст в этих строках задан в
 * sp и растёт вместе с системным размером шрифта. Считая по неизменным
 * константам, виджет полагал, что помещается больше, чем помещалось на самом
 * деле, — и нижние строки обрезались корпусом. Прокрутки в виджете нет,
 * обрезанное просто пропадает.
 *
 * Снизу единица: уменьшенный шрифт лишних строк не даёт, зато пустоты добавит.
 */
@Composable
internal fun fontScale(): Float =
    LocalContext.current.resources.configuration.fontScale.coerceAtLeast(1f)
