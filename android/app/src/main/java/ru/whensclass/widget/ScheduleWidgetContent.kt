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
 * Содержимое дневного виджета — отдельно от [ScheduleWidget], чтобы его можно
 * было отрисовать при отладке без установки на телефон.
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
    unsupported: Boolean = false,
    sourceUrl: String? = null,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    // Оболочки вроде Nova дают сжать виджет ниже объявленного минимума —
    // тогда убираем то, без чего можно, начиная с логотипа. Узость считается
    // с учётом крупного шрифта: время в sp, колонка в dp.
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
            unsupported,
            // Пары дня прежние или неполные: сервер его не прочитал. Пустой
            // такой день говорит это строкой ниже.
            schedule?.days?.firstOrNull { it.date == day.toString() }
                ?.let { it.unread != null && it.lessons.isNotEmpty() } == true,
            fit,
            colors,
        )
        Spacer(GlanceModifier.height(if (fit.dense) 3.dp else 6.dp))

        val today = schedule?.days?.firstOrNull { it.date == day.toString() }
        when {
            // Обновлять нечего: нажатие ведёт в приложение.
            groupName == null -> MissingHint(
                "Откройте приложение и выберите группу или себя", colors,
                open = actionStartActivity(openDay(context, day)),
            )
            schedule == null -> MissingHint("Расписание ещё не загружено", colors)
            today == null -> {
                val missing = missingDay(
                    schedule, day, serverBroken, checked = checkedToday(fetchedAt, now.toLocalDate()),
                    unsupported = unsupported,
                )
                // К своей колонке и к этому дню, а не в книгу целиком.
                val link = sheetLink(schedule, day, sourceUrl)
                MissingHint(
                    missing.text, colors, if (missing.toSource) link else null,
                    // Выходной — не повод «нажмите, чтобы обновить».
                    open = if (missing.toApp) actionStartActivity(openDay(context, day)) else null,
                )
            }
            // Сервер день не прочитал, прежних пар нет — это не «пар нет».
            today.lessons.isEmpty() && today.unread != null -> MissingHint(
                "Сервер не прочитал этот день в таблице", colors, sheetLink(schedule, day, sourceUrl),
            )
            // Свободный день: «нажмите, чтобы обновить» читалось бы как «не
            // загрузилось», поэтому нажатие ведёт в приложение.
            today.lessons.isEmpty() -> MissingHint(
                "Пар нет", colors, open = actionStartActivity(openDay(context, day)),
            )
            else -> Lessons(
                today.lessons,
                schedule.bellsOf(today),
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
 * До какого дня вперёд есть данные — в тех же шагах, что и листание. Не по
 * длине списка: расписание приходит с понедельника, вместе с прожитыми днями.
 */
internal fun lastOffset(schedule: ScheduleDto?, today: LocalDate): Int =
    offsets(schedule, today).maxOrNull()?.coerceIn(0, ScheduleWidget.MAX_OFFSET) ?: 0

/**
 * Насколько далеко назад есть данные: прожитые дни недели лежат на телефоне,
 * и листать к ним можно, как и на экране приложения.
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
    unsupported: Boolean,
    unread: Boolean,
    fit: Fit,
    colors: Palette,
) {
    val context = LocalContext.current
    val openApp = actionStartActivity(openDay(context, day))

    // Крупно — день; свою группу знают наизусть, она во второй строке.
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Логотип колледжа — одни контуры, без своего фона; красим цветом темы:
        // фирменный красный на чёрном сливается. На узком виджете уходит первым.
        if (!fit.narrow) {
            Image(
                provider = ImageProvider(R.drawable.logo_ngok),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.logo),
                // По логотипу тоже открывается приложение: он выглядит как кнопка.
                modifier = GlanceModifier
                    .size(width = 26.dp, height = 14.dp)
                    .clickable(openApp),
            )
            Spacer(GlanceModifier.width(6.dp))
        }

        Column(modifier = GlanceModifier.defaultWeight()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // Нажатие на шапку открывает приложение: там неделя целиком
                // и настройки.
                modifier = GlanceModifier.fillMaxWidth().clickable(openApp),
            ) {
                Text(
                    formatDayTitleShort(day).replaceFirstChar { it.uppercase() },
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.text,
                    ),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // ФИО целиком не влезает рядом со временем проверки.
                    if (groupName.count { it == ' ' } >= 2) shortenName(groupName) else groupName,
                    maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                    // Имя уступает место: статус и ⟳ меряются первыми, иначе на
                    // узком виджете они режутся.
                    modifier = GlanceModifier.defaultWeight().clickable(openApp),
                )
                // Пометка дня — здесь, а не у даты: рядом со стрелками ей не
                // хватало места.
                if (unread) {
                    Text(
                        " · не прочитан",
                        maxLines = 1,
                        style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                        modifier = GlanceModifier.clickable(openApp),
                    )
                }
                // Время последней проверки — служебная мелочь, поэтому тем же
                // приглушённым цветом; краснеет, только когда данные протухли.
                Text(
                    when {
                        busy -> " · обновление…"
                        failed -> " · не обновилось"
                        // Пропажа и сбой — не «обновлено»: ответ пришёл, но
                        // расписание в нём прежнее. Группы в таблице нет —
                        // нажатие ведёт в приложение, к «выбрать заново».
                        gone -> " · нет в таблице"
                        unsupported -> " · обновите приложение"
                        // Сбой показываем и тогда, когда пары на экране есть:
                        // снимок прежний. Коротко — подробности на плашке в
                        // приложении.
                        serverBroken -> " · сбой"
                        done -> " · обновлено"
                        else -> " · " + formatFetchedShort(fetchedAt, nowMillis)
                    },
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = when {
                            failed || serverBroken || gone || unsupported -> colors.error
                            busy || done -> colors.accent
                            else -> colors.textDim
                        },
                    ),
                    modifier = GlanceModifier.clickable(
                        if (gone || unsupported) openApp else actionRunCallback<RefreshAction>(),
                    ),
                )
                // Отдельным текстом, а не хвостом времени: внутри одного текста
                // значок встаёт по базовой линии букв и висит выше цифр.
                // Показывается всегда, иначе меняется высота строки и виджет
                // подпрыгивает.
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
        // CLEAR_TOP: без него каждое нажатие клало в стек ещё одну копию
        // экрана. SINGLE_TOP: без него CLEAR_TOP пересоздаёт живой экран.
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )


/**
 * Во сколько раз система увеличила шрифт. Бюджет строк считается в dp, а
 * текст задан в sp и растёт со шрифтом; без поправки нижние строки
 * обрезаются корпусом — прокрутки в виджете нет.
 *
 * Снизу единица: уменьшенный шрифт лишних строк не даёт, зато пустоты добавит.
 */
@Composable
internal fun fontScale(): Float =
    LocalContext.current.resources.configuration.fontScale.coerceAtLeast(1f)
