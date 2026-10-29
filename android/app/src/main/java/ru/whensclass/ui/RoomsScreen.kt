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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ru.whensclass.data.RoomUseDto
import ru.whensclass.data.RoomsAnswer
import ru.whensclass.data.RoomsDto
import ru.whensclass.widget.formatDayRange
import ru.whensclass.widget.plural
import java.time.LocalDate
import java.time.LocalTime

/**
 * Последний ответ вкладки «Аудитории». Живёт выше вкладки: иначе каждое
 * переключение вкладок стирало бы его и кабинеты грузились бы заново.
 */
@Stable
class RoomsMemory {
    var body by mutableStateOf<RoomsDto?>(null)
    // На какой запрос ответ в `body` и когда он пришёл: полежавший перечитывается.
    var loadedFor by mutableStateOf<String?>(null)
    var loadedAt by mutableLongStateOf(0L)
    var loadedRetry by mutableIntStateOf(-1)
    // ⟳ на экране: не загрузившееся — заново.
    var nudge by mutableIntStateOf(0)
}

/**
 * Кабинеты: какие свободны по расписанию на паре дня, какие заняты и где
 * накладки (docs/api.md, /v1/rooms). То же, что вкладка на сайте. «По
 * расписанию» — всерьёз: ключей, переносов и мероприятий таблица не знает.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoomsScreen(
    load: suspend (String?) -> RoomsAnswer,
    today: LocalDate,
    now: LocalTime,
    memory: RoomsMemory = remember { RoomsMemory() },
) {
    // Выбранные день и пара — на тот день, когда их выбрали: назавтра вкладка
    // снова открывается на сегодняшнем, а не на прошедшем.
    var date by rememberSaveable { mutableStateOf<String?>(null) }
    var pair by rememberSaveable { mutableIntStateOf(0) }
    var chosenOn by rememberSaveable { mutableStateOf<String?>(null) }
    val fresh = chosenOn == today.toString()
    val active = date.takeIf { fresh }
    val activePair = pair.takeIf { fresh } ?: 0
    fun choose(day: String?, number: Int) {
        date = day; pair = number; chosenOn = today.toString()
    }

    var retry by rememberSaveable { mutableIntStateOf(0) }
    var body by memory::body
    var loadedFor by memory::loadedFor
    var loadedAt by memory::loadedAt
    var loadedRetry by memory::loadedRetry
    var failed by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf(false) }
    val request = active ?: "сегодня $today"
    LaunchedEffect(request, retry, ScreenClock.resumes, memory.nudge) {
        if (loadedFor == request && loadedRetry == retry &&
            System.currentTimeMillis() - loadedAt < ROOMS_FRESH_MILLIS
        ) return@LaunchedEffect
        failed = false
        missing = false
        val answer = load(active)
        val got = answer.body
        answered = answer.answered
        when {
            got != null -> {
                body = got; loadedFor = request; loadedAt = System.currentTimeMillis(); loadedRetry = retry
            }
            answer.missing -> missing = true
            else -> failed = true
        }
    }

    val shown = body
    val ready = shown != null && loadedFor == request
    if (shown == null) {
        Status(failed, missing, active == null, answered) { retry++ }
        return
    }

    val numbers = shown.bells.keys.mapNotNull { it.toIntOrNull() }.sorted()
    val days = shown.days.ifEmpty { listOf(shown.date) }.take(DAYS_SHOWN)
    // Поздно вечером сегодняшние пары кончились — сразу к следующему дню, с первой пары.
    val lastEnd = numbers.lastOrNull()?.let { clock(shown.bells[it.toString()]?.getOrNull(1)) }
    if (ready && active == null && shown.date == today.toString() && days.size > 1 && lastEnd != null && now >= lastEnd) {
        LaunchedEffect(Unit) { choose(days[1], 0) }
    }
    // Сегодня — идущая или следующая пара, иначе первая.
    val chosen = activePair.takeIf { it in numbers } ?: run {
        if (shown.date != today.toString()) numbers.firstOrNull() ?: 1
        else numbers.firstOrNull { n -> clock(shown.bells[n.toString()]?.getOrNull(1))?.let { now < it } == true }
            ?: numbers.lastOrNull() ?: 1
    }
    // Идущая сейчас пара — своей рамкой, чтобы её было видно и при другой выбранной.
    val running = if (shown.date != today.toString()) null else numbers.firstOrNull { n ->
        val span = shown.bells[n.toString()]
        val from = clock(span?.getOrNull(0))
        val to = clock(span?.getOrNull(1))
        from != null && to != null && now >= from && now < to
    }
    val busy = shown.busy[chosen.toString()].orEmpty()
    // Кабинет в списках — с корпусом, если он не главный: «Вход 2, 55», «Восход, 221»,
    // как он стоит в карточках свободных.
    val buildingOf = remember(shown) {
        shown.buildings.drop(1).flatMap { b -> b.rooms.map { it.room to b.name } }.toMap()
    }
    fun place(room: String): String {
        val building = buildingOf[room] ?: return room
        return "$building, " + room.removePrefix("$building ")
    }
    val clashes = shown.clashes.filter { it.number == chosen }
    // Кнопка горит у дня на экране, а пока выбранный грузится или не пришёл — у него.
    val selected = if (ready) shown.date else active ?: shown.date

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        item(key = "дни") {
            ChipsLabel("День")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                days.forEach { d ->
                    val day = runCatching { LocalDate.parse(d) }.getOrNull()
                    val label = when (day) {
                        today -> "Сегодня"
                        today.plusDays(1) -> "Завтра"
                        null -> d
                        else -> formatDayRange(day, day).substringBefore(',')
                    }
                    FilterChip(
                        // Тот же день ещё раз — повтор, если он не загрузился.
                        selected = d == selected, onClick = { if (d == active) retry++ else choose(d, 0) },
                        // Крупный шрифт — надпись мельчает, а не обрезается до «Сегод» (FIT, как у кнопок).
                        label = { Text(label, maxLines = 1, autoSize = FIT, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (!ready) {
            item(key = "статус") { Status(failed, missing, active == null, answered) { retry++ } }
        } else {
        item(key = "пары") {
            ChipsLabel("Пара")
            // Номера пар делят строку поровну: шесть кнопок не переносятся и на узком экране.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                numbers.forEach { n ->
                    val start = shown.bells[n.toString()]?.getOrNull(0)
                    val accent = MaterialTheme.colorScheme.primary
                    FilterChip(
                        selected = n == chosen, onClick = { choose(active, n) },
                        label = {
                            Text(
                                "$n", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                                fontWeight = if (n == running) FontWeight.Bold else null,
                            )
                        },
                        border = if (n == running) {
                            FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = n == chosen,
                                borderColor = accent, selectedBorderColor = accent,
                                borderWidth = 2.dp, selectedBorderWidth = 2.dp,
                            )
                        } else {
                            FilterChipDefaults.filterChipBorder(enabled = true, selected = n == chosen)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val span = shown.bells[chosen.toString()].orEmpty()
            Text(
                "$chosen пара" + (span.getOrNull(0)?.let { ", $it–" + span.getOrNull(1).orEmpty() } ?: "") +
                    (if (chosen == running) " · идёт сейчас" else ""),
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
                        Text(building.name, fontWeight = FontWeight.SemiBold)
                        // Из тех, где по таблице бывают пары: других кабинетов колледжа в ней нет (пояснение внизу).
                        Text(
                            "свободно ${free.size} из " + plural(building.rooms.size, "кабинета", "кабинетов", "кабинетов"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Ровно под строкой выше, без своего отступа (Note — для текста вне карточки).
                        if (free.isEmpty()) {
                            Text(
                                "Свободных по расписанию нет.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        val byFloor = free.groupBy { it.floor }.toSortedMap()
                        // Этажа не знает ни один кабинет корпуса (Вход 2) — без пустой колонки подписей.
                        val floors = byFloor.keys.any { it.isNotEmpty() }
                        byFloor.forEach { (floor, rooms) ->
                            Row(Modifier.padding(top = 6.dp)) {
                                // В одну строку и шире при крупном шрифте: «эта / ж» не читается.
                                if (floors) Text(
                                    if (floor.isNotEmpty()) "$floor этаж" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.widthIn(min = 48.dp).padding(top = 4.dp, end = 4.dp),
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                // В таблице на одно время в кабинет поставлены разные пары —
                // ошибка колледжа или перенос: кто-то из них будет в другом месте.
                Section("Две пары в одном кабинете")
                Note("По таблице здесь в одно время разные пары — уточните, где ваша.")
                Card { clashes.forEachIndexed { i, c -> if (i > 0) Divider(); Uses(place(c.room), c.lessons) } }
            }
        }
        if (busy.isNotEmpty()) {
            item(key = "заняты") {
                var open by rememberSaveable { mutableStateOf(false) }
                Text(
                    (if (open) "▾ " else "▸ ") + "Кто где занят — " + plural(busy.size, "кабинет", "кабинета", "кабинетов"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                )
                if (open) Card { busy.toSortedMap().entries.forEachIndexed { i, (room, uses) -> if (i > 0) Divider(); Uses(place(room), uses) } }
            }
        }
        item(key = "пояснение") {
            Note(
                "Показано по расписанию колледжа. В списке — кабинеты, где по нему бывают пары; переносов и мероприятий в нём нет." +
                    (if (shown.unread.isEmpty()) "" else " Не учтены кабинеты " +
                        plural(shown.unread.size, "группы", "групп", "групп") +
                        ", у которых день не прочитан: " + shown.unread.joinToString(", ") + ".") +
                    (if (shown.unpublished == 0) "" else " Не учтены кабинеты " +
                        plural(shown.unpublished, "группы", "групп", "групп") +
                        ", которым колледж ещё не выложил этот день."),
            )
        }
        }
    }
}

/** Загрузка, сбой или «дня в таблице нет» — с повтором, где он поможет. */
@Composable
private fun Status(failed: Boolean, missing: Boolean, today: Boolean, answered: Boolean, retry: () -> Unit) {
    when {
        missing -> Explanation(
            if (today) "Ближайших дней в таблице нет" else "Этого дня в таблице нет",
            "Колледж ещё не выложил расписание.",
        )
        failed -> Explanation("Аудитории не загрузились", failureText(answered), onRetry = retry)
        else -> Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
    }
}

/** Ответ полежал — при возврате на экран перечитать: колледж правит лист и днём. */
private const val ROOMS_FRESH_MILLIS = 5 * 60 * 1000L

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
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(start = 4.dp, top = 16.dp),
)

@Composable
private fun Card(content: @Composable () -> Unit) = Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
) { Column { content() } }

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.divider)

@Composable
private fun RoomChip(room: String) = Text(
    room,
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.SemiBold,
    // Свободный кабинет — зелёным: красный читался как «занято».
    color = MaterialTheme.colorScheme.online,
    modifier = Modifier
        .background(MaterialTheme.colorScheme.online.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
        .padding(horizontal = 8.dp, vertical = 3.dp),
)

/** Кабинет и кто в нём: каждая пара — своей строкой (группа, предмет, преподаватель). */
@Composable
private fun Uses(room: String, uses: List<RoomUseDto>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(room, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        uses.forEach { u ->
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Medium)) { append(u.group) }
                    // Точка держится за предметом: строка не начинается с «· Фамилия».
                    append(" " + u.subject + (u.teachers.firstOrNull()?.let { "\u00A0· $it" } ?: ""))
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ChipsLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
)
