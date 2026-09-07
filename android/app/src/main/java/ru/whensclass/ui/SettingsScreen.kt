package ru.whensclass.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    theme: ThemeChoice,
    update: ReleaseDto?,
    updateReady: Boolean,
    onTheme: (ThemeChoice) -> Unit,
    onChangeGroup: () -> Unit,
    onRefresh: () -> Unit,
    onUpdate: () -> Unit,
    onBack: () -> Unit,
) {
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        if (update != null) {
            Section("Обновление приложения") {
                Text(
                    "Доступна версия ${update.versionName}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (update.notes.isNotBlank()) {
                    Text(
                        update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (updateReady) "Файл скачан — осталось подтвердить установку."
                    else "Скачается с нашего сервера. Установку подтвердите вручную: " +
                        "молча ставить приложения Android не даёт.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onUpdate) {
                    Text(if (updateReady) "Установить" else "Скачать обновление")
                }
            }
        }

        Section("Группа") {
            Text(groupName ?: "не выбрана", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onChangeGroup) { Text("Выбрать другую") }
        }

        Section("Оформление") {
            ThemeOption("Как в системе", ThemeChoice.SYSTEM, theme, onTheme)
            ThemeOption("Тёмная", ThemeChoice.DARK, theme, onTheme)
            ThemeOption("Светлая", ThemeChoice.LIGHT, theme, onTheme)
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
                "На сервер уходит только название вашей группы — иначе непонятно, " +
                    "чьё расписание присылать. Больше ничего: ни имени, ни номера, " +
                    "ни местоположения. Учётной записи нет, аналитики и рекламы нет.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Section("Ответственность") {
            Text(
                "Расписание берётся из общей таблицы колледжа. Что написано там — " +
                    "то и покажет приложение: за ошибки, замены и опоздавшие " +
                    "обновления мы не отвечаем.\n\n" +
                    "Если однажды всё сломается — постараемся починить, но сроков " +
                    "не обещаем. Пропущенная пара остаётся на вашей совести, даже " +
                    "если приложение в этот момент показывало ерунду. Сверяйтесь " +
                    "с таблицей, когда это важно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("О приложении") {
            Text(
                "Версия ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Расписание НГОК для своих. Приложение неофициальное.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Link("Нашли ошибку — напишите в Telegram", "https://t.me/toomonn")
            Link("Исходный код и другие проекты", "https://github.com/Tomonj1")
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
            .heightIn(min = 48.dp)
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
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
