package ru.whensclass.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Подходит ли имя под запрос поиска. Без регистра, «ё» как «е», без пробелов,
 * дефисов и косых: дефис на русской клавиатуре спрятан, и «исп 924» или
 * «исп924» давали «Ничего не нашлось» при «ИСП-924/1» в списке, а
 * «Чернышева» не находила «Чернышёву».
 */
internal fun matchesQuery(name: String, query: String): Boolean {
    val wanted = searchKey(query)
    return wanted.isEmpty() || searchKey(name).contains(wanted)
}

private fun searchKey(text: String): String =
    text.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

/**
 * Раздел длинного списка — по первой букве: «Б», «Г», «И»; имена с цифры —
 * в «0–9». Двести карточек подряд листались без опоры, глазу не за что было
 * зацепиться.
 */
internal fun letterOf(name: String): String {
    val first = name.trimStart().firstOrNull() ?: return "#"
    return when {
        first.isDigit() -> "0–9"
        first.isLetter() -> first.uppercaseChar().toString().replace('Ё', 'Е')
        else -> "#"
    }
}

/**
 * Список с разделами по букве: у каждого раздела заголовок и одна карточка,
 * строки в ней — через тонкую черту. Порядок — как в списке с сервера.
 */
internal fun <T> LazyListScope.lettered(
    list: List<T>,
    name: (T) -> String,
    key: (T) -> Any,
    onPick: (T) -> Unit,
) {
    list.groupBy { letterOf(name(it)) }.forEach { (letter, rows) ->
        item(key = "буква $letter") {
            Text(
                letter,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
            )
        }
        itemsIndexed(rows, key = { _, row -> key(row) }) { index, row ->
            val top = if (index == 0) 12.dp else 0.dp
            val bottom = if (index == rows.lastIndex) 12.dp else 0.dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(top, top, bottom, bottom))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    name(row),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onPick(row) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.background,
                    )
                }
            }
        }
    }
}

/**
 * Смена роли — первой строкой списка, такой же заметной, как остальные:
 * мелкую красную надпись над поиском легко пропустить.
 */
@Composable
internal fun RoleSwitchRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Список не загрузился — со ссылкой, куда написать, и сведениями для отчёта.
 * Отсюда до настроек не дойти: ни группу, ни себя ещё не выбрали, а
 * спотыкается здесь как раз тот, кто поставил приложение впервые. У
 * преподавателя адреса не было вовсе.
 */
@Composable
internal fun LoadFailed(what: String, loadDiagnostics: (suspend () -> String)?, onRetry: (() -> Unit)? = null) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "$what не загрузился. Проверьте интернет или напишите автору в Telegram:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "@toomonn",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/toomonn"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                .padding(vertical = 4.dp),
        )
        // Связь вернулась — повторить здесь же, а не перезапуском.
        onRetry?.let { ActionButton(label = "Повторить", onClick = it, modifier = Modifier.fillMaxWidth()) }
        loadDiagnostics?.let { ReportLink(it) }
    }
}
