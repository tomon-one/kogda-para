package ru.whensclass.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ru.whensclass.R

private const val SITE = "https://kogda-para-nsk.ru"
private const val REPO = "https://github.com/tomon-one/kogda-para"

/**
 * «Поделиться» (Tomon 29.09): код для камеры, ссылки на сайт и на исходный
 * код с копированием и системное «Отправить». Приложение раздаётся файлом и
 * пересылкой — иначе как от человека к человеку о нём не узнать. Код ведёт в
 * репозиторий: оттуда и сайт, и файл, и как поставить.
 */
@Composable
fun ShareLink(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    if (open) ShareDialog { open = false }
    Text(
        "Поделиться",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clickable { open = true }
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun ShareDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поделиться") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Image(
                    painter = painterResource(R.drawable.share_qr),
                    contentDescription = "Код со ссылкой на исходный код и файл приложения",
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Text(
                    "Наведите камеру — откроется страница приложения: оттуда и файл, и сайт.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                CopyRow("Сайт", SITE.removePrefix("https://")) {
                    copyToClipboard(context, "Сайт «Когда пара?»", SITE)
                }
                CopyRow("Исходный код", REPO.removePrefix("https://")) {
                    copyToClipboard(context, "Исходный код «Когда пара?»", REPO)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val text = "«Когда пара?» — расписание НГОК на экране телефона: $SITE\n" +
                    "Приложение для Android и исходный код: $REPO"
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            "Поделиться",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                onDismiss()
            }) { Text("Отправить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun CopyRow(label: String, shown: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(shown, style = MaterialTheme.typography.bodyMedium)
        }
        ActionButton(label = "Скопировать", onClick = onCopy, top = 0.dp, spoken = "Скопировать ссылку: $label")
    }
}
