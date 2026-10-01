package ru.whensclass.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.ScheduleStore
import ru.whensclass.data.sheetLink
import ru.whensclass.widget.formatDurationLong
import ru.whensclass.widget.formatSince
import ru.whensclass.widget.plural

/**
 * Плашка «расписание застряло на сервере»: без неё сбой выглядит как «колледж
 * ещё не выложил». Говорит, чья это беда, и даёт дорогу в обход — таблицу
 * колледжа.
 *
 * Цвета берём из своих: `errorContainer` в схеме не задан, и Material
 * подставил бы туда чужой розовый.
 */
@Composable
internal fun ServerBroken(
    sourceUrl: String?,
    since: String?,
    now: LocalDateTime,
    unreachable: Boolean = false,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // Сервер не ответил телефону — не «не прочитал таблицу»: таблица тут
            // ни при чём, как и в уведомлении.
            Text(
                if (unreachable) "Сервер расписания не отвечает" else "Сбой на сервере",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                // Что на экране прежнее, говорит строка со временем сбоя ниже.
                (if (unreachable) "Телефон не достучался до сервера. "
                else "Не удалось прочитать таблицу. ") + "Пары могли поменяться.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Давность — отдельной строкой: долгий сбой не должен выглядеть
            // минутным рядом с «проверено 5 минут назад».
            since?.let {
                Text(
                    // Не «получено» и не дата правки: до сбоя сервер читал таблицу
                    // исправно, значит на экране она — какой была к его началу.
                    "Сбой с ${formatSince(it, now.atZone(ru.whensclass.widget.COLLEGE_ZONE).toInstant())}. " +
                        "На экране — таблица, какой она была до сбоя.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            sourceUrl?.let { url ->
                ActionButton(
                    label = "Открыть таблицу колледжа",
                    onClick = { openLink(context, url) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Плашка «группы в таблице больше нет»: сервер отвечает 404 при здоровом
 * состоянии — группу переименовали, разделили или убрали, и старый
 * идентификатор больше ни на что не указывает. Выход один — выбрать заново.
 */
@Composable
internal fun Gone(groupName: String, teacherMode: Boolean, onRepick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                if (teacherMode) "Вас больше нет в таблице" else "Группы больше нет в таблице",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                // Без повтора заголовка; «разделили» о человеке — нелепица, а
                // 404 преподавателю приходит при другом написании имени или если
                // его нет 60 дней.
                (if (teacherMode) "Имя «$groupName» в таблице записали иначе или убрали. "
                 else "Группу «$groupName» переименовали, разделили или убрали. ") +
                    "На экране — последнее, что было.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionButton(label = "Выбрать заново", onClick = onRepick, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** За сколько грузится таблица колледжа. Перемерено 8 сентября 2026 года. */
private const val SHEET_SECONDS = 8

/**
 * Счёт ответов: сколько раз таблицу открывать не пришлось. Прячется под
 * долгим нажатием на название группы, нарочно без подсказки.
 *
 * Два счётчика показаны отдельно ([ScheduleStore.countOpen]): виджет
 * отвечает и без человека.
 */
@Composable
internal fun TallyDialog(loadTally: suspend () -> ScheduleStore.Tally, onDismiss: () -> Unit) {
    var tally by remember { mutableStateOf<ScheduleStore.Tally?>(null) }
    LaunchedEffect(Unit) { tally = loadTally() }
    // Пока считается — не показываем ничего: окно, мигнувшее нулями, выглядит
    // поломкой.
    val counted = tally ?: return

    // Крупное число — только открытия приложения: виджеты перерисовываются и
    // тогда, когда на них никто не смотрит.
    val total = counted.opens
    val seconds = total * SHEET_SECONDS
    val spent = if (seconds < 60) {
        plural(seconds.toInt(), "секунду", "секунды", "секунд")
    } else {
        formatDurationLong((seconds / 60).toInt())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Таблицу вы не открывали") },
        text = {
            Column {
                Text(
                    plural(total.toInt(), "раз", "раза", "раз"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Столько раз вместо неё ответило приложение. Виджеты сверх того " +
                        "перерисовывались ${plural(counted.draws.toInt(), "раз", "раза", "раз")} — " +
                        "смотрели на них или нет.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Она грузится $SHEET_SECONDS секунд. Считайте, что $spent " +
                        "вы потратили на что-то другое.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (counted.since > 0L) {
                    Spacer(Modifier.height(8.dp))
                    val day = Instant.ofEpochMilli(counted.since)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    Text(
                        "Счёт идёт ${tallySince(day, ru.whensclass.widget.collegeToday())}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ладно") } },
    )
}

/** «с 23 сентября», «с сегодняшнего дня»: после «с» — родительный падеж. */
internal fun tallySince(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "с сегодняшнего дня"
    today.minusDays(1) -> "со вчерашнего дня"
    else -> "с " + day.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale("ru")))
}


/**
 * Объяснение вместо пустого экрана, когда расписания нет. true — объяснили.
 * [loading] — оно как раз загружается (сразу после выбора группы), и
 * «Проверьте интернет» было бы ложью.
 */
@Composable
internal fun explainMissing(
    schedule: ScheduleDto?,
    today: java.time.LocalDate,
    sourceUrl: String?,
    loading: Boolean = false,
): Boolean {
    if (schedule == null && loading) {
        Explanation(
            title = "Расписание загружается",
            text = "Обычно это несколько секунд.",
            busy = true,
        )
        return true
    }
    if (schedule == null) {
        Explanation(
            title = "Расписание ещё не загружено",
            text = "Проверьте интернет и нажмите ⟳ вверху. Не помогает — напишите автору в Telegram: @toomonn.",
        )
        return true
    }
    if (schedule.days.isEmpty()) {
        // Сервер ответил, но дней в ответе нет: так бывает в воскресенье,
        // когда следующий лист ещё не выложен.
        Explanation(
            title = "На эти дни расписания нет",
            text = "Колледж их ещё не выложил — или расписание застряло на сервере. " +
                "Проверить можно в таблице колледжа.",
            sourceUrl = sheetLink(schedule, today, sourceUrl),
        )
        return true
    }
    return false
}
