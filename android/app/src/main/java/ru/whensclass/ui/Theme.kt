package ru.whensclass.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Красный колледжа — из его же логотипа. */
private val BRAND = Color(0xFFD60403)
private val BRAND_LIGHT = Color(0xFFFF6B70)

/** Зелёный «Открыть» у онлайн-пары: в схеме Material своего зелёного нет. */
internal val ColorScheme.online: Color
    get() = if (background.luminance() < 0.5f) Color(0xFF4CC38A) else Color(0xFF0A7A3E)

/** Плитка пары внутри карточки дня (--tile сайта). */
internal val ColorScheme.tile: Color
    get() = if (background.luminance() < 0.5f) Color(0xFF1C1C1C) else Color(0xFFF4F5F7)

/** Тёмная схема в тон виджету: чистый чёрный не светится на OLED. */
internal val DarkScheme = darkColorScheme(
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF9AA0A6),
    primary = BRAND_LIGHT,
    onPrimary = Color(0xFF000000),
    error = BRAND_LIGHT,
    // Остальное задано явно: по умолчанию у Material сиреневые выбранные кнопки,
    // окна и дорожки выключателей — чужой цвет в красно-серой теме.
    secondaryContainer = Color(0xFF3D2224),
    onSecondaryContainer = Color(0xFFF5F5F5),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0E0E0E),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF5F6368),
    outlineVariant = Color(0xFF3A3A3A),
    // Плашка-сообщение: светлее карточек, иначе читается строкой карточки под ней.
    inverseSurface = Color(0xFF3A3A3A),
    inverseOnSurface = Color(0xFFF5F5F5),
)

/**
 * Светлая схема: серый лист, белые карточки — так карточка отделена от фона.
 * Серые нейтральные, без синевы: холодный серый читается как грязь.
 */
internal val LightScheme = lightColorScheme(
    background = Color(0xFFF1F2F4),
    onBackground = Color(0xFF15171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15171A),
    surfaceVariant = Color(0xFFE7E8EA),
    onSurfaceVariant = Color(0xFF5E6266),
    primary = BRAND,
    onPrimary = Color(0xFFFFFFFF),
    error = BRAND,
    secondaryContainer = Color(0xFFFADADB),
    onSecondaryContainer = Color(0xFF15171A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8F9),
    surfaceContainer = Color(0xFFF1F2F4),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE7E8EA),
    outline = Color(0xFF8A8E93),
    outlineVariant = Color(0xFFC9CBCF),
    inverseSurface = Color(0xFF2B2D30),
    inverseOnSurface = Color(0xFFF5F5F5),
)
