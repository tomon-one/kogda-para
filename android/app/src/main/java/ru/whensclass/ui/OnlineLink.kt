package ru.whensclass.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Ссылка на онлайн-занятие: хост с хвостом адреса и две кнопки. Полный адрес
 * не показывается — он не влезает и раздувает строку пары. «Открыть» первой:
 * нужна чаще; «Копировать» — чтобы переслать.
 */
@Composable
internal fun OnlineLink(url: String) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        // Куда ведёт кнопка: ссылку вписывает руками любой, кто правит таблицу.
        // Хост не с площадки вебинаров колледжа — предупреждаем прямо.
        val known = remember(url) { ru.whensclass.data.isKnownWebinar(url) }
        val color = if (known) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
        // Хост ужимается, хвост — нет: ради последних цифр хвост и показан.
        Row {
            Text(
                if (known) host(url) else "чужой адрес: " + host(url),
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            linkEnd(url)?.let {
                Text(
                    " · …/$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        // Волосок между кнопками: вплотную рамки сливаются, а широкий зазор
        // разносит пару кнопок.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LinkButton("Открыть") { openLink(context, url) }
            LinkButton("Копировать") { copyLink(context, url) }
        }
    }
}

/**
 * «https://my.mts-link.ru/j/100000004/20000000626» -> «20000000626»: хвост
 * к хосту, строкой «my.mts-link.ru · …/20000000626». По хвосту отличают три
 * пары подряд в одной комнате от трёх разных, по хосту — фишинговую ссылку
 * от настоящей.
 */
private fun linkEnd(url: String): String? =
    runCatching { Uri.parse(url).pathSegments }
        .getOrNull()
        ?.lastOrNull { it.isNotBlank() }
        ?.takeLast(16)

/** «https://my.mts-link.ru/j/144...» -> «my.mts-link.ru»: запасной вид без пути. */
private fun host(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        // Поля и высота меньше обычных, чтобы пара с вебинаром не распухала;
        // область нажатия Compose сам держит не меньше 48 dp.
        modifier = Modifier.height(28.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        // Тонкая рамка: без неё текст кнопки неотличим от подписи рядом.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        // В одну строку с многоточием: кнопка остаётся своей высоты.
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

internal fun openLink(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "Нечем открыть ссылку", Toast.LENGTH_SHORT).show()
    }
}

private fun copyLink(context: android.content.Context, url: String) =
    copyToClipboard(context, "Ссылка на занятие", url, "Ссылка скопирована")
