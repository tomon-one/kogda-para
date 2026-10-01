package ru.whensclass.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import android.content.Intent
import android.net.Uri
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto

/**
 * Почему дня нет в расписании.
 *
 * Раньше на любой такой случай виджет отвечал «ещё не опубликовано» —
 * утверждением о колледже, которого приложение в этот момент знать не может.
 * Воскресенье внутри опубликованного листа объявлялось неопубликованным, а
 * недельной давности данные — тоже.
 */
internal data class Missing(val text: String, val toSource: Boolean = false, val off: Boolean = false)

/**
 * Проверял ли телефон сервер с начала сегодняшнего дня. Нет — «ещё не
 * опубликовано» утверждать нечем: телефон, который не будят в фоне (или без
 * сети), с четверга не видел недели, которую колледж выложил в пятницу.
 */
internal fun checkedToday(fetchedAt: Long, today: LocalDate): Boolean =
    fetchedAt >= today.atStartOfDay(COLLEGE_ZONE).toInstant().toEpochMilli()

internal fun missingDay(
    schedule: ScheduleDto,
    day: LocalDate,
    serverBroken: Boolean,
    /** Для недельного виджета — «на эти дни», а не «на этот день». */
    week: Boolean = false,
    /** Телефон проверял сервер сегодня ([checkedToday]). */
    checked: Boolean = true,
): Missing {
    val covered = schedule.coverage.size == 2 && runCatching {
        !day.isBefore(LocalDate.parse(schedule.coverage[0])) &&
            !day.isAfter(LocalDate.parse(schedule.coverage[1]))
    }.getOrDefault(false)
    // «Выходной» — только про день между первым и последним скачанным днём:
    // cov относится ко всему листу, а не к окну на телефоне, и будень новой
    // недели при окне прошлой назывался выходным, хотя пары у группы есть.
    val dates = schedule.days.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
    val inWindow = dates.isNotEmpty() && !day.isBefore(dates.min()) && !day.isAfter(dates.max())
    val off = Missing("Выходной", off = true)
    return when {
        // Воскресений в листах не бывает: это выходной всегда, и при cov,
        // который кончается субботой. Но не для недели:
        // пустая неделя в воскресенье — это «следующая не выложена», а не
        // «выходной» без выхода к таблице.
        day.dayOfWeek == java.time.DayOfWeek.SUNDAY && !week -> off
        covered && inWindow -> off
        // Сбой проверяем раньше несвежести. Данные при сбое всегда рано
        // или поздно стареют, и «нажмите на время в шапке» отправляло
        // человека жать кнопку, которая в этом случае помочь не может.
        //
        // Пустой день и наша поломка выглядели одинаково, и человек
        // спокойно ждал расписания, которого мы уже не принесём.
        serverBroken -> Missing("Сбой: расписание не обновляется", toSource = true)
        // Лист этот день покрывает, а на телефоне его нет — окно не то.
        // Утверждать «выходной» или «не опубликовано» нечем.
        covered || !checked -> Missing(
            if (week) "Расписание на эти дни не загружено" else "Расписание на этот день не загружено",
        )
        // Единственное объяснение, которое приложение проверить не может:
        // ровно так же выглядит наш собственный промах с поиском листа.
        // Поэтому спорить о виновнике незачем — надо дать выход к таблице.
        else -> Missing(
            if (week) "Расписание на эти дни ещё не опубликовано"
            else "Расписание на этот день ещё не опубликовано",
            toSource = true,
        )
    }
}

/**
 * С какой пары начинать список, когда влезают не все.
 *
 * К обеду первые пары уже не нужны, а последние не видны. Поэтому сегодняшний
 * список начинается с той пары, которая ещё не кончилась, и съезжает вниз сам
 * собой в течение дня. Прошлые и будущие дни показываются с начала.
 */
internal fun windowStart(
    lessons: List<LessonDto>,
    bells: Map<String, List<String>>,
    day: LocalDate,
    fits: Int,
    now: LocalDateTime,
): Int {
    // Окно стоит там, где день граничит с «сейчас»: у будущего дня — в начале,
    // у прожитого — в конце, у сегодняшнего — на ближайшей не кончившейся паре.
    // «Сейчас» приходит параметром: см. currentLessonNumber.
    val today = now.toLocalDate()
    if (day.isAfter(today)) return 0
    if (day.isBefore(today)) return maxOf(0, lessons.size - fits)
    val time = now.toLocalTime()
    val index = lessons.indexOfFirst { lesson ->
        val end = bells[lesson.number.toString()]?.getOrNull(1)
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        end == null || !time.isAfter(end)
    }
    // Все пары кончились: остаёмся в конце дня. Раньше indexOfFirst возвращал
    // −1, условие «index <= 0» отбрасывало окно в начало, и вечером виджет
    // прыгал обратно на утренние пары.
    if (index < 0) return maxOf(0, lessons.size - fits)
    if (index == 0) return 0
    // У конца дня не оставляем пустоту снизу: окно упирается в последнюю пару.
    return minOf(index, maxOf(0, lessons.size - fits))
}


/**
 * Надпись вместо пар. [open] — нажатие открывает приложение, а не обновляет:
 * когда обновлять нечего (группа не выбрана).
 */
@Composable
internal fun MissingHint(
    text: String,
    colors: Palette,
    sourceUrl: String? = null,
    open: androidx.glance.action.Action? = null,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(open ?: actionRunCallback<RefreshAction>()),
    ) {
        Text(
            text,
            style = TextStyle(fontSize = 13.sp, color = colors.textDim),
            modifier = GlanceModifier.padding(vertical = 8.dp),
        )
        // «Нажмите, чтобы обновить» под «откройте приложение» спорило с ним
        // самим.
        if (open == null && sourceUrl == null) {
            Text(
                "нажмите, чтобы обновить",
                style = TextStyle(fontSize = 11.sp, color = colors.accent),
            )
        } else if (sourceUrl != null) {
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


/** Таблица колледжа в браузере: первоисточник, когда дня у нас нет. */
private fun openSource(url: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
