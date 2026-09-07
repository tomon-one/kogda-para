package ru.whensclass.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ru.whensclass.R
import ru.whensclass.data.GroupDto

/**
 * Выбор группы из почти двух сотен.
 *
 * Без поиска список бесполезен: пролистывать 187 строк, чтобы найти свою, —
 * ровно та морока, от которой мы уходим.
 */
@Composable
fun GroupPickerScreen(
    loadGroups: suspend () -> List<GroupDto>,
    onPick: (GroupDto) -> Unit,
) {
    var groups by remember { mutableStateOf<List<GroupDto>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { groups = loadGroups() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Image(
            painter = painterResource(R.drawable.logo_ngok),
            contentDescription = null,
            modifier = Modifier.height(48.dp),
        )
        Text(
            "Выберите свою группу",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Поиск") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )

        val list = groups
        when {
            list == null -> CircularProgressIndicator()

            list.isEmpty() -> Text(
                "Список групп не загрузился. Проверьте, есть ли интернет.",
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> {
                // Отбор считаем только когда меняется запрос или сам список.
                // Иначе он пересчитывался на каждую перерисовку — а перерисовка
                // случается на каждую букву, и список из 187 строк заметно
                // подтормаживал при наборе.
                val filtered = remember(list, query) {
                    if (query.isBlank()) list
                    else list.filter { it.name.contains(query.trim(), ignoreCase = true) }
                }
                if (filtered.isEmpty()) {
                    Text("Ничего не нашлось", style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn {
                    items(filtered, key = { it.id }) { group ->
                        Text(
                            group.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(group) }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}
