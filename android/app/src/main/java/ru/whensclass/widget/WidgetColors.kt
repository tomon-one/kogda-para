package ru.whensclass.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider

/** Какую тему показывать. Хранится в настройках приложения. */
enum class ThemeChoice {
    SYSTEM, DARK, LIGHT;

    companion object {
        fun from(value: String?): ThemeChoice = when (value) {
            "dark" -> DARK
            "light" -> LIGHT
            else -> SYSTEM
        }

        fun toStored(choice: ThemeChoice): String = when (choice) {
            DARK -> "dark"
            LIGHT -> "light"
            SYSTEM -> "system"
        }
    }
}

/**
 * Палитра виджета.
 *
 * Цвета заданы явно, а не взяты из системной темы: оболочки на телефонах
 * слишком по-разному понимают «динамические» цвета, а виджет должен выглядеть
 * одинаково у всех одногруппников.
 */
data class Palette(
    val background: ColorProvider,
    val surface: ColorProvider,
    val nowSurface: ColorProvider,
    val text: ColorProvider,
    val textDim: ColorProvider,
    val accent: ColorProvider,
    val error: ColorProvider,
    val button: ColorProvider,
    val buttonDisabled: ColorProvider,
)

object WidgetColors {

    /** Тёмная — чистый чёрный: на OLED такие пиксели просто выключены. */
    val dark = Palette(
        background = ColorProvider(Color(0xFF000000)),
        surface = ColorProvider(Color(0xFF121212)),
        nowSurface = ColorProvider(Color(0xFF10312A)),
        text = ColorProvider(Color(0xFFF2F2F2)),
        textDim = ColorProvider(Color(0xFF9AA0A6)),
        accent = ColorProvider(Color(0xFF5FD3A8)),
        error = ColorProvider(Color(0xFFFF7A7A)),
        button = ColorProvider(Color(0xFF1E1E1E)),
        buttonDisabled = ColorProvider(Color(0xFF141414)),
    )

    /** Светлая — мягкий белый, чтобы не слепить на светлых обоях. */
    val light = Palette(
        background = ColorProvider(Color(0xFFFBFBFB)),
        surface = ColorProvider(Color(0xFFF0F1F3)),
        nowSurface = ColorProvider(Color(0xFFD3F0E4)),
        text = ColorProvider(Color(0xFF14181C)),
        textDim = ColorProvider(Color(0xFF5C6672)),
        accent = ColorProvider(Color(0xFF10795C)),
        error = ColorProvider(Color(0xFFB3261E)),
        button = ColorProvider(Color(0xFFE3E5E8)),
        buttonDisabled = ColorProvider(Color(0xFFEDEEF0)),
    )

    /** Выбранная тема, а при выборе «как в системе» — системная. */
    fun resolve(context: Context, choice: ThemeChoice): Palette = when (choice) {
        ThemeChoice.DARK -> dark
        ThemeChoice.LIGHT -> light
        ThemeChoice.SYSTEM -> if (isSystemDark(context)) dark else light
    }

    private fun isSystemDark(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }
}
