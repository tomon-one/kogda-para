package ru.whensclass.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import ru.whensclass.data.DayDto
import ru.whensclass.data.UnreadDay
import ru.whensclass.widget.currentLessonNumber
import ru.whensclass.widget.formatDayTitle

@Composable
internal fun DayCard(
    day: DayDto,
    bells: Map<String, List<String>>,
    now: LocalDateTime,
    teacher: Boolean = false,
    /** Выбранные группы по порядку, первая — своя; пусто — группа одна. */
    groups: List<String> = emptyList(),
    groupsByName: Boolean = true,
    /** Как показывать названия предметов. */
    subjects: ru.whensclass.data.SubjectNames = ru.whensclass.data.SubjectNames(),
    /** Следующий день — будний и тоже без своих пар ([freeOwnDay]). */
    nextFree: Boolean = false,
    /** Экран давно не трогали ([IDLE_MILLIS]). */
    idle: Boolean = false,
    /** Картинка дня в чат; null — кнопки нет. */
    onImage: ((String) -> Unit)? = null,
    /** Компактный вид: отступы меньше, пары теснее. */
    compact: Boolean = false,
) {
    val today = now.toLocalDate()
    val date = remember(day.date) { runCatching { LocalDate.parse(day.date) }.getOrNull() }
    val isToday = date == today
    val past = date != null && date.isBefore(today)
    // Момент — параметром от часов со звонками (rememberNow в ScheduleDays): иначе подсветка не двигается со звонком.
    val current = if (isToday) currentLessonNumber(bells, today, now) else null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        // Прошедший день не выбрасываем, а приглушаем цветом, не прозрачностью: при ней вчерашнюю
        // аудиторию не прочесть.
        Column {
            val notes = unreadNotes(day) + listOfNotNull(
                BIRTHDAY.takeIf { isToday && !teacher && date.monthValue == 4 && date.dayOfMonth == 2 },
                date?.takeIf { isToday }?.let { holidayNote(it.monthValue, it.dayOfMonth) },
            )
            // Метки — в шапке дня (вариант А): и график, и непрочитанность видны сразу.
            val ownUnreadShort = day.unread != null && day.unreadGroups.isEmpty()
            DayHeader(
                date?.let { formatDayTitle(it, today) } ?: day.date, isToday, past, daySummary(day.lessons, groups, bells),
                bottom = if (notes.isEmpty()) (if (compact) 4.dp else 8.dp) else 4.dp,
                chips = listOfNotNull(day.state?.let(::stateDayText), "Не прочитан".takeIf { day.unread != null }),
                // У дня без пар картинка ни о чём — кнопки нет.
                onImage = onImage?.takeIf { day.lessons.any { lesson -> !lesson.isCancelled && (groups.isEmpty() || 0 in lesson.slots) } }
                    ?.let { share -> { share(day.date) } },
                compact = compact,
            )

            // Свой день не прочитан — «пар нет» тут было бы неправдой.
            val unknown = day.unread == UnreadDay.MISSING
            // Свой непрочитанный день без подробностей сказан меткой в шапке — строку не повторяем.
            val shownNotes = if (day.unread != null && ownUnreadShort) notes.drop(1) else notes
            shownNotes.forEachIndexed { index, note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (index == 0) 0.dp else 4.dp,
                        bottom = when {
                            index != shownNotes.lastIndex -> 4.dp
                            day.lessons.isNotEmpty() -> 8.dp
                            unknown -> 16.dp
                            else -> 4.dp
                        }),
                )
            }
            if (unknown && day.lessons.isEmpty()) {
                // Сказано выше.
            } else if (day.lessons.isEmpty()) {
                Text(
                    // График колледжа важнее шутки: «пар нет» в неделю сессии — неправда.
                    day.state?.let(::stateDayText)
                        ?: if (day.absent) absentDay(day.date) else freeDay(day.date, teacher, nextFree, now, idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            } else {
                // Своих пар нет, а у выбранных групп есть: это сказала сводка под датой, подпись не
                // повторяем; отменённые сводка не считает — у них подпись остаётся.
                val allForeign = groups.isNotEmpty() && day.lessons.none { 0 in it.slots }
                day.lessons.forEach { lesson ->
                    // Отменённая пара в своё время и пара только у других групп — не «идёт сейчас».
                    val foreign = groups.isNotEmpty() && 0 !in lesson.slots
                    LessonRow(
                        lesson, bells,
                        isNow = lesson.number == current && !lesson.isCancelled && !foreign,
                        past = past,
                        groups = groups,
                        groupsByName = groupsByName,
                        subject = subjects.text(lesson),
                        saidAbove = allForeign && !lesson.isCancelled,
                        compact = compact,
                    )
                }
                // День, в котором отменили все свои пары.
                val own = if (groups.isEmpty()) day.lessons else day.lessons.filter { 0 in it.slots }
                if (own.isNotEmpty() && own.all { it.isCancelled }) {
                    Text(
                        // Преподавателю отмена всех пар — сорванные часы, не удача.
                        if (teacher) "Все пары отменены" else "Всё отменили. Повезло",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** Метка дня: график колледжа или непрочитанный день — одной строкой у даты. */
@Composable
private fun DayChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun DayHeader(
    title: String,
    isToday: Boolean,
    past: Boolean = false,
    summary: String? = null,
    bottom: Dp = 8.dp,
    /** Короткие метки дня: график колледжа, непрочитанный день. */
    chips: List<String> = emptyList(),
    /** Картинка дня в чат: null — кнопки нет. */
    onImage: (() -> Unit)? = null,
    /** Компактный вид: шапка дня ниже. */
    compact: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (compact) 8.dp else 12.dp, bottom = bottom)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    isToday -> MaterialTheme.colorScheme.primary
                    past -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                // Чтецу — заголовок: по дням переходят жестом, как по заголовкам на сайте.
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            chips.forEach { DayChip(it) }
            // Картинка дня у дня без пар ни о чём: кнопки нет. Поле — 32 dp, чтобы не растить строку
            // с датой: у IconButton оно 48 dp.
            if (onImage != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable(onClick = onImage),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Картинка дня в чат",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
