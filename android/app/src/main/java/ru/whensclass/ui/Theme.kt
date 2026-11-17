package ru.whensclass.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import java.time.LocalDate

/** Красный колледжа — из его же логотипа. */
private val BRAND = Color(0xFFD60403)
private val BRAND_LIGHT = Color(0xFFFF6B70)
private val BRAND_ON_LIGHT = Color(0xFFC20302)

/** Зелёный «Открыть» у онлайн-пары: в схеме Material своего зелёного нет. */
internal val ColorScheme.online: Color
    get() = if (isDark) Color(0xFF4CC38A) else Color(0xFF086B35)

/** Плитка пары внутри карточки дня (--tile сайта). У «Стекла» — полупрозрачная, как карточка. */
internal val ColorScheme.tile: Color
    get() = when {
        // У «Vice» плитка своего оттенка: на фиолетовом и розовом фоне прозрачная, как у «Стекла», не читалась бы.
        this === ViceDarkScheme -> Color(0xFF4E2189)
        this === ViceLightScheme -> Color(0xFFFBEDF3)
        background.alpha < 1f -> if (isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.55f)
        isDark -> Color(0xFF1C1C1C)
        else -> Color(0xFFF4F5F7)
    }

/**
 * Разделитель строк внутри карточки — цветом фона, будто щель. У «Стекла» фон прозрачный, и щель
 * пропала бы: там она тёмная.
 */
internal val ColorScheme.divider: Color
    get() = when {
        background.alpha == 1f -> background
        isDark -> Color.Black.copy(alpha = 0.55f)
        else -> Color.Black.copy(alpha = 0.06f)
    }

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
    // Остальное явно: по умолчанию у Material сиреневые выбранные кнопки, окна и дорожки
    // выключателей — чужой цвет в красно-серой теме.
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
 * Светлая схема: серый лист, белые карточки — карточка отделена от фона. Серые нейтральные, без
 * синевы: холодный серый читается как грязь.
 */
