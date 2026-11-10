package ru.whensclass.ui

import androidx.compose.ui.semantics.selected
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
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
    // Неделя сменилась — плашка уезжает вместе с днями, а не пробегает всю полоску.
    var pillWeek by remember { mutableStateOf(monday) }
    LaunchedEffect(index, monday) {
        if (monday != pillWeek) {
            pillWeek = monday
            pill.snapTo(index.toFloat())
        } else {
            pill.animateTo(
                index.toFloat(),
                androidx.compose.animation.core.tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            )
        }
    }
    var sideways by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val weekSwipe = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
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
            // Вбок — соседняя неделя: палец влево — следующая. Ось выбирает первое
            // заметное движение, так что листание вверх-вниз с полоски не мешает.
            .draggable(
                rememberDraggableState { sideways += it },
                androidx.compose.foundation.gestures.Orientation.Horizontal,
                onDragStarted = { sideways = 0f },
                onDragStopped = { velocity ->
                    val step = when {
                        sideways < -weekSwipe || velocity < -WEEK_FLING -> 1L
                        sideways > weekSwipe || velocity > WEEK_FLING -> -1L
                        else -> 0L
                    }
                    if (step != 0L) onPick(shownDay().plusWeeks(step))
                },
            )
            .pointerInput(monday) {
                detectTapGestures { offset ->
                    val i = (offset.x / (size.width / 7f)).toInt().coerceIn(0, 6)
                    onPick(monday.plusDays(i.toLong()))
                }
            }
            .clipToBounds(),
    ) {
    // Новая неделя въезжает со стороны листания: вперёд — справа, назад — слева.
    AnimatedContent(
        targetState = monday,
        transitionSpec = {
            val forward = if (targetState > initialState) 1 else -1
            val move = androidx.compose.animation.core.tween<IntOffset>(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            (slideInHorizontally(move) { it * forward } togetherWith slideOutHorizontally(move) { -it * forward })
                .using(SizeTransform(clip = false))
        },
        label = "неделя",
        modifier = Modifier.weight(1f),
    ) { week ->
    // Плашка — у своей недели: у уезжающей её нет.
    val current = week == monday
    Row(
        modifier = Modifier.fillMaxWidth().drawBehind {
            if (!current) return@drawBehind
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
            val date = week.plusDays(i.toLong())
            val on = current && i == index
            val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        // Выбранный и сегодняшний — словами: цвет и точку чтец не видит.
                        contentDescription = "${WEEK_NAMES[i]}, ${date.dayOfMonth}" + if (date == today) ", сегодня" else ""
                        selected = on
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
    }
}

/** Скорость броска вбок (пикселей в секунду), с которой неделя сменится и без 48 dp пути. */
private const val WEEK_FLING = 1500f
