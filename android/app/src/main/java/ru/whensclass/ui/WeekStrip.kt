package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

// Полоска дней недели над списком.

/** Поле над полоской дней. */
private val STRIP_TOP = 6.dp

private val WEEK_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Полоска дней недели (week.js сайта): неделя дня, который наверху списка, он же
 * выделен, точка — сегодня. Выделение — одна плашка, она переезжает от дня к дню.
 */
@Composable
internal fun WeekStrip(
    modifier: Modifier = Modifier,
    /** «Стекло»: размывать список под полоской. */
    glass: GlassSource? = null,
    shownDay: () -> LocalDate,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    /** Палец повёл полоску вверх-вниз — листать список под ней. */
    onDrag: (Float) -> Unit,
) {
    val shown = shownDay()
    val monday = shown.minusDays((shown.dayOfWeek.value - 1).toLong())
    val index = shown.dayOfWeek.value - 1
    // Плашка едет только в отрисовке: при листании полоска не пересчитывается на
    // каждом кадре, а цвет подписи меняется сразу — под плашкой это не видно.
    val pill = remember { androidx.compose.animation.core.Animatable(index.toFloat()) }
    LaunchedEffect(index) {
        pill.animateTo(
            index.toFloat(),
            androidx.compose.animation.core.tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        )
    }
    val pillColor = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.isDark
    // У «Стекла» полоска висит над списком: размыто только под ней самой,
    // вокруг список виден как есть — без отдельной полосы во всю ширину.
    Box(modifier = modifier.fillMaxWidth().holdTouches().padding(start = 12.dp, end = 12.dp, top = STRIP_TOP, bottom = 8.dp)) {
    if (glass != null) {
        Box(
            Modifier
                .matchParentSize()
                // Само стекло рисует список под полоской (GlassSource); здесь — только где оно.
                .onGloballyPositioned { glass.window = it.boundsInRoot() },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .then(if (glass != null) Modifier.border(1.dp, if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(14.dp)) else Modifier)
            .padding(4.dp)
            // Одно касание на всю полоску вместо семи кнопок: при листании
            // полоска перестраивается дешевле. Чтецу экрана — каждый день отдельно (ниже).
            .draggable(
                rememberDraggableState { onDrag(it) },
                androidx.compose.foundation.gestures.Orientation.Vertical,
            )
            .pointerInput(monday) {
                detectTapGestures { offset ->
                    val i = (offset.x / (size.width / 7f)).toInt().coerceIn(0, 6)
                    onPick(monday.plusDays(i.toLong()))
                }
            }
            .drawBehind {
                val cell = size.width / 7
                drawRoundRect(
                    pillColor,
                    topLeft = androidx.compose.ui.geometry.Offset(cell * pill.value, 0f),
                    size = androidx.compose.ui.geometry.Size(cell, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                )
            },
    ) {
        for (i in 0 until 7) {
            val date = monday.plusDays(i.toLong())
            val on = i == index
            val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "${WEEK_NAMES[i]}, ${date.dayOfMonth}"
                        onClick(label = "Перейти к дню") { onPick(date); true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 4.dp, bottom = 7.dp),
                ) {
                    Text(
                        WEEK_NAMES[i],
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) fg else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                    )
                }
                if (date == today) {
                    // Точка — сегодня.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 2.dp)
                            .size(4.dp)
                            .background(if (on) fg else MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
    }
}
