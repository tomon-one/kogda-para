package ru.whensclass.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider

/**
 * Палитра виджета.
 *
 * Тёмная и намеренно чёрная: на OLED-экране чистый чёрный не светится вовсе,
 * поэтому виджет не мозолит глаза на тёмных обоях и не тратит заряд. Цвета
 * заданы явно, а не взяты из системной темы, чтобы виджет выглядел одинаково
 * у всех — оболочки на телефонах слишком по-разному понимают «динамические»
 * цвета.
 */
object WidgetColors {
    /** Чистый чёрный: на OLED эти пиксели просто выключены. */
    val background = ColorProvider(Color(0xFF000000))

    /** Карточка пары чуть светлее фона, чтобы строки читались как отдельные. */
    val surface = ColorProvider(Color(0xFF121212))

    /** Пара, которая идёт прямо сейчас. */
    val nowSurface = ColorProvider(Color(0xFF10312A))

    val text = ColorProvider(Color(0xFFF2F2F2))
    val textDim = ColorProvider(Color(0xFF9AA0A6))
    val accent = ColorProvider(Color(0xFF5FD3A8))
    val error = ColorProvider(Color(0xFFFF7A7A))

    /** Кнопки переключения дня. */
    val button = ColorProvider(Color(0xFF1E1E1E))
    val buttonDisabled = ColorProvider(Color(0xFF141414))
}
