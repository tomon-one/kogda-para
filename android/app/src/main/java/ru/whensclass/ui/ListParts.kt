package ru.whensclass.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Подходит ли имя под запрос поиска. Без регистра, «ё» как «е», без пробелов,
 * дефисов и косых: дефис на русской клавиатуре спрятан, и «исп 924» или
 * «исп924» давали «Ничего не нашлось» при «ИСП-924/1» в списке, а
 * «Чернышева» не находила «Чернышёву» (третий аудит, М33 прогона 2).
 */
internal fun matchesQuery(name: String, query: String): Boolean {
    val wanted = searchKey(query)
    return wanted.isEmpty() || searchKey(name).contains(wanted)
}

private fun searchKey(text: String): String =
    text.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

/**
 * Список не загрузился — со ссылкой, куда написать, и сведениями для отчёта.
 * Отсюда до настроек не дойти: ни группу, ни себя ещё не выбрали, а
 * спотыкается здесь как раз тот, кто поставил приложение впервые. У
 * преподавателя адреса не было вовсе (третий аудит, М76 прогона 2).
 */
@Composable
internal fun LoadFailed(what: String, loadDiagnostics: (suspend () -> String)?, onRetry: (() -> Unit)? = null) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "$what не загрузился. Проверьте интернет или напишите",
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
        // Связь вернулась — повторить здесь же, а не перезапуском (В3
        // прогона 2).
        onRetry?.let { ActionButton(label = "Повторить", onClick = it) }
        loadDiagnostics?.let { ReportLink(it) }
    }
}
