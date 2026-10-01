package ru.whensclass.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Красный колледжа — из его же логотипа. */
private val BRAND = Color(0xFFD60403)
private val BRAND_LIGHT = Color(0xFFFF6B70)

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
)
