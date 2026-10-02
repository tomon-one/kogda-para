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
 * Почему дня нет в расписании. «Ещё не опубликовано» — утверждение о колледже,
 * и говорится только тогда, когда другие объяснения (выходной, сбой, не то
 * окно на телефоне) исключены.
 */
internal data class Missing(
    val text: String,
    val toSource: Boolean = false,
    /** Обновлять нечего: нажатие ведёт в приложение («выходной», «обновите приложение»). */
    val toApp: Boolean = false,
)

/**
 * Проверял ли телефон сервер с начала сегодняшнего дня. Нет — «ещё не
 * опубликовано» утверждать нечем: телефон без сети или без фона мог не
 * увидеть уже выложенную неделю.
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
    /** Сервер эту сборку не обслуживает (426). */
    unsupported: Boolean = false,
): Missing {
    val covered = schedule.coverage.size == 2 && runCatching {
        !day.isBefore(LocalDate.parse(schedule.coverage[0])) &&
            !day.isAfter(LocalDate.parse(schedule.coverage[1]))
    }.getOrDefault(false)
    // «Выходной» — только про день между первым и последним скачанным днём:
    // cov относится ко всему листу, а не к окну на телефоне.
    val dates = schedule.days.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
    val inWindow = dates.isNotEmpty() && !day.isBefore(dates.min()) && !day.isAfter(dates.max())
    val off = Missing("Выходной", toApp = true)
    return when {
        // Воскресений в листах не бывает: это выходной всегда. Но не для
        // недели: пустая неделя в воскресенье — это «следующая не выложена».
        day.dayOfWeek == java.time.DayOfWeek.SUNDAY && !week -> off
        covered && inWindow -> off
        unsupported -> Missing("Нужно обновить приложение", toApp = true)
        // Сбой — раньше несвежести: данные при сбое всегда стареют, а
        // обновление тут не поможет.
        serverBroken -> Missing("Сбой: расписание не обновляется", toSource = true)
        // Лист этот день покрывает, а на телефоне его нет — окно не то.
        // Утверждать «выходной» или «не опубликовано» нечем.
        covered || !checked -> Missing(
            if (week) "Расписание на эти дни не загружено" else "Расписание на этот день не загружено",
        )
        // Проверить это приложение не может — так же выглядит и промах
        // сервера с поиском листа, — поэтому даём выход к таблице.
        else -> Missing(
            if (week) "Расписание на эти дни ещё не опубликовано"
            else "Расписание на этот день ещё не опубликовано",
            toSource = true,
        )
    }
}

/**
 * С какой пары начинать список, когда влезают не все: окно стоит там, где день
 * граничит с «сейчас» — у будущего дня в начале, у прожитого в конце, у
 * сегодняшнего на ближайшей не кончившейся паре.
 */
internal fun windowStart(
    lessons: List<LessonDto>,
    bells: Map<String, List<String>>,
    day: LocalDate,
    fits: Int,
    now: LocalDateTime,
): Int {
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
    // Все пары кончились: остаёмся в конце дня, а не прыгаем на утренние.
    if (index < 0) return maxOf(0, lessons.size - fits)
    if (index == 0) return 0
    // У конца дня не оставляем пустоту снизу: окно упирается в последнюю пару.
    return minOf(index, maxOf(0, lessons.size - fits))
}

/**
 * Окно [start, start + fits) без разреза номера: две пары одного номера
 * (подгруппы в разных кабинетах) показываются обе или прячутся обе, иначе
 * половина пропала бы без слова. Край, режущий номер, сдвигается внутрь окна;
 * не остаётся ничего — окно как было.
 */
internal fun windowRange(lessons: List<LessonDto>, start: Int, fits: Int): IntRange {
    val end = minOf(lessons.size, start + fits)
    var from = start
    var to = end
    while (from in 1 until to && lessons[from].number == lessons[from - 1].number) from++
    while (to in (from + 1) until lessons.size && lessons[to].number == lessons[to - 1].number) to--
    return if (from < to) from until to else start until end
}


/**
 * Надпись вместо пар. [open] — нажатие открывает приложение, а не обновляет:
 * когда обновлять нечего (группа не выбрана, свободный день).
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
        // «Нажмите, чтобы обновить» — только когда нажатие и правда обновляет.
        if (open == null && sourceUrl == null) {
            Text(
                "нажмите, чтобы обновить",
                style = TextStyle(fontSize = 11.sp, color = colors.accent),
            )
        } else if (sourceUrl != null) {
            // Одна подсказка, а не две: обновление здесь ничего не изменит —
            // сервер сказал всё, что знает.
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
