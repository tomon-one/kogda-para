package ru.whensclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.BuildConfig
import ru.whensclass.widget.ThemeChoice

/**
 * Настройки и короткий честный рассказ о данных.
 *
 * Тексты намеренно немногословны: это приложение для одногруппников, а не
 * пользовательское соглашение, которое всё равно никто не читает.
 */
@Composable
fun SettingsScreen(
    groupName: String?,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    onChangeGroup: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Настройки",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp),
            )
            TextButton(onClick = onBack) { Text("Готово") }
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
            Text("Версия ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Расписание НГОК для своих.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
