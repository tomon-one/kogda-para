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
 * Красный с белым — цвета колледжа. Цвета заданы явно, а не взяты из системной
 * темы: оболочки на телефонах слишком по-разному понимают «динамические»
 * цвета, а виджет должен выглядеть одинаково у всех одногруппников.
 */
data class Palette(
    val background: ColorProvider,
    val surface: ColorProvider,
    val nowSurface: ColorProvider,
    val text: ColorProvider,
    val textDim: ColorProvider,
    val accent: ColorProvider,
    /** Логотип: на светлом — фирменный красный, на чёрном — белый. */
    val logo: ColorProvider,
    val error: ColorProvider,
    val button: ColorProvider,
    val buttonDisabled: ColorProvider,
)

object WidgetColors {

    /** Фирменный красный колледжа. */
    val BRAND = Color(0xFFE4353A)
    private val BRAND_DEEP = Color(0xFFB3151B)

    /** Тёмная — чистый чёрный: на OLED такие пиксели просто выключены. */
    val dark = Palette(
        background = ColorProvider(Color(0xFF000000)),
        surface = ColorProvider(Color(0xFF141414)),
        // Идущая сейчас пара — приглушённый красный, чтобы бросалась в глаза,
        // но не выжигала экран.
        nowSurface = ColorProvider(Color(0xFF3A1113)),
        text = ColorProvider(Color(0xFFF5F5F5)),
        textDim = ColorProvider(Color(0xFF9AA0A6)),
        accent = ColorProvider(Color(0xFFFF6B70)),
        // Красный логотип на чёрном сливается с фоном: на тёмной теме он белый,
        // как вторая половина цветов колледжа.
        logo = ColorProvider(Color(0xFFF5F5F5)),
        error = ColorProvider(BRAND),
        button = ColorProvider(Color(0xFF1E1E1E)),
        buttonDisabled = ColorProvider(Color(0xFF141414)),
    )

    /** Светлая — белая с красным, как печатное расписание колледжа. */
    val light = Palette(
        background = ColorProvider(Color(0xFFFFFFFF)),
        surface = ColorProvider(Color(0xFFF4F5F7)),
        nowSurface = ColorProvider(Color(0xFFFFE1E2)),
        text = ColorProvider(Color(0xFF16181B)),
        textDim = ColorProvider(Color(0xFF5C6672)),
        accent = ColorProvider(BRAND_DEEP),
        logo = ColorProvider(BRAND),
        error = ColorProvider(BRAND_DEEP),
        button = ColorProvider(Color(0xFFEDEEF0)),
        buttonDisabled = ColorProvider(Color(0xFFF6F7F8)),
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
