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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.SubjectNames

/**
 * Таблица сокращений: свои названия предметов. Свёрнутое имя пишется здесь и
 * хранится на телефоне; пустое поле — «как у службы» (короткое или полное).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubjectsScreen(
    schedule: ScheduleDto?,
    names: SubjectNames,
    onSave: (String, String?) -> Unit,
    onBack: () -> Unit,
) {
    // Один предмет — одна строка: у пары в разных днях имя одно и то же.
    val subjects = remember(schedule) {
        schedule?.days.orEmpty()
            .flatMap { it.lessons }
            .filterNot { it.isCancelled }
            .distinctBy { it.subject }
            .sortedBy { it.subject.lowercase() }
    }
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
            items(subjects, key = { it.subject }) { lesson ->
                SubjectRow(lesson, names, onSave)
            }
        }
    }
}

@Composable
private fun SubjectRow(lesson: LessonDto, names: SubjectNames, onSave: (String, String?) -> Unit) {
    val saved = names.custom[lesson.subject].orEmpty()
    // По умолчанию — что показал бы служба: короткое или полное.
    val fallback = names.text(lesson.copy(shortSubject = lesson.shortSubject))
    var text by remember(lesson.subject, saved) { mutableStateOf(saved) }
    // Пишем не на каждую букву: хранилище — на диск, а человек печатает быстро.
    LaunchedEffect(text) {
        if (text.trim() == saved.trim()) return@LaunchedEffect
        delay(700)
        onSave(lesson.subject, text.takeIf { it.isNotBlank() })
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            lesson.subject,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(fallback) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
