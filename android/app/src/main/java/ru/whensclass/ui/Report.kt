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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/**
 * Как приложение рассказывает о себе, когда что-то сломалось. Нужно и в «О
 * приложении», и на обоих экранах выбора, где человек застревает до настроек.
 * Строка нарочно неприметная: нужна раз в жизни.
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
 * Отчёт об ошибке готовым текстом ([collectDiagnostics]): то, чего не видно
 * на скриншоте, — сборка и что она в последний раз получила от сервера.
 */
@Composable
private fun ReportDialog(load: suspend () -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    // Сведения нужны, когда что-то не так: сбор не должен ронять приложение.
    LaunchedEffect(Unit) {
        report = runCatching { load() }
            // Не имя класса: в release-сборке R8 его переименует. Текст ошибки
            // переживает обфускацию.
            .getOrElse { "Собрать не вышло: " + (it.message ?: "без объяснения") }
    }
    val text = report

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сведения для отчёта") },
        text = {
            // На маленьком экране с заголовком и кнопками список упирается в край.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val link = MaterialTheme.colorScheme.primary
                Text(
                    // Что уходит — видно ниже целиком, без обещаний «личного нет»:
                    // в отчёте модель телефона, пояс и у преподавателя — ФИО.
                    // Имя автора — ссылкой: отсюда как раз и идут писать.
                    buildAnnotatedString {
                        append("Пришлите это автору в Telegram: ")
                        withLink(
                            LinkAnnotation.Url(
                                "https://t.me/toomonn",
                                TextLinkStyles(SpanStyle(color = link, fontWeight = FontWeight.SemiBold)),
                            ),
                        ) { append("@toomonn") }
                        append(" — вместе с жалобой. Всё, что уйдёт, — ниже: посмотрите перед отправкой.")
                    },
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
                        // Ровными столбцами: видно, что это выписка, и её надо
                        // переслать целиком.
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
