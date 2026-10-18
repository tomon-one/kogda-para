package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ru.whensclass.data.GroupDto
import ru.whensclass.data.RetakeDto
import ru.whensclass.data.RetakesDto
import ru.whensclass.widget.formatDayRange
import ru.whensclass.widget.plural
import java.time.LocalDate

/**
 * Лист пересдач колледжа: по преподавателям, без групп и предметов. Даты
 * служба разбирает по дням, а что преподаватель ведёт сейчас — берёт из
 * расписания (docs/api.md, /v1/retakes). То же, что вкладка на сайте.
 *
 * [retakes] null — грузится; [failed] — не загрузился.
 */
@Composable
internal fun RetakesScreen(
    retakes: RetakesDto?,
    failed: Boolean,
    today: LocalDate,
    onRetry: () -> Unit,
    /** Не загрузились, хотя сервер ответил, — иначе нет связи. */
    answered: Boolean = false,
    /** Преподаватель найден в расписании — нажатие на имя открывает его расписание. */
    onOpenTeacher: (String) -> Unit = {},
    /**
     * Список преподавателей: имя полностью — оттуда по id. В листе пересдач
     * «Фамилия И.О.», инициалы читаются хуже; нет в списке — как в листе.
     */
    teachers: List<GroupDto>? = null,
) {
    val fullNames = remember(teachers) { teachers.orEmpty().associate { it.id to it.name } }
    fun named(id: String?, written: String) = id?.let { fullNames[it] } ?: written
    fun nameOf(row: RetakeDto) = named(row.teacherId, row.teacher)
    if (retakes == null) {
        if (failed) {
            Explanation("Пересдачи не загрузились", failureText(answered), onRetry = onRetry)
        } else {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    if (retakes.rows.isEmpty()) {
        Explanation("Пересдач нет", "Графика пересдач в таблице колледжа сейчас нет.")
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    // Ищут того, у кого долг: если его долги принимает другой, найдётся и
    // строка «долги принимает», и сам принимающий.
    val redirects = remember(retakes) {
        retakes.redirects.flatMap { r ->
            r.teachers.mapIndexed { i, name -> Moved(name, r.teacherIds.getOrNull(i), r.to, r.toId) }
        }
    }
    val q = query.trim()
    val moved = if (q.isEmpty()) emptyList() else redirects.filter {
        matchesQuery(it.name, q) || matchesQuery(named(it.id, it.name), q)
    }
    val rows = if (q.isEmpty()) retakes.rows else retakes.rows.filter { row ->
        matchesQuery(row.teacher, q) || matchesQuery(nameOf(row), q) || row.subjects.any { matchesQuery(it, q) } ||
            moved.any { matchesQuery(row.teacher, it.to) }
    }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
        item(key = "заголовок") {
            Text(
                retakes.title ?: "Пересдачи",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        item(key = "памятка") { Memo() }
        item(key = "поиск") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Фамилия или предмет") },
                trailingIcon = { ClearSearch(query) { query = "" } },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        if (rows.isEmpty() && moved.isEmpty()) {
            item(key = "пусто") {
                Text(
                    "Ничего не нашлось. Вопросы — куратору, декану или руководителю направления, в учебный офис (каб. 267).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            // Строка — свой элемент списка: одной карточкой семьдесят строк
            // строились бы разом, и вкладка открывалась бы с заминкой.
            val count = moved.size + rows.size
            moved.forEachIndexed { i, m ->
                item(key = "перенос:$i") { Piece(i, count) { RedirectRow(named(m.id, m.name), named(m.toId, m.to)) } }
            }
            rows.forEachIndexed { i, row ->
                item(key = "строка:$i") { Piece(moved.size + i, count) { RetakeRow(row, nameOf(row), today, onOpenTeacher) } }
            }
        }
        if (q.isEmpty()) {
            item(key = "конец") {
                ListEnd("Всё. " + plural(retakes.rows.size, "преподаватель", "преподавателя", "преподавателей") + ".")
            }
            if (redirects.isNotEmpty()) {
                item(key = "другие") {
                    Text(
                        "Долги принимает другой преподаватель",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
                    )
                    Card {
                        redirects.forEachIndexed { i, m ->
                            if (i > 0) Divider()
                            RedirectRow(named(m.id, m.name), named(m.toId, m.to))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Memo() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            "График составлен по преподавателям: найдите того, у кого долг, и приходите строго в указанное время.",
            "По прошедшим датам колледж договаривался с каждым отдельно.",
            "Вопросы — куратору, декану или руководителю направления, в учебный офис (каб. 267).",
        ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

/** Кусок общей карточки: скруглён сверху у первой строки и снизу у последней. */
@Composable
private fun Piece(index: Int, count: Int, content: @Composable () -> Unit) {
    val top = if (index == 0) 12.dp else 0.dp
    val bottom = if (index == count - 1) 12.dp else 0.dp
    Surface(
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (index > 0) Divider()
            content()
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.background)

@Composable
private fun Line(label: String, text: String, muted: Boolean = false) {
    if (text.isBlank()) return
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = labelColor)) { append("$label ") }
            append(text)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) labelColor else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun RetakeRow(row: RetakeDto, name: String, today: LocalDate, onOpenTeacher: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Нашёлся в расписании — имя цветом и ведёт к его парам, как на сайте.
        val id = row.teacherId
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (id != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = if (id != null) {
                Modifier.clickable(onClickLabel = "Расписание преподавателя") { onOpenTeacher(id) }
            } else {
                Modifier
            },
        )
        if (row.slots.isEmpty()) {
            // Даты не разобрались — как в таблице.
            Line("Дата:", row.dates)
            Line("Время:", row.time)
        } else {
            row.slots.forEach { slot ->
                val from = runCatching { LocalDate.parse(slot.from) }.getOrNull()
                val to = runCatching { LocalDate.parse(slot.to) }.getOrNull()
                val past = to != null && to < today
                val day = if (from != null && to != null) formatDayRange(from, to) else slot.from
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Medium,
                                textDecoration = if (past) TextDecoration.LineThrough else null,
                            ),
                        ) { append(day) }
                        if (slot.time.isNotBlank()) {
                            withStyle(SpanStyle(textDecoration = if (past) TextDecoration.LineThrough else null)) {
                                append("  " + slot.time)
                            }
                        }
                        if (past) withStyle(SpanStyle(color = muted)) { append("  прошло") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (past) muted else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Line("Кабинет:", row.room)
        if (row.subjects.isNotEmpty()) Line("Ведёт сейчас:", row.subjects.take(3).joinToString("; "), muted = true)
    }
}

/** Строка второй таблицы: как написано в листе и id людей, если нашлись. */
private data class Moved(val name: String, val id: String?, val to: String, val toId: String?)

@Composable
private fun RedirectRow(name: String, to: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Line("Долги принимает", to)
    }
}

/** Почему раздел не загрузился: сервер ответил ошибкой — дело не в интернете. */
internal fun failureText(answered: Boolean) =
    if (answered) "Сервер не смог их отдать. Повторите позже." else "Нет связи с сервером. Проверьте интернет."
