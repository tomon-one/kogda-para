package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Значки групп под временем пары — только тех, у кого эта пара есть: своя —
 * закрашенным, другие — бледным. Так видно совмещённые (Tomon 28.09): пустые
 * рамки для групп без пары только путали.
 *
 * Подпись — название ([shortLabels]) или номер: место группы в настройках,
 * своя — 1. Что именно — выбирается в настройках. Номера — по три в ряд,
 * названия — сколько влезет в колонку времени.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupMarks(slots: List<Int>, names: List<String>, byName: Boolean, modifier: Modifier = Modifier) {
    val whose = slots.sorted().mapNotNull { names.getOrNull(it) }
    val labels = if (byName) shortLabels(names) else names.indices.map { (it + 1).toString() }
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .then(if (byName) Modifier else Modifier.widthIn(max = (MARK + 4.dp) * MARKS_IN_ROW * scale))
            .clearAndSetSemantics { contentDescription = "Пара у групп: " + whose.joinToString(", ") },
    ) {
        labels.forEachIndexed { slot, label -> if (slot in slots) GroupMark(label, own = slot == 0) }
    }
}

/** Один значок: своя группа — закрашенный красный, другая — бледно-красный. */
@Composable
fun GroupMark(label: String, own: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    // Растёт со шрифтом, как колонка времени: подпись в sp, значок в dp.
    val side = MARK * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(side)
            .defaultMinSize(minWidth = side)
            .background(if (own) colors.primary else colors.primary.copy(alpha = 0.12f), shape)
            .padding(horizontal = 4.dp),
    ) {
        Text(
            label,
            // Высота строки — своя, по размеру цифр: без неё бралась строка
            // основного текста, втрое выше значка, и подпись съезжала вниз
            // (Tomon 28.09).
            style = TextStyle(
                fontSize = 11.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            color = if (own) colors.onPrimary else colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Подписи групп для значков: подгруппы той же группы, что своя, — коротко,
 * «/1», «/2»; остальные — полным названием. Иначе «ИСП-924/1 ИСП-924/2
 * ИСП-924/3» не влезали в колонку времени.
 */
fun shortLabels(names: List<String>): List<String> {
    val base = names.firstOrNull()?.let { SUBGROUP.matchEntire(it.trim())?.groupValues?.get(1) }
    return names.map { name ->
        val match = SUBGROUP.matchEntire(name.trim())
        if (base != null && match != null && match.groupValues[1] == base) "/" + match.groupValues[2] else name
    }
}

/** «Вторая группа», «Третья группа» — заголовок выбора следующей. */
fun ordinalGroup(number: Int): String =
    (ORDINALS.getOrNull(number - 1) ?: "$number-я") + " группа"

private val SUBGROUP = Regex("""(.+)/(\d+)""")

private val ORDINALS = listOf("Первая", "Вторая", "Третья", "Четвёртая", "Пятая", "Шестая")

private const val MARKS_IN_ROW = 3
private val MARK = 18.dp
