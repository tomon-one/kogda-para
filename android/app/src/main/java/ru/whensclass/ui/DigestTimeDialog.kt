package ru.whensclass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.whensclass.notify.Digest

/** Быстрый выбор вечера: сводка о завтрашнем дне. */
private val DIGEST_QUICK = listOf(17, 18, 19, 20, 21, 22).map { it * 60 }

/** «20:00» из минуты дня. */
internal fun clockText(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)

/** Строка «Во сколько» — как «За сколько предупредить» у напоминаний. */
@Composable
internal fun DigestRow(current: Int, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Во сколько",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        ActionButton(
            label = clockText(current),
            onClick = onOpen,
            modifier = Modifier.width(ROW_BUTTON),
            top = 0.dp,
            side = 8.dp,
            spoken = "Вечерняя сводка в ${clockText(current)}. Изменить",
        )
    }
}

/**
 * Окно времени сводки: быстрые вечера и своё «ЧЧ:ММ». Границы те же, что у службы
 * (push/digest.py).
 */
@Composable
internal fun DigestTimeDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val quick = current in DIGEST_QUICK
    var hours by rememberSaveable { mutableStateOf(if (quick) "" else (current / 60).toString()) }
    var minutes by rememberSaveable { mutableStateOf(if (quick) "" else (current % 60).toString()) }
    val value = run {
        val h = hours.toIntOrNull()
        val m = minutes.toIntOrNull()
        val all = if (h != null && m != null) h * 60 + m else 0
        if (h != null && m != null && m in 0..59 && all in Digest.MIN..Digest.MAX) all else 0
    }
    val submit = { if (value > 0) onPick(value) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.let {
                if (it.alpha < 1f) MaterialTheme.colorScheme.surfaceContainer else it
            },
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Вечерняя сводка",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }
                Text(
                    "Быстрый выбор",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                DIGEST_QUICK.chunked(3).forEachIndexed { index, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (index == 0) 0.dp else 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { minute ->
                            OutlinedButton(
                                onClick = { onPick(minute) },
                                modifier = Modifier.weight(1f),
                            ) { Text(clockText(minute)) }
                        }
                    }
                }
                Text(
                    "Своё время",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                // Узко или крупный шрифт — «Готово» уходит строкой ниже: колонки полей шире, и
                // «Минуты» не переносится на вторую строку.
                val tight = LocalConfiguration.current.screenWidthDp /
                    LocalDensity.current.fontScale < 360f
                val gap = Arrangement.spacedBy(8.dp)
                // Подпись в одну строку: перенос «Минут|ы» читается как поломка поля.
                val hoursField: @Composable RowScope.() -> Unit = {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { hours = it.filter(Char::isDigit).take(2) },
                        label = { Text("Часы", maxLines = 1, softWrap = false) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number, imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                val minutesField: @Composable RowScope.() -> Unit = {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit).take(2) },
                        label = { Text("Минуты", maxLines = 1, softWrap = false) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (tight) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = gap,
                    ) {
                        hoursField()
                        minutesField()
                    }
                    ActionButton(
                        label = "Готово",
                        onClick = submit,
                        enabled = value > 0,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        top = 0.dp,
                        side = 8.dp,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = gap,
                    ) {
                        hoursField()
                        minutesField()
                        ActionButton(
                            label = "Готово",
                            onClick = submit,
                            enabled = value > 0,
                            modifier = Modifier.width(96.dp),
                            top = 0.dp,
                            side = 8.dp,
                        )
                    }
                }
                Text(
                    "С ${clockText(Digest.MIN)} до ${clockText(Digest.MAX)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if ((hours.isNotEmpty() || minutes.isNotEmpty()) && value == 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
