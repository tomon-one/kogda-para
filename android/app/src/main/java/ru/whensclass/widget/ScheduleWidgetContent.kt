package ru.whensclass.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import android.content.Intent
import android.net.Uri
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
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import ru.whensclass.R
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
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
    day: LocalDate,
    offset: Int,
    busy: Boolean = false,
    done: Boolean = false,
    failed: Boolean = false,
    serverBroken: Boolean = false,
    sourceUrl: String? = null,
    modifier: GlanceModifier = GlanceModifier,
) {
    val size = LocalSize.current
    // Оболочки вроде Nova дают сжать виджет ниже объявленного минимума. Ругаться
    // на это некому — просто убираем то, без чего можно, начиная с логотипа.
    val fit = Fit(narrow = size.width < 220.dp, dense = size.height < 120.dp)

    Column(
        modifier = modifier
            .fillMaxSize()
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
            firstOffset(schedule),
            lastOffset(schedule),
            busy,
            done,
            failed,
            serverBroken,
            fit,
            colors,
        )
        Spacer(GlanceModifier.height(if (fit.dense) 3.dp else 6.dp))

        val today = schedule?.days?.firstOrNull { it.date == day.toString() }
        when {
            groupName == null -> Hint("Откройте приложение и выберите свою группу", colors)
            schedule == null -> Hint("Расписание ещё не загружено", colors)
            today == null -> {
                val missing = missingDay(schedule, day, fetchedAt, serverBroken)
                Hint(missing.text, colors, if (missing.toSource) sourceUrl else null)
            }
            today.lessons.isEmpty() -> Hint("Пар нет", colors)
            else -> Lessons(
                today.lessons,
                schedule.bells,
                day,
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
private fun lastOffset(schedule: ScheduleDto?): Int =
    offsets(schedule).maxOrNull()?.coerceIn(0, ScheduleWidget.MAX_OFFSET) ?: 0

/**
 * Насколько далеко назад есть данные.
 *
 * Расписание приходит с понедельника: прожитые дни уже лежат на телефоне, и
 * запрещать их листать незачем — на экране приложения они тоже остаются.
 */
private fun firstOffset(schedule: ScheduleDto?): Int =
    offsets(schedule).minOrNull()?.coerceIn(-ScheduleWidget.MAX_OFFSET, 0) ?: 0

private fun offsets(schedule: ScheduleDto?): List<Int> {
    val today = LocalDate.now()
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
    firstDay: Int,
    lastDay: Int,
    busy: Boolean,
    done: Boolean,
    failed: Boolean,
    serverBroken: Boolean,
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
                    modifier = GlanceModifier.clickable(openApp),
                )
                // Время последней проверки — служебная мелочь, поэтому тем же
                // приглушённым цветом; краснеет, только когда данные протухли.
                Text(
                    when {
                        busy -> " · обновляю…"
                        failed -> " · не вышло"
                        done -> " · обновлено"
                        // Сбой на сервере надо показывать и тогда, когда пары
                        // на экране есть: снимок в этот момент прежний, а не
                        // сегодняшний. Раньше про сбой говорила только надпись
                        // вместо дня — то есть лишь на краю листа, а в середине
                        // недели виджет молчал, пока экран уже говорил.
                        serverBroken -> " · сбой у нас"
                        else -> " · " + formatFetchedShort(fetchedAt)
                    },
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = when {
                            failed || serverBroken || isStale(fetchedAt) -> colors.error
                            busy || done -> colors.accent
                            else -> colors.textDim
                        },
                    ),
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
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

@Composable
private fun Lessons(
    lessons: List<LessonDto>,
    bells: Map<String, List<String>>,
    day: LocalDate,
    fit: Fit,
    colors: Palette,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    if (lessons.isEmpty()) {
        // Подстраховка: список без строк оставлял виджет пустым, и человек
        // видел только шапку на чёрном фоне.
        Hint("Пар нет", colors)
        return
    }
    val current = currentLessonNumber(bells, day)
    // Обычный список, не ленивый. Ленивый прокручивался пальцем, но жил только
    // пока жив процесс приложения: система выгружала его — и виджет чернел
    // насовсем, не оживая ни обновлением, ни запуском приложения.
    val rowHeight = (if (fit.dense) 46.dp else 52.dp) * fontScale()
    // Шапка с группой и стрелками плюс строка «ещё N»: их место списку не
    // достаётся. Раньше «ещё» отнимало строку у пары, и вместо двух занятий
    // виджет показывал одно — хуже, чем не показать остаток вовсе.
    val free = LocalSize.current.height - (if (fit.dense) 48.dp else 58.dp) - 16.dp
    // Обычно показываем не меньше двух пар: одна пара на весь виджет
    // выглядит как поломка. Но если оболочка ужала виджет ниже собственного
    // минимума — а Nova это умеет, — вторая строка не влезет и обрежется
    // корпусом. Тогда честнее показать одну целиком.
    val room = (free / rowHeight).toInt()
    val fits = (if (free < rowHeight) 1 else room.coerceAtLeast(2))
        .coerceAtMost(lessons.size)
    val start = windowStart(lessons, bells, day, fits)
    val shown = lessons.subList(start, minOf(lessons.size, start + fits))
    val rest = lessons.size - start - shown.size

    Column(modifier = modifier) {
        shown.forEach { lesson ->
            // Пара и отступ под ней — одним контейнером: разметка виджета
            // вмещает не больше десяти детей, и по два на пару их не хватало бы
            // на длинный день.
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                LessonRow(lesson, bells, isNow = lesson.number == current, fit, colors)
                Spacer(GlanceModifier.height(if (fit.dense) 3.dp else 4.dp))
            }
        }
        if (rest > 0) {
            val context = LocalContext.current
            Text(
                morePairs(rest),
                maxLines = 1,
                style = TextStyle(fontSize = 11.sp, color = colors.textDim),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(start = 4.dp)
                    .clickable(actionStartActivity(openDay(context, day))),
            )
        }
    }
}

/**
 * Почему дня нет в расписании.
 *
 * Раньше на любой такой случай виджет отвечал «ещё не опубликовано» —
 * утверждением о колледже, которого приложение в этот момент знать не может.
 * Воскресенье внутри опубликованного листа объявлялось неопубликованным, а
 * недельной давности данные — тоже.
 */
internal data class Missing(val text: String, val toSource: Boolean = false)

internal fun missingDay(
    schedule: ScheduleDto,
    day: LocalDate,
    fetchedAt: Long,
    serverBroken: Boolean,
): Missing {
    val covered = schedule.coverage.size == 2 && runCatching {
        !day.isBefore(LocalDate.parse(schedule.coverage[0])) &&
            !day.isAfter(LocalDate.parse(schedule.coverage[1]))
    }.getOrDefault(false)
    return when {
        covered -> Missing("Выходной: пар в этот день нет")
        // Сбой проверяем раньше несвежести. Данные при сбое всегда рано
        // или поздно стареют, и «нажмите на время в шапке» отправляло
        // человека жать кнопку, которая в этом случае помочь не может.
        //
        // Пустой день и наша поломка выглядели одинаково, и человек
        // спокойно ждал расписания, которого мы уже не принесём.
        serverBroken -> Missing("Сбой у нас: расписание не обновляется", toSource = true)
        isStale(fetchedAt) -> Missing("Данные устарели. Нажмите на время в шапке")
        // Единственное объяснение, которое приложение проверить не может:
        // ровно так же выглядит наш собственный промах с поиском листа.
        // Поэтому спорить о виновнике незачем — надо дать выход к таблице.
        else -> Missing("Расписание на этот день ещё не опубликовано", toSource = true)
    }
}

/**
 * С какой пары начинать список, когда влезают не все.
 *
 * К обеду первые пары уже не нужны, а последние не видны. Поэтому сегодняшний
 * список начинается с той пары, которая ещё не кончилась, и съезжает вниз сам
 * собой в течение дня. Прошлые и будущие дни показываются с начала.
 */
private fun windowStart(
    lessons: List<LessonDto>,
    bells: Map<String, List<String>>,
    day: LocalDate,
    fits: Int,
): Int {
    if (day != LocalDate.now()) return 0
    val now = LocalTime.now()
    val index = lessons.indexOfFirst { lesson ->
        val end = bells[lesson.number.toString()]?.getOrNull(1)
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        end == null || !now.isAfter(end)
    }
    // Все пары кончились: остаёмся в конце дня. Раньше indexOfFirst возвращал
    // −1, условие «index <= 0» отбрасывало окно в начало, и вечером виджет
    // прыгал обратно на утренние пары.
    if (index < 0) return maxOf(0, lessons.size - fits)
    if (index == 0) return 0
    // У конца дня не оставляем пустоту снизу: окно упирается в последнюю пару.
    return minOf(index, maxOf(0, lessons.size - fits))
}

/** Насколько тесно виджету — от этого зависит, что показывать. */
private data class Fit(val narrow: Boolean, val dense: Boolean)

/** Приложение открывается на том же дне, что показывает виджет. */
internal fun openDay(context: android.content.Context, day: LocalDate): Intent =
    Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_DAY, day.toString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

@Composable
private fun LessonRow(
    lesson: LessonDto,
    bells: Map<String, List<String>>,
    isNow: Boolean,
    fit: Fit,
    colors: Palette,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(if (isNow) colors.nowSurface else colors.surface)
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = if (fit.dense) 3.dp else 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Ширины хватает на «09:00–10:30» одной строкой: время, переносимое
        // пополам, читается как опечатка. На узком виджете диапазон не влезает —
        // тогда показываем только начало пары.
        Column(modifier = GlanceModifier.width(if (fit.narrow) 48.dp else 72.dp)) {
            // У текущей пары номер уступает место словам: номер и так виден по
            // времени рядом, а «идёт сейчас» ищут глазами первым. Строка та же,
            // поэтому высота пары не меняется.
            Text(
                when {
                    isNow && fit.narrow -> "сейчас"
                    isNow -> "идёт сейчас"
                    else -> "${lesson.number} пара"
                },
                maxLines = 1,
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = if (isNow) FontWeight.Medium else FontWeight.Normal,
                    color = if (isNow) colors.accent else colors.textDim,
                ),
            )
            val time = if (fit.narrow) {
                lessonStart(bells, lesson.number)
            } else {
                lessonTime(bells, lesson.number)
            }
            time?.let { time ->
                Text(
                    time,
                    maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = colors.text),
                )
            }
        }
        Spacer(GlanceModifier.width(6.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                lesson.subject,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
                ),
            )
            Details(lesson, colors)
        }
    }
}