internal val LightScheme = lightColorScheme(
    background = Color(0xFFF1F2F4),
    onBackground = Color(0xFF15171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15171A),
    surfaceVariant = Color(0xFFE7E8EA),
    onSurfaceVariant = Color(0xFF5E6266),
    // Чуть темнее красного логотипа: на плашках с его оттенком текст держит 4,5:1 (как на сайте).
    primary = BRAND_ON_LIGHT,
    onPrimary = Color(0xFFFFFFFF),
    error = BRAND_ON_LIGHT,
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

/**
 * «Стекло» (beta): у схемы прозрачный фон — под ним красный свет ([glowBackdrop]), карточки
 * полупрозрачные. Окна (диалоги) остаются непрозрачными: у них свои surfaceContainer*.
 */
internal val GlassDarkScheme = DarkScheme.copy(
    background = Color.Transparent,
    surface = Color.White.copy(alpha = 0.07f),
    // Подложки сегментов и дорожки выключателя: сплошной тёмный на подсвеченной карточке выглядел дырой.
    surfaceVariant = Color.White.copy(alpha = 0.08f),
)

/** «Стекло» в светлой теме: молочные карточки на сером листе с розовым светом сверху. */
// Подписи и красное темнее обычной светлой: под светом сверху мелкий серый текст и «Пересдачи» на
// красном держат контраст 4,5:1.
internal val GlassLightScheme = LightScheme.copy(
    background = Color.Transparent,
    surface = Color.White.copy(alpha = 0.5f),
    surfaceVariant = Color.Black.copy(alpha = 0.05f),
    onSurfaceVariant = Color(0xFF4D5155),
    primary = Color(0xFFA00302),
)

/**
 * «Vice» — временная тема заката: фиолетово-персиковый. Фон и карточки непрозрачные, поэтому
 * подложка «Стекла» под ней не нужна.
 */
internal val ViceDarkScheme = DarkScheme.copy(
    background = Color(0xFF1B0A2E),
    onBackground = Color(0xFFF7EFF6),
    surface = Color(0xFF421A78),
    onSurface = Color(0xFFF7EFF6),
    surfaceVariant = Color(0xFF4E2189),
    onSurfaceVariant = Color(0xFFC9AEDC),
    primary = Color(0xFFF57CAB),
    onPrimary = Color(0xFF2A0C4A),
    error = Color(0xFFF57CAB),
    secondaryContainer = Color(0xFF5B2A9C),
    onSecondaryContainer = Color(0xFFF7EFF6),
    surfaceContainerLowest = Color(0xFF2C0F55),
    surfaceContainerLow = Color(0xFF3B1670),
    surfaceContainer = Color(0xFF421A78),
    surfaceContainerHigh = Color(0xFF4E2189),
    surfaceContainerHighest = Color(0xFF5B2A9C),
    outline = Color(0xFFA97BC0),
    outlineVariant = Color(0xFF6E3EA0),
    inverseSurface = Color(0xFFF7EFF6),
    inverseOnSurface = Color(0xFF361365),
)

/** «Vice» в светлой теме: розовый лист, белые карточки, пурпурный акцент. */
internal val ViceLightScheme = LightScheme.copy(
    background = Color(0xFFFDF4F8),
    onBackground = Color(0xFF2A0C2E),
    onSurface = Color(0xFF2A0C2E),
    surfaceVariant = Color(0xFFF3E3EC),
    onSurfaceVariant = Color(0xFF6B4A6E),
    primary = Color(0xFFB42EB4),
    error = Color(0xFFB42EB4),
    secondaryContainer = Color(0xFFF6DCF0),
    onSecondaryContainer = Color(0xFF2A0C2E),
    surfaceContainerLow = Color(0xFFFDF6F8),
    surfaceContainer = Color(0xFFFBF0F2),
    surfaceContainerHighest = Color(0xFFF3E3EC),
    outline = Color(0xFFA97BAE),
    outlineVariant = Color(0xFFE2CFE4),
    inverseSurface = Color(0xFF361365),
    inverseOnSurface = Color(0xFFF7EFF6),
)

/** Закат «Vice»: от фиолетового через пурпур и розовый к персиковому. Для плашки в настройках. */
internal val VICE_SUNSET = listOf(
    Color(0xFF361365),
    Color(0xFFB42EB4),
    Color(0xFFE15F97),
    Color(0xFFEE8B70),
)

/** Срок «Vice»: 31 декабря ещё действует, с 1 января — нет. Выбор в хранилище при этом не трогается. */
private val VICE_END: LocalDate = LocalDate.of(2027, 1, 1)

/**
 * Закат под карточками «Vice»: тёмно-фиолетовый по диагонали и тёплое пятно у нижнего правого угла —
 * как закат за домами. Пятно держится в углу: там только фон, а текст на нём терял бы контраст.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.viceBackdrop(dark: Boolean) {
    val corner = androidx.compose.ui.geometry.Offset(size.width * 1.05f, size.height * 1.1f)
    if (dark) {
        drawRect(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color(0xFF1B0A2E), Color(0xFF2E1048), Color(0xFF4C1668), Color(0xFF6B2275)),
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset(size.width, size.height),
            ),
        )
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color(0x8CEE8B70), Color(0x00EE8B70)),
                center = corner,
                radius = maxOf(size.width * 0.95f, 1f),
            ),
        )
    } else {
        drawRect(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color(0xFFFDF4F8), Color(0xFFFBE7EE)),
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset(size.width, size.height),
            ),
        )
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color(0x73FBCFC4), Color(0x00FBCFC4)),
                center = corner,
                radius = maxOf(size.width * 0.95f, 1f),
            ),
        )
    }
}

/** Показывать ли «Vice» в настройках. */
internal fun viceOffered(today: LocalDate): Boolean = today.isBefore(VICE_END)

/** Действует ли включённый «Vice» сейчас. */
internal fun viceActive(flag: Boolean, today: LocalDate): Boolean = flag && viceOffered(today)

/** Тёмная ли схема. По фону не понять: у «Стекла» он прозрачный. */
internal val ColorScheme.isDark: Boolean
    get() = onBackground.luminance() > 0.5f

/** Лист под светом «Стекла». */
internal fun glassBase(dark: Boolean): Color = if (dark) Color.Black else Color(0xFFF1F2F4)

/**
 * Верхний свет «Стекла» кистью в координатах элемента с левым верхним углом в [origin] экрана
 * размером [screen]: полоса дней рисует тот же свет, что корень, и не отделяется.
 */
internal fun glowTopBrush(
    dark: Boolean,
    screen: androidx.compose.ui.geometry.Size,
    origin: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
): androidx.compose.ui.graphics.Brush =
    androidx.compose.ui.graphics.Brush.radialGradient(
        if (dark) listOf(BRAND.copy(alpha = 0.42f), BRAND.copy(alpha = 0.12f), Color.Transparent)
        else listOf(BRAND.copy(alpha = 0.24f), BRAND.copy(alpha = 0.08f), Color.Transparent),
        center = androidx.compose.ui.geometry.Offset(screen.width * 0.55f - origin.x, -screen.height * 0.04f - origin.y),
        radius = maxOf(screen.width * 1.05f, 1f),
    )

/**
 * Свет за экраном «Стекла»: красное пятно сверху и слабое снизу слева. Рисуется один раз, без
 * анимации: при листании корень не перерисовывается.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.glowBackdrop(dark: Boolean) {
    drawRect(glassBase(dark))
    drawRect(glowTopBrush(dark, size))
    drawRect(
        androidx.compose.ui.graphics.Brush.radialGradient(
            // Слабый: сильнее — серые подписи на плитках внизу теряли контраст 4,5:1.
            listOf(BRAND.copy(alpha = if (dark) 0.08f else 0.10f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(0f, size.height),
            radius = maxOf(size.width * 0.9f, 1f),
        ),
    )
}

/**
 * Включено ли энергосбережение. Система в нём сама выключает размытие окон; «Стекло» делает так же
 * — размытие пересчитывается на каждом кадре листания.
 */
@androidx.compose.runtime.Composable
internal fun rememberPowerSave(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val power = remember(context) { context.getSystemService(android.os.PowerManager::class.java) }
    var on by remember { mutableStateOf(power?.isPowerSaveMode == true) }
    androidx.compose.runtime.DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                on = power?.isPowerSaveMode == true
            }
        }
        context.registerReceiver(receiver, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return on
}
