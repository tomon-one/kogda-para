package ru.whensclass.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import ru.whensclass.data.GroupDto
import ru.whensclass.data.ScheduleDto

/**
 * Расписание преподавателя.
 *
 * Отдельного листа с преподавателями в таблице колледжа нет — сервер собирает
 * его, переворачивая расписание групп. Поэтому здесь у каждой пары написано,
 * каким группам она читается.
 */
@Composable
fun TeacherScreen(
    teachers: List<GroupDto>?,
    loadSchedule: suspend (String) -> ScheduleDto?,
    pinned: List<String> = emptyList(),
    onTogglePin: (String) -> Unit = {},
    reloadKey: Int = 0,
    ownSchedule: ScheduleDto? = null,
    searchLabel: String = "Поиск по фамилии",
    showGroups: Boolean = true,
    selfId: String? = null,
    ownScheduleTitle: String = "Посмотреть других преподавателей",
    othersTitle: String = "Другие преподаватели",
) {
    // В роли преподавателя его собственное расписание уже лежит на телефоне:
    // показываем сразу, без похода в сеть. Список остальных — по кнопке.
    var browsing by remember { mutableStateOf(false) }
    if (ownSchedule != null && !browsing) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScheduleDays(
                schedule = ownSchedule,
                today = remember { LocalDate.now() },
                showGroups = showGroups,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { browsing = true },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(ownScheduleTitle)
            }
        }
        return
    }

    var picked by remember { mutableStateOf<GroupDto?>(null) }
    var schedule by remember { mutableStateOf<ScheduleDto?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // reloadKey меняется по нажатию на обновление: перечитываем расписание
    // того преподавателя, который сейчас открыт.
    LaunchedEffect(picked, reloadKey) {
        val teacher = picked ?: return@LaunchedEffect
        loading = true
        schedule = loadSchedule(teacher.id)
        loading = false
    }

    val chosen = picked
    if (chosen != null) {
        ChosenTeacher(
            teacher = chosen,
            schedule = schedule,
            loading = loading,
            onBack = {
                picked = null
                schedule = null
            },
        )
        return
    }

    if (ownSchedule != null) {
        TextButton(onClick = { browsing = false }, modifier = Modifier.padding(start = 8.dp)) {
            Text("Вернуться к своему расписанию")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(searchLabel) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val list = teachers
        when {
            list == null -> Centered { CircularProgressIndicator() }

            list.isEmpty() -> Centered {
                Text(
                    "Список преподавателей не загрузился. Проверьте интернет.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> {
                val found = remember(list, query) {
                    if (query.isBlank()) list
                    else list.filter { it.name.contains(query.trim(), ignoreCase = true) }
                }
                // Закреплённые — отдельной группой сверху, а не просто первыми
                // строками: так видно, что это именно закреплённые, и они не
                // перелетают через весь список, когда их снимают.
                // Сам человек — отдельной строкой над всеми: за своим
                // расписанием заходят чаще, чем за чужим.
                val self = remember(found, selfId) { found.firstOrNull { it.id == selfId } }
                val favourites = remember(found, pinned, selfId) {
                    found.filter { it.id in pinned && it.id != selfId }
                }
                val others = remember(found, pinned, selfId) {
                    found.filter { it.id !in pinned && it.id != selfId }
                }

                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    self?.let { own ->
                        item(key = "self-title") { SectionTitle("Ваше расписание") }
                        item(key = "self") {
                            TeacherRow(
                                teacher = own,
                                pinned = own.id in pinned,
                                isSelf = true,
                                onOpen = { picked = own },
                                onTogglePin = { onTogglePin(own.id) },
                            )
                        }
                    }
                    if (favourites.isNotEmpty()) {
                        item(key = "pinned") { SectionTitle("Закреплённые") }
                        items(
                            favourites,
                            key = { "p-${it.id}" },
                            contentType = { "teacher" },
                        ) { teacher ->
                            TeacherRow(
                                teacher = teacher,
                                pinned = true,
                                isSelf = teacher.id == selfId,
                                onOpen = { picked = teacher },
                                onTogglePin = { onTogglePin(teacher.id) },
                            )
                        }
                    }
                    if (others.isNotEmpty() && (self != null || favourites.isNotEmpty())) {
                        item(key = "others") { SectionTitle(othersTitle) }
                    }
                    items(others, key = { it.id }, contentType = { "teacher" }) { teacher ->
                        TeacherRow(
                            teacher = teacher,
                            pinned = false,
                            isSelf = teacher.id == selfId,
                            onOpen = { picked = teacher },
                            onTogglePin = { onTogglePin(teacher.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChosenTeacher(
    teacher: GroupDto,
    schedule: ScheduleDto?,
    loading: Boolean,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                teacher.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("Другой") }
        }

        when {
            loading -> Centered { CircularProgressIndicator() }
            schedule == null -> Centered {
                Text("Расписание не загрузилось", style = MaterialTheme.typography.bodyMedium)
            }
            else -> ScheduleDays(
                schedule = schedule,
                today = remember { LocalDate.now() },
                showGroups = true,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun TeacherRow(
    teacher: GroupDto,
    pinned: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    isSelf: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(teacher.name, style = MaterialTheme.typography.bodyLarge)
                // Себя человек ищет в списке первым делом — отмечаем.
                if (isSelf) {
                    Text(
                        "это вы",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                if (pinned) "★" else "☆",
                style = MaterialTheme.typography.titleMedium,
                color = if (pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onTogglePin)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        content()
    }
}
