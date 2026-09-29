package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.whensclass.widget.formatDurationLong
import ru.whensclass.widget.formatDurationShort

/** Быстрый выбор напоминания — две строки по пять. */
internal val REMIND_QUICK = listOf(10, 15, 20, 30, 45, 60, 90, 120, 180, 240)

/**
 * Границы своего времени: меньше десяти минут предупреждать поздно, дольше
 * четырёх часов — уже не про эту пару. Те же, что у службы для сайта.
 */
internal const val REMIND_MIN = 10
internal const val REMIND_MAX = 240

/** Подпись плитки: число крупно, единица мелко — «1,5» и «часа». */
internal fun tileLabel(minutes: Int): Pair<String, String> = when {
    minutes < 60 -> "$minutes" to "мин"
    minutes == 60 -> "1" to "час"
    minutes % 60 == 0 -> "${minutes / 60}" to "часа"
    minutes % 30 == 0 -> "${minutes / 60},5" to "часа"
    else -> formatDurationShort(minutes) to ""
}

/**
 * Строка «За сколько предупредить» с кнопкой, где написано выбранное. Кнопка
 * той же ширины, что «Сменить» и «Убрать»: «1 ч 30 мин» и «20 мин» её не
 * двигают.
 */
@Composable
internal fun MinutesRow(current: Int, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "За сколько предупредить",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        ActionButton(
            label = formatDurationShort(current),
            onClick = onOpen,
            modifier = Modifier.width(ROW_BUTTON),
            top = 0.dp,
            side = 8.dp,
            spoken = "За сколько предупредить: ${formatDurationLong(current)}. Изменить",
        )
    }
}

/**
 * Окно выбора (Tomon 29.09): сверху крестик, ниже быстрый выбор плитками —
 * нажатие сразу сохраняет и закрывает, — внизу своё время. Выбранная плитка
 * залита красным; своё время — рамкой вокруг поля.
 */
@Composable
internal fun ReminderDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val quick = current in REMIND_QUICK
    var own by rememberSaveable { mutableStateOf(if (quick) "" else current.toString()) }
    val minutes = own.toIntOrNull()
    val valid = minutes != null && minutes in REMIND_MIN..REMIND_MAX
    val submit = { if (valid) onPick(minutes!!) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "За сколько предупредить",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    CloseButton(onDismiss)
                }
                Column(modifier = Modifier.padding(end = 10.dp)) {
                    Caption("Быстрый выбор", top = 8.dp)
                    REMIND_QUICK.chunked(5).forEachIndexed { index, row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (index == 0) 0.dp else 8.dp)
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { m ->
                                QuickTile(m, selected = m == current, onClick = { onPick(m) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    Caption("Своё время", top = 20.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MinutesField(
                            value = own,
                            onValue = { own = it.filter(Char::isDigit).take(3) },
                            chosen = !quick && own == current.toString(),
                            onDone = submit,
                            modifier = Modifier.weight(1f),
                        )
                        ActionButton(
                            label = "Готово",
                            onClick = submit,
                            enabled = valid,
                            modifier = Modifier.width(ROW_BUTTON),
                            top = 0.dp,
                            side = 8.dp,
                        )
                    }
                    Text(
                        "От $REMIND_MIN до $REMIND_MAX минут (${REMIND_MAX / 60} часа)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (own.isNotEmpty() && !valid) {
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
}

@Composable
private fun Caption(text: String, top: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = top, bottom = 8.dp),
    )
}

/** Крестик в круге 40 dp — область нажатия больше самого значка. */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .semantics { contentDescription = "Закрыть" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Плитка быстрого выбора: одной высоты и ширины с соседями, что бы на ней ни было. */
@Composable
private fun QuickTile(minutes: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val (number, unit) = tileLabel(minutes)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = formatDurationLong(minutes) }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            number,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 22.sp, stepSize = 1.sp),
            modifier = Modifier.padding(horizontal = 4.dp).clearAndSetSemantics {},
        )
        Text(
            unit,
            style = MaterialTheme.typography.labelSmall,
            color = fg.copy(alpha = 0.75f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Поле своего времени — той же высоты, что кнопки; «мин» стоит в нём справа. */
@Composable
private fun MinutesField(
    value: String,
    onValue: (String) -> Unit,
    chosen: Boolean,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .height(BUTTON_HEIGHT)
            .semantics { contentDescription = "Своё время, минут" },
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.5.dp, if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            "Например, 47",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    field()
                }
                Text(
                    "мин",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        },
    )
}
