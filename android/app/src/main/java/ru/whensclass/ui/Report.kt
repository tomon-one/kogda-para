package ru.whensclass.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Как приложение рассказывает о себе, когда что-то сломалось.
 *
 * Живёт отдельно от настроек, потому что нужно в трёх местах: в «О приложении»
 * и на обоих экранах выбора — там человек застревает раньше, чем добирается до
 * настроек, и рассказать о себе иначе не может.
 *
 * Строка нарочно неприметная: она нужна раз в жизни и не должна спорить с тем,
 * ради чего экран открыт.
 */
@Composable
fun ReportLink(
    load: suspend () -> String,
    modifier: Modifier = Modifier,
    /** Среди ссылок «О приложении» — как они, а не неприметной строкой. */
    asLink: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    if (open) ReportDialog(load) { open = false }

    Text(
        "Сведения для отчёта",
        style = if (asLink) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
        color = if (asLink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (asLink) androidx.compose.ui.text.font.FontWeight.Medium else null,
        modifier = modifier
            .clickable { open = true }
            .then(if (asLink) Modifier.padding(top = 10.dp, bottom = 2.dp) else Modifier.padding(vertical = 8.dp)),
    )
}

/**
 * Отчёт об ошибке готовым текстом.
 *
 * Скриншот показывает, что человек видит, но не показывает, какая у него
 * сборка и что она в последний раз получила от сервера. Отсюда это уезжает
 * одной кнопкой — и разговор начинается не с десяти вопросов.
 */
@Composable
private fun ReportDialog(load: suspend () -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    // Сведения нужны ровно тогда, когда что-то не так. Уронить приложение на
    // сборе сведений о поломке было бы издевательством.
    LaunchedEffect(Unit) {
        report = runCatching { load() }
            // Не имя класса: в release-сборке R8 его переименует, и человек
            // пришлёт мне «Собрать не вышло: a». Текст ошибки переживает
            // обфускацию, а когда его нет — честнее сказать, что его нет.
            .getOrElse { "Собрать не вышло: " + (it.message ?: "без объяснения") }
    }
    val text = report

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сведения для отчёта") },
        text = {
            // Список короткий, но на маленьком экране вместе с заголовком и
            // кнопками он всё же упирается в край.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    // Раньше обещало «личного нет», а в отчёте модель телефона,
                    // версия Android, пояс и у преподавателя — ФИО.
                    // Перечень — весь: в отчёте ещё пояс, другие группы, виджеты и
                    // настройки.
                    // Что уходит — видно ниже целиком, перечень его только
                    // повторял; куда слать — не было сказано вовсе.
                    "Пришлите это автору в Telegram: @toomonn — вместе с жалобой. Всё, что уйдёт, — " +
                        "ниже: посмотрите перед отправкой.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(
                        text ?: "Сбор сведений…",
                        style = MaterialTheme.typography.bodySmall,
                        // Ровными столбцами: так видно, что это выписка, а не
                        // рассказ, и её надо переслать целиком.
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    text?.let { copyToClipboard(context, "Отчёт «Когда пара?»", it) }
                    onDismiss()
                },
                enabled = text != null,
            ) { Text("Скопировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
