package ru.whensclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.whensclass.BuildConfig
import ru.whensclass.widget.ThemeChoice

/**
 * Настройки и честный рассказ о том, что уходит на сервер.
 *
 * Раздел про данные написан не для галочки: приложение действительно ничего,
 * кроме выбранной группы, никуда не отправляет, и это стоит уметь показать.
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
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Назад") }
        }

        Section("Группа") {
            Text(groupName ?: "не выбрана", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onChangeGroup) { Text("Сменить группу") }
        }

        Section("Оформление") {
            ThemeOption("Как в системе", ThemeChoice.SYSTEM, theme, onTheme)
            ThemeOption("Тёмная", ThemeChoice.DARK, theme, onTheme)
            ThemeOption("Светлая", ThemeChoice.LIGHT, theme, onTheme)
            Text(
                "Тёмная сделана чисто чёрной: на экранах OLED такие точки просто " +
                    "выключены, виджет не светится и не тратит заряд.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Обновление") {
            Text(
                "Приложение спрашивает сервер раз в час, а также когда вы его " +
                    "открываете, добавляете виджет или нажимаете «Обновить». " +
                    "Сам сервер перечитывает таблицу колледжа каждые 20 минут и " +
                    "отдельно за восемь минут до каждой пары — расписание правят " +
                    "и перед самым звонком.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRefresh) { Text("Обновить сейчас") }
        }

        Section("Какие данные уходят") {
            Text(
                "Приложение отправляет на сервер ровно одно: название выбранной " +
                    "вами группы — иначе непонятно, чьё расписание присылать. " +
                    "Оно уходит в адресе запроса, например /v1/schedule/isp-924-1.",
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Не отправляется и не собирается ничего другого: ни имя, ни " +
                    "телефон, ни список установленных приложений, ни местоположение, " +
                    "ни какие-либо опознаватели устройства. Учётной записи в " +
                    "приложении нет вовсе. Аналитики и рекламы нет.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Как у любого обращения в интернете, сервер видит сетевой адрес, " +
                    "с которого пришёл запрос. Журнал обращений на нём ведётся без " +
                    "этих адресов, и запросы ни с чем не связываются.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Расписание хранится в памяти телефона, чтобы виджет работал без " +
                    "сети. Всё стирается вместе с приложением.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("О приложении") {
            Text("Версия ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            Text("Сервер: ${BuildConfig.BASE_URL}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Расписание берётся из общей таблицы колледжа. Если в ней ошибка, " +
                    "она будет и здесь.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
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
            .selectable(selected = value == current, onClick = { onPick(value) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == current, onClick = { onPick(value) })
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