@Composable
private fun Details(lesson: LessonDto, colors: Palette) {
    // Одной строкой, а не тремя. Раньше тип, место и преподаватель занимали по
    // строке каждый, пара выходила в четыре строки высотой, и в виджет помещалась
    // одна — при том что смотрят в него ради двух ближайших.
    val parts = buildList {
        if (lesson.isCancelled) add(lesson.note?.let { "отменена — $it" } ?: "отменена")
        kindName(lesson.kind)?.let { add(it) }
        add(if (lesson.isOnline) "онлайн" else roomLabel(lesson.room) ?: "не указано")
        // В расписании преподавателя вместо его имени — группы, которым читается
        // пара: сам он и так знает, кто ведёт.
        (lesson.groups ?: lesson.teachers.firstOrNull()?.let(::surnameOnly))?.let { add(it) }
    }
    if (parts.isEmpty()) return

    val line = GlanceModifier.fillMaxWidth()
    Text(
        if (lesson.url != null) parts.joinToString(" · ") + "  ⧉" else parts.joinToString(" · "),
        maxLines = 1,
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                lesson.isCancelled -> colors.error
                lesson.isOnline -> colors.accent
                else -> colors.text
            },
        ),
        modifier = lesson.url?.let { url ->
            val context = LocalContext.current
            line.clickable(actionStartActivity(CopyLinkActivity.intent(context, url)))
        } ?: line,
    )
}

@Composable
private fun Hint(text: String, colors: Palette, sourceUrl: String? = null) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionRunCallback<RefreshAction>()),
    ) {
        Text(
            text,
            style = TextStyle(fontSize = 13.sp, color = colors.textDim),
            modifier = GlanceModifier.padding(vertical = 8.dp),
        )
        if (sourceUrl == null) {
            Text(
                "нажмите, чтобы обновить",
                style = TextStyle(fontSize = 11.sp, color = colors.accent),
            )
        } else {
            // Второй строкой ровно одна подсказка, а не две: обновление
            // здесь уже ничего не изменит — сервер сказал всё, что знает.
            // На узком виджете третья строка к тому же не поместилась бы.
            Text(
                "открыть таблицу колледжа",
                style = TextStyle(fontSize = 11.sp, color = colors.accent),
                modifier = GlanceModifier
                    .clickable(actionStartActivity(openSource(sourceUrl))),
            )
        }
    }
}

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

/** Таблица колледжа в браузере: первоисточник, когда дня у нас нет. */
private fun openSource(url: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
