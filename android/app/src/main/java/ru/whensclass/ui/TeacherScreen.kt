package ru.whensclass.ui

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
    loadTeachers: suspend () -> List<GroupDto>,
    loadSchedule: suspend (String) -> ScheduleDto?,
) {
    var teachers by remember { mutableStateOf<List<GroupDto>?>(null) }
    var picked by remember { mutableStateOf<GroupDto?>(null) }
    var schedule by remember { mutableStateOf<ScheduleDto?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { teachers = loadTeachers() }
    LaunchedEffect(picked) {
        val teacher = picked ?: return@LaunchedEffect
        loading = true
        schedule = loadSchedule(teacher.id)
        loading = false
    }

    val chosen = picked
    if (chosen != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    chosen.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { picked = null; schedule = null }) { Text("Другой") }
            }

            when {
                loading -> Centered { CircularProgressIndicator() }
                schedule == null -> Centered {
                    Text(
                        "Расписание не загрузилось",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> ScheduleDays(
                    schedule = schedule!!,
                    today = remember { LocalDate.now() },
                    showGroups = true,
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Поиск по фамилии") },
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
                val filtered = remember(list, query) {
                    if (query.isBlank()) list
                    else list.filter { it.name.contains(query.trim(), ignoreCase = true) }
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered, key = { it.id }) { teacher ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                teacher.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { picked = teacher }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
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
