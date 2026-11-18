package ru.whensclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.SubjectLine
import ru.whensclass.data.SubjectNames
import ru.whensclass.data.subjectLines

/**
 * Таблица сокращений: свои названия предметов. Свёрнутое имя пишется здесь и
 * хранится на телефоне; пустое поле — «как у службы» (короткое или полное).
 * Записывается кнопкой: человек видит, что и когда сохранилось.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubjectsScreen(
    schedule: ScheduleDto?,
    names: SubjectNames,
    onSave: suspend (Map<String, String?>) -> Unit,
    onBack: () -> Unit,
) {
    // Один предмет — одна строка: у пары в разных днях имя одно и то же, а пометка
    // «ЗАЧЕТ» делает предмет вторым вариантом той же пары, не вторым предметом.
    val subjects = remember(schedule) {
        subjectLines(schedule?.days.orEmpty().flatMap { it.lessons })
    }
    // Правки до сохранения: кнопка внизу пишет их разом.
    var edits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    fun saved(line: SubjectLine): String =
        line.variants.firstNotNullOfOrNull { names.custom[it]?.takeIf { text -> text.isNotBlank() } }.orEmpty()
    fun value(line: SubjectLine): String = edits[line.subject] ?: saved(line)
    val changed = subjects.filter { value(it).trim() != saved(it).trim() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Названия предметов",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ActionButton(
                label = "Сохранить",
                enabled = changed.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                onClick = {
                    val changes = buildMap {
                        changed.forEach { line ->
                            val text = value(line).trim().ifEmpty { null }
                            line.variants.forEach { full -> put(full, text) }
                        }
                    }
                    scope.launch {
                        onSave(changes)
                        snackbar.showSnackbar("Сохранено", duration = SnackbarDuration.Short)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "подсказка") {
                Text(
                    "Пустое поле — название из словаря или полное. Своё видят только этот телефон " +
                        "и только в приложении: в уведомлениях сайта остаётся словарь.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            if (subjects.isEmpty()) {
                item(key = "пусто") {
                    Text(
                        "Расписание ещё не загружено — предметов пока нет.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(subjects, key = { it.subject }) { line ->
                SubjectRow(
                    line = line,
                    // Подсказка — что показал бы служба сейчас: короткое имя или полное.
                    fallback = if (names.short) {
                        line.short?.replaceFirstChar { it.uppercase() } ?: line.subject
                    } else {
                        line.subject
                    },
                    value = value(line),
                    onChange = { edits = edits + (line.subject to it) },
                )
            }
        }
    }
}

@Composable
private fun SubjectRow(line: SubjectLine, fallback: String, value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            line.subject,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text(fallback) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
