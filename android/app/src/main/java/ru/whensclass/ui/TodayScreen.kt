package ru.whensclass.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.widget.formatDayTitle
import ru.whensclass.widget.formatFetchedAt
import ru.whensclass.widget.lessonTime
import ru.whensclass.widget.shortenName

/**
 * Расписание в приложении — на несколько дней вперёд.
 *
 * Здесь, в отличие от виджета, места не жалко: показываем всё, что прислал
 * сервер, вместе с преподавателями и пометками.
 */
@Composable
fun TodayScreen(
    groupName: String,
    schedule: ScheduleDto?,
    fetchedAt: Long,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(groupName, style = MaterialTheme.typography.headlineSmall)
                Text(formatFetchedAt(fetchedAt), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRefresh) { Text("Обновить") }
            TextButton(onClick = onSettings) { Text("Настройки") }
        }

        Spacer(Modifier.width(8.dp))

        if (schedule == null) {
            Text(
                "Расписание ещё не загружено.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        LazyColumn {
            schedule.days.forEach { day ->
                item(key = day.date) { DayCard(day, schedule.bells) }
            }
        }
    }
}

@Composable
private fun DayCard(day: DayDto, bells: Map<String, List<String>>) {
    val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                date?.let(::formatDayTitle) ?: day.date,
                style = MaterialTheme.typography.titleMedium,
            )
            if (day.lessons.isEmpty()) {
                Text("Пар нет", style = MaterialTheme.typography.bodyMedium)
            } else {
                day.lessons.forEach { lesson ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    LessonRow(lesson, bells)
                }
            }
        }
    }
}

@Composable
private fun LessonRow(lesson: LessonDto, bells: Map<String, List<String>>) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.width(64.dp)) {
            Text("${lesson.number} пара", style = MaterialTheme.typography.labelMedium)
            lessonTime(bells, lesson.number)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                lesson.subject,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
                ),
            )
            val details = buildString {
                lesson.kind?.let { append(it) }
                lesson.room?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodySmall)
            }
            lesson.teachers.forEach {
                Text(shortenName(it), style = MaterialTheme.typography.bodySmall)
            }
            if (lesson.isCancelled) {
                Text(
                    lesson.note?.let { "Отменена — $it" } ?: "Отменена",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.error,
                    ),
                )
            }
            lesson.url?.let { url ->
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Ссылка на занятие", url))
                    },
                ) {
                    Text("Копировать ссылку на вебинар")
                }
            }
        }
    }
}
