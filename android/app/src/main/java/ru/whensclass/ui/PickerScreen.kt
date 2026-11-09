package ru.whensclass.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * Выбор из почти двух сотен — с поиском: без него список бесполезен. Студент
 * выбирает группу (свою или ещё одну), преподаватель — себя: дальше
 * приложение работает поверх выбранного расписания — экран, виджеты, напоминания.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    /** Список держит App: сохранённый — сразу, свежий — следом. */
    items: List<GroupDto>?,
    onPick: (GroupDto) -> Unit,
    /** Чей список: группы или преподаватели — от этого заголовок, поиск и подписи. */
    teachers: Boolean = false,
    onRetry: (() -> Unit)? = null,
    /** Заголовок: при выборе другой группы — не «Выберите группу», это не смена своей. */
    title: String = if (teachers) "Найдите себя в списке" else "Выберите группу",
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    /** Строка смены роли над списком: студенту — «Я преподаватель», преподавателю — «Я студент». */
    onSwitchRole: (() -> Unit)? = null,
    loadDiagnostics: (suspend () -> String)? = null,
    /** Сервер эту сборку не обслуживает (426): вместо «проверьте интернет» — к обновлению. */
    onUpdate: (() -> Unit)? = null,
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        // Список отступает от клавиатуры: при enableEdgeToEdge окно под неё
        // не ужимается.
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            SearchField(query, { query = it }, if (teachers) "Поиск по фамилии" else "Поиск по названию",
                Modifier.padding(horizontal = 16.dp))

            val list = items
            when {
                list == null -> Loading()

                list.isEmpty() && onUpdate != null -> Unsupported(onUpdate, "Сервер больше не поддерживает эту версию: список не загрузится, пока приложение не обновят.")

                // До настроек отсюда не дойти, пока ничего не выбрано: адрес и отчёт — здесь же.
                list.isEmpty() -> LoadFailed(if (teachers) "Список преподавателей" else "Список групп", loadDiagnostics, onRetry)

                else -> {
                    // Отбор — только при смене запроса или списка, не на каждую
                    // перекомпоновку.
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
                        // Преподавателю нужна не группа, а собственное расписание, и наоборот.
                        onSwitchRole?.let { switchRole ->
                            item(key = "роль") { RoleSwitchRow(if (teachers) "Я студент" else "Я преподаватель", switchRole) }
                        }
                        lettered(filtered, name = { it.name }, key = { it.id }, onPick = onPick)
                        // Дно списка — для тех, кто листает, а не ищет.
                        if (query.isBlank()) {
                            item(key = "конец") {
                                ListEnd(
                                    "Всё. " +
                                        (if (teachers) plural(list.size, "преподаватель", "преподавателя", "преподавателей")
                                        else plural(list.size, "группа", "группы", "групп")) +
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
