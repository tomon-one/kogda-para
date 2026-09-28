package ru.whensclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Text
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
 * Выбор группы из почти двух сотен.
 *
 * Без поиска список бесполезен: пролистывать 187 строк, чтобы найти свою, —
 * ровно та морока, от которой мы уходим.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerScreen(
    /** Список держит App: сохранённый — сразу, свежий — следом. */
    groups: List<GroupDto>?,
    onPick: (GroupDto) -> Unit,
    onRetry: (() -> Unit)? = null,
    /** Заголовок: при выборе другой группы — не «Выберите группу», это не смена своей. */
    title: String = "Выберите группу",
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    onTeacherMode: (() -> Unit)? = null,
    loadDiagnostics: (suspend () -> String)? = null,
) {
    // Набранное переживает поворот.
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
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
        // Список отступает от клавиатуры: окно под неё не ужимается
        // (enableEdgeToEdge), и найденное пряталось под ней.
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Поиск по названию") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val list = groups
            when {
                list == null -> Row(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }

                list.isEmpty() -> LoadFailed("Список групп", loadDiagnostics, onRetry)

                else -> {
                    // Отбор считаем только когда меняется запрос или сам список:
                    // иначе он пересчитывался на каждую букву, и список из 187
                    // строк заметно подтормаживал.
                    val filtered = remember(list, query) {
                        if (query.isBlank()) list
                        else list.filter { matchesQuery(it.name, query) }
                    }
                    if (filtered.isEmpty()) {
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
                        // Приложением пользуются и преподаватели: им нужна не
                        // группа, а собственное расписание.
                        onTeacherMode?.let { switchRole ->
                            item(key = "роль") { RoleSwitchRow("Я преподаватель", switchRole) }
                        }
                        lettered(filtered, name = { it.name }, key = { it.id }, onPick = onPick)
                        // Дно списка. Видит только тот, кто долистал до
                        // конца вместо того, чтобы искать поиском.
                        if (query.isBlank()) {
                            item(key = "конец") {
                                ListEnd(
                                    "Всё. " +
                                        plural(list.size, "группа", "группы", "групп") +
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
