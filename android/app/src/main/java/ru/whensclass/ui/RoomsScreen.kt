package ru.whensclass.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.data.RoomUseDto
import ru.whensclass.data.RoomsDto
import ru.whensclass.widget.formatDayRange
import ru.whensclass.widget.plural
import java.time.LocalDate
import java.time.LocalTime

/**
 * Кабинеты: какие свободны по расписанию на паре дня, какие заняты и где
 * накладки (docs/api.md, /v1/rooms). То же, что вкладка на сайте. «По
 * расписанию» — всерьёз: ключей, переносов и мероприятий таблица не знает.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoomsScreen(
    load: suspend (String?) -> RoomsDto?,
    today: LocalDate,
    now: LocalTime,
) {
    var date by rememberSaveable { mutableStateOf<String?>(null) }
    var pair by rememberSaveable { mutableIntStateOf(0) }
    var retry by rememberSaveable { mutableIntStateOf(0) }
    var body by androidx.compose.runtime.remember { mutableStateOf<RoomsDto?>(null) }
    var failed by androidx.compose.runtime.remember { mutableStateOf(false) }
    LaunchedEffect(date, retry) {
        failed = false
        val fresh = load(date)
        if (fresh != null) body = fresh else failed = true
    }

    val shown = body
    if (shown == null || (date != null && shown.date != date && !failed)) {
        if (failed) {
            Explanation("Аудитории не загрузились", "Проверьте интернет.")
            ActionButton(label = "Повторить", onClick = { retry++ }, modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val numbers = shown.bells.keys.mapNotNull { it.toIntOrNull() }.sorted()
    val days = shown.days.ifEmpty { listOf(shown.date) }.take(DAYS_SHOWN)
    // Поздно вечером сегодняшние пары кончились — сразу к следующему дню.
    val lastEnd = numbers.lastOrNull()?.let { clock(shown.bells[it.toString()]?.getOrNull(1)) }
    if (date == null && shown.date == today.toString() && days.size > 1 && lastEnd != null && now >= lastEnd) {
        LaunchedEffect(Unit) { date = days[1] }
    }
    // Сегодня — идущая или следующая пара, иначе первая.
    val chosen = pair.takeIf { it in numbers } ?: run {
        if (shown.date != today.toString()) numbers.firstOrNull() ?: 1
        else numbers.firstOrNull { n -> clock(shown.bells[n.toString()]?.getOrNull(1))?.let { now < it } == true }
            ?: numbers.lastOrNull() ?: 1
    }
    val busy = shown.busy[chosen.toString()].orEmpty()
    val clashes = shown.clashes.filter { it.number == chosen }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        item(key = "дни") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEach { d ->
                    val day = runCatching { LocalDate.parse(d) }.getOrNull()
                    val label = when (day) {
                        today -> "Сегодня"
                        today.plusDays(1) -> "Завтра"
                        null -> d
                        else -> formatDayRange(day, day).substringBefore(',')
                    }
                    FilterChip(selected = d == shown.date, onClick = { date = d; pair = 0 }, label = { Text(label) })
                }
            }
        }
        item(key = "пары") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                numbers.forEach { n ->
                    val start = shown.bells[n.toString()]?.getOrNull(0)
                    FilterChip(selected = n == chosen, onClick = { pair = n }, label = { Text("$n") })
                }
            }
            val span = shown.bells[chosen.toString()].orEmpty()
            Text(
                "$chosen пара" + (span.getOrNull(0)?.let { ", $it–" + span.getOrNull(1).orEmpty() } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
        shown.buildings.forEach { building ->
            item(key = "корпус:" + building.name) {
                val free = building.rooms.filter { it.room !in busy }
                // Сменили день или пару — кабинеты въезжают: видно, что список другой.
                AnimatedContent(
                    targetState = shown.date to chosen,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 12 }) togetherWith fadeOut(tween(90))
                    },
                    label = "кабинеты",
                ) { _ ->
                Card {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            building.name + " · свободно ${free.size} из ${building.rooms.size}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (free.isEmpty()) Note("Свободных по расписанию нет.")
                        free.groupBy { it.floor }.toSortedMap().forEach { (floor, rooms) ->
                            Row(Modifier.padding(top = 6.dp)) {
                                Text(
                                    if (floor.isNotEmpty()) "$floor этаж" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(56.dp).padding(top = 4.dp),
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    rooms.forEach { RoomChip(it.room.removePrefix("Восход ")) }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        if (clashes.isNotEmpty()) {
            item(key = "накладки") {
                Section("Накладки — в одном кабинете разные пары")
                Card { clashes.forEachIndexed { i, c -> if (i > 0) Divider(); Uses(c.room, c.lessons) } }
            }
        }
        if (busy.isNotEmpty()) {
            item(key = "заняты") {
                var open by rememberSaveable { mutableStateOf(false) }
                Text(
                    (if (open) "▾ " else "▸ ") + "Заняты — ${busy.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                )
                if (open) Card { busy.toSortedMap().entries.forEachIndexed { i, (room, uses) -> if (i > 0) Divider(); Uses(room, uses) } }
            }
        }
        item(key = "пояснение") {
            Note(
                "Свободны по расписанию: ключей, переносов и мероприятий таблица колледжа не знает." +
                    if (shown.unread.isEmpty()) "" else " Не учтены кабинеты " +
                        plural(shown.unread.size, "группы", "групп", "групп") +
                        ", у которых день не прочитан: " + shown.unread.joinToString(", ") + ".",
            )
        }
    }
}

/** Сколько дней показывать: сегодня, завтра и следующий учебный — дальше колледж всё равно переставит. */
private const val DAYS_SHOWN = 3

private fun clock(text: String?): LocalTime? =
    text?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }

@Composable
private fun Note(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
)

@Composable
private fun Section(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
)

@Composable
private fun Card(content: @Composable () -> Unit) = Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
) { Column { content() } }

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.background)

@Composable
private fun RoomChip(room: String) = Text(
    room,
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
        .padding(horizontal = 10.dp, vertical = 3.dp),
)

@Composable
private fun Uses(room: String, uses: List<RoomUseDto>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(room, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
        Text(
            uses.joinToString("; ") { u -> u.group + " — " + u.subject + (u.teachers.firstOrNull()?.let { ", $it" } ?: "") },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
