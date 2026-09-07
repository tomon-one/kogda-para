package ru.whensclass.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.BuildConfig
import ru.whensclass.data.ReleaseDto
import ru.whensclass.widget.ThemeChoice

/**
 * Настройки и короткий честный рассказ о данных.
 *
 * Тексты намеренно немногословны: это приложение для одногруппников, а не
 * пользовательское соглашение, которое всё равно никто не читает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    groupName: String?,
    teacherMode: Boolean,
    onSwitchRole: () -> Unit,
    theme: ThemeChoice,
    update: ReleaseDto?,
    installing: Boolean,
    notifyBefore: Int,
    notifyChanges: Boolean,
    onNotifyBefore: (Int) -> Unit,
    onNotifyChanges: (Boolean) -> Unit,
    focusUpdate: Boolean,
    checkingUpdate: Boolean,
    updateChecked: Boolean,
    onCheckUpdate: () -> Unit,
    onTheme: (ThemeChoice) -> Unit,
    onChangeGroup: () -> Unit,
    onRefresh: () -> Unit,
    onUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    var askOwnTime by remember { mutableStateOf(false) }
    if (askOwnTime) {
        OwnTimeDialog(
            current = notifyBefore,
            onDismiss = { askOwnTime = false },
            onPick = {
                onNotifyBefore(it)
                askOwnTime = false
            },
        )
    }

    val scroll = rememberScrollState()
    var updateOffset by remember { mutableIntStateOf(0) }

    // Пришли по значку обновления — сразу прокручиваем к нему: раздел стоит
    // внизу, и искать его глазами не надо.
    LaunchedEffect(focusUpdate, updateOffset) {
        if (focusUpdate && updateOffset > 0) scroll.animateScrollTo(updateOffset)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Настройки",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Section(if (teacherMode) "Преподаватель" else "Группа") {
            Text(groupName ?: "не выбрано", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onChangeGroup) {
                Text(if (teacherMode) "Сменить преподавателя" else "Сменить группу")
            }

        }

        Section("Оформление") {
            ThemeOption("Как в системе", ThemeChoice.SYSTEM, theme, onTheme)
            ThemeOption("Тёмная", ThemeChoice.DARK, theme, onTheme)
            ThemeOption("Светлая", ThemeChoice.LIGHT, theme, onTheme)
        }

        Section("Уведомления") {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Напоминать о паре", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = notifyBefore > 0,
                    onCheckedChange = { onNotifyBefore(if (it) DEFAULT_NOTIFY_BEFORE else 0) },
                )
            }
            if (notifyBefore > 0) {
                Text(
                    "За сколько предупредить",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Разброс большой: кому-то хватит десяти минут, а кому-то ехать
                // через весь город.
                FlowRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    NOTIFY_OPTIONS.forEach { minutes ->
                        MinutesChip(
                            minutes = minutes,
                            selected = minutes == notifyBefore,
                            onPick = { onNotifyBefore(minutes) },
                        )
                    }
                    // Готовых значений хватает не всем: кто-то едет ровно сорок
                    // семь минут и хочет именно столько.
                    MinutesChip(
                        label = if (notifyBefore !in NOTIFY_OPTIONS) minutesLabel(notifyBefore)
                        else "Своё",
                        selected = notifyBefore !in NOTIFY_OPTIONS,
                        onPick = { askOwnTime = true },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Сообщать об изменениях", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = notifyChanges, onCheckedChange = onNotifyChanges)
            }
            Text(
                "Отмены и замены на сегодня и завтра.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Расписание") {
            Text(
                "Приложение забирает расписание каждый час и при каждом открытии. " +
                    "Сервер перечитывает таблицу колледжа каждые 20 минут и ещё раз " +
                    "перед каждой парой.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRefresh) { Text("Обновить расписание") }
        }

        Section("Данные") {
            Text(
                // У преподавателя на сервер уходит не группа, а он сам —
                // писать про группу было бы неправдой.
                if (teacherMode) {
                    "На сервер уходит только фамилия выбранного преподавателя — " +
                        "иначе непонятно, чьё расписание присылать. Больше ничего: " +
                        "ни ваших данных, ни номера, ни местоположения. Учётной " +
                        "записи нет, аналитики и рекламы нет."
                } else {
                    "На сервер уходит только название вашей группы. Больше ничего: " +
                        "ни имени, ни номера, ни местоположения. Учётной записи нет, " +
                        "аналитики и рекламы нет. Всё для вашего удобства ;)"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Section("Ответственность") {
            Text(
                "Расписание берётся из общей таблицы колледжа. Что написано там, " +
                    "то и покажет приложение: за ошибки, замены и опоздавшие " +
                    "обновления я не отвечаю.\n\n" +
                    "Если однажды что-то сломается, я постараюсь починить, но сроков " +
                    "не обещаю. Пропущенная пара остаётся на вашей совести, даже " +
                    "если приложение в этот момент показывало ерунду. Сверяйтесь " +
                    "с таблицей, когда это важно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(
            "Версия приложения",
            modifier = Modifier.onGloballyPositioned { updateOffset = it.positionInParent().y.toInt() },
        ) {
            Text(
                "Установлена ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (update != null) {
                Text(
                    "Вышла ${update.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (update.notes.isNotBlank()) {
                    Text(
                        update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Скачается с сервера приложения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onUpdate, enabled = !installing) {
                    Text(if (installing) "Скачиваю…" else "Обновить приложение")
                }
            } else {
                // Раньше кнопка молчала, когда обновления не было, и выглядела
                // сломанной. Теперь всегда отвечает.
                if (updateChecked && !checkingUpdate) {
                    Text(
                        "Установлена последняя версия",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCheckUpdate, enabled = !checkingUpdate) {
                    Text(
                        when {
                            checkingUpdate -> "Проверка…"
                            updateChecked -> "Проверить ещё раз"
                            else -> "Проверить обновления"
                        }
                    )
                }
            }
        }

        Section("О приложении") {
            Text(
                "Неофициальное приложение для студентов НГОК.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Link("Нашли ошибку? Напишите мне в Telegram", "https://t.me/toomonn")
            Link("GitHub автора", "https://github.com/tomon-one")
            Text(
                if (teacherMode) "Я студент" else "Я преподаватель",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSwitchRole)
                    .padding(top = 14.dp, bottom = 4.dp),
            )
            Text(
                "Создано Tomon",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
    }
}

/** Строка-ссылка: открывает адрес в браузере или в приложении Telegram. */
@Composable
private fun Link(text: String, url: String) {
    val context = LocalContext.current
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .padding(top = 10.dp, bottom = 2.dp),
    )
}

