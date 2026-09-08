package ru.whensclass.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.R
import ru.whensclass.data.GroupDto
import ru.whensclass.widget.plural

/** Сообщение о неудачной загрузке — со ссылкой, куда написать. */
@Composable
private fun LoadFailed() {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "Список групп не загрузился. Проверьте интернет или напишите",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "@toomonn",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/toomonn"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                .padding(vertical = 4.dp),
        )
    }
}

/**
 * Выбор группы из почти двух сотен.
 *
 * Без поиска список бесполезен: пролистывать 187 строк, чтобы найти свою, —
 * ровно та морока, от которой мы уходим.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerScreen(
    loadGroups: suspend () -> List<GroupDto>,
    onPick: (GroupDto) -> Unit,
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    onTeacherMode: (() -> Unit)? = null,
) {
    var groups by remember { mutableStateOf<List<GroupDto>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { groups = loadGroups() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Выберите группу",
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!canGoBack) {
                // Первый запуск: логотип уместен, дальше он только мешает.
                Image(
                    painter = painterResource(R.drawable.logo_ngok),
                    contentDescription = null,
                    modifier = Modifier
                        .height(40.dp)
                        .padding(start = 16.dp, bottom = 4.dp),
                )
            }

            onTeacherMode?.let { switchRole ->
                // Приложением пользуются и преподаватели: им нужна не группа,
                // а собственное расписание.
                TextButton(
                    onClick = switchRole,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Я преподаватель")
                }
            }

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

                list.isEmpty() -> LoadFailed()

                else -> {
                    // Отбор считаем только когда меняется запрос или сам список:
                    // иначе он пересчитывался на каждую букву, и список из 187
                    // строк заметно подтормаживал.
                    val filtered = remember(list, query) {
                        if (query.isBlank()) list
                        else list.filter { it.name.contains(query.trim(), ignoreCase = true) }
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
                        contentPadding = PaddingValues(
                            start = 12.dp, end = 12.dp, bottom = 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filtered, key = { it.id }) { group ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable { onPick(group) }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                )
                            }
                        }
                        // Дно списка. Видит только тот, кто долистал до
                        // конца вместо того, чтобы искать поиском.
                        if (query.isBlank()) {
                            item(key = "конец") {
                                ListEnd(
                                    "Всё. " +
                                        plural(list.size, "группа", "группы", "групп") +
                                        ", и ни одной лишней.",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
