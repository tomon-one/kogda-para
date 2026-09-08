package ru.whensclass.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Свои переключатели и кнопки.
 *
 * Материаловские выглядят как чужие: круглый тумблер-таблетка и кнопка без
 * границ, которая «летает» по карточке, не показывая, где по ней нажимать.
 * Здесь всё прямоугольное со скруглением — в тон карточкам настроек и
 * виджетам, где углы такие же.
 *
 * Анимации короткие (140 мс) и только по цвету и сдвигу: тумблер в списке
 * настроек не должен заставлять себя ждать.
 */
private const val SHIFT_MS = 140

/** Тумблер: прямоугольный трек, квадратный бегунок. */
@Composable
fun MinimalSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shift by animateDpAsState(
        targetValue = if (checked) 18.dp else 0.dp,
        animationSpec = tween(SHIFT_MS),
        label = "thumb",
    )
    val track by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(SHIFT_MS),
        label = "track",
    )

    Box(
        modifier = modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(track)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = shift)
                .size(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (checked) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
        )
    }
}

/** Выбор одного из нескольких — вместо круглой точки Material. */
@Composable
fun MinimalCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(SHIFT_MS),
        label = "check",
    )

    Box(
        modifier = modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, color, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

/**
 * Кнопка действия.
 *
 * У неё есть своя площадка: без неё текст кнопки в карточке неотличим от
 * подписи рядом, и непонятно, куда именно нажимать.
 */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
