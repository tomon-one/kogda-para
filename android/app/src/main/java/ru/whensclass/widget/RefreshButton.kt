package ru.whensclass.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * Кнопка «обновить» — одна на все виджеты.
 *
 * Была значком в конце строки со временем проверки. Значок в тексте нельзя
 * поставить по высоте: он живёт на нижней строке шапки и выглядит уехавшим
 * вниз, а нажимать надо ровно в буквы. Здесь это отдельный ребёнок строки
 * шапки, поэтому она сама ставит его по центру рядом со стрелками.
 *
 * Крутиться значок не должен: анимация в виджете идёт через перерисовку всего
 * дерева, и вращение стоит дороже, чем сам запрос. О ходе дела говорит цвет и
 * надпись рядом.
 */
@Composable
fun RefreshButton(colors: Palette, busy: Boolean, done: Boolean, failed: Boolean) {
    Text(
        "⟳",
        maxLines = 1,
        style = TextStyle(
            fontSize = 14.sp,
            color = when {
                failed -> colors.error
                busy || done -> colors.accent
                else -> colors.text
            },
        ),
        // Порядок как у стрелок: подложка со скруглением, потом нажатие, потом
        // поля. Поля внутри нажимаемой области — иначе палец ловит только знак.
        modifier = GlanceModifier
            .background(colors.button)
            .cornerRadius(8.dp)
            .clickable(actionRunCallback<RefreshAction>())
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}
