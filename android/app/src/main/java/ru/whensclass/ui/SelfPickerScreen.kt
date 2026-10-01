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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.data.GroupDto
import ru.whensclass.widget.plural

/**
 * Преподаватель выбирает себя.
 *
 * То же, что выбор группы у студента: дальше приложение работает поверх его
 * расписания — экран, виджеты, напоминания.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfPickerScreen(
    teachers: List<GroupDto>?,
    onPick: (GroupDto) -> Unit,
    onRetry: (() -> Unit)? = null,
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    onStudentMode: () -> Unit = {},
    loadDiagnostics: (suspend () -> String)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Найдите себя в списке",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        // От клавиатуры — как в выборе группы.
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Поиск по фамилии") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                teachers == null -> Row(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }

                // До настроек отсюда не дойти: себя ещё не выбрали — адрес
                // и отчёт здесь же.
                teachers.isEmpty() -> LoadFailed("Список преподавателей", loadDiagnostics, onRetry)

                else -> {
                    val filtered = remember(teachers, query) {
                        if (query.isBlank()) teachers
                        else teachers.filter { matchesQuery(it.name, query) }
                    }
                    if (filtered.isEmpty()) {
                        // Пустой экран после поиска читается как поломка.
                        Text(
                            "Ничего не нашлось",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    ) {
                        item(key = "роль") { RoleSwitchRow("Я студент", onStudentMode) }
                        lettered(filtered, name = { it.name }, key = { it.id }, onPick = onPick)
                        // Дно списка — для тех, кто листает, а не ищет.
                        if (query.isBlank()) {
                            item(key = "конец") {
                                ListEnd(
                                    "Всё. " +
                                        plural(
                                            teachers.size,
                                            "преподаватель",
                                            "преподавателя",
                                            "преподавателей",
                                        ) +
                                        ".",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