/** Насколько заранее можно попросить напоминание. */
private val NOTIFY_OPTIONS = listOf(10, 15, 20, 30, 45, 60, 90, 120, 180, 240)
private const val DEFAULT_NOTIFY_BEFORE = 20
private const val MIN_NOTIFY = 10
private const val MAX_NOTIFY = 240

private fun minutesLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes мин"
    minutes % 60 == 0 -> "${minutes / 60} ч"
    else -> "${minutes / 60} ч ${minutes % 60} мин"
}

/**
 * Своё время напоминания.
 *
 * Границы взяты из здравого смысла: меньше десяти минут предупреждать поздно,
 * дольше четырёх часов — уже не про эту пару.
 */
@Composable
private fun OwnTimeDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var value by remember { mutableStateOf(current.takeIf { it > 0 }?.toString().orEmpty()) }
    val minutes = value.toIntOrNull()
    val valid = minutes != null && minutes in MIN_NOTIFY..MAX_NOTIFY

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("За сколько предупредить") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { new -> value = new.filter { it.isDigit() }.take(3) },
                    label = { Text("Минут") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    "От $MIN_NOTIFY минут до ${MAX_NOTIFY / 60} часов",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { minutes?.let(onPick) }, enabled = valid) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun MinutesChip(
    minutes: Int = 0,
    label: String = minutesLabel(minutes),
    selected: Boolean,
    onPick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp).clickable(onClick = onPick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            content()
        }
    }
}

@Composable
private fun ThemeOption(
    label: String,
    value: ThemeChoice,
    current: ThemeChoice,
    onPick: (ThemeChoice) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Не ниже 48dp: попасть пальцем в строку списка иначе трудно.
            .heightIn(min = 48.dp)
            .selectable(selected = value == current, onClick = { onPick(value) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == current, onClick = { onPick(value) })
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
