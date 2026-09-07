package ru.whensclass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import ru.whensclass.data.DayDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.widget.currentLessonNumber
import ru.whensclass.widget.formatDayTitle
import ru.whensclass.widget.formatFetchedAt
import ru.whensclass.widget.lessonTime
import ru.whensclass.widget.shortenName

/**
 * Расписание на несколько дней вперёд.
 *
 * Здесь, в отличие от виджета, места не жалко: показываем всё, что прислал
 * сервер. Пары выстроены таблицей — колонка времени одной ширины на все
 * строки, иначе взгляд не находит, когда начинается следующая.
 */
private val TIME_COLUMN = 88.dp

@Composable
fun TodayScreen(
    groupName: String,
    schedule: ScheduleDto?,
    fetchedAt: Long,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    groupName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    formatFetchedAt(fetchedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSettings) { Text("Настройки") }
        }

        if (schedule == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Расписание ещё не загружено.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRefresh) { Text("Загрузить") }
            }
            return@Column
        }

        val today = remember { LocalDate.now() }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(schedule.days, key = { it.date }) { day ->
                DayCard(day, schedule.bells, today)
            }
            item(key = "refresh") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onRefresh) { Text("Обновить расписание") }
                }
            }
        }
    }
}

@Composable
private fun DayCard(day: DayDto, bells: Map<String, List<String>>, today: LocalDate) {
    val date = remember(day.date) { runCatching { LocalDate.parse(day.date) }.getOrNull() }
    val isToday = date == today
    val current = if (isToday) currentLessonNumber(bells, today) else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                date?.let(::formatDayTitle) ?: day.date,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isToday) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        if (day.lessons.isEmpty()) {
            Text(
                "Пар нет",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            )
        } else {
            day.lessons.forEachIndexed { index, lesson ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(start = TIME_COLUMN + 14.dp))
                }
                LessonRow(lesson, bells, isNow = lesson.number == current)
            }
        }
    }
}

@Composable
private fun LessonRow(lesson: LessonDto, bells: Map<String, List<String>>, isNow: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Колонка времени одинаковой ширины во всех строках — иначе таблица
        // разъезжается, а «09:00–10:30» переносится и теряет последнюю цифру.
        Column(modifier = Modifier.width(TIME_COLUMN)) {
            Text(
                "${lesson.number} пара",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            lessonTime(bells, lesson.number)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
            if (isNow) {
                Text(
                    "сейчас",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                lesson.subject,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
            )

            val details = remember(lesson) {
                buildString {
                    lesson.kind?.let { append(it) }
                    lesson.room?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                    lesson.teachers.forEach {
                        if (isNotEmpty()) append(" · ")
                        append(shortenName(it))
                    }
                }
            }
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (lesson.isCancelled) {
                Text(
                    lesson.note?.let { "Отменена — $it" } ?: "Отменена",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }

            lesson.url?.let { url ->
                Text(
                    "Занятие онлайн · копировать ссылку",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        // Область нажатия во всю строку и не ниже 48dp:
                        // в мелкую надпись попасть пальцем трудно.
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("Ссылка на занятие", url))
                            Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT)
                                .show()
                        }
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}
