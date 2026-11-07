package ru.whensclass.ui

import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Стекло: размытый фон под полоской дней и шапкой.

/**
 * Матовое стекло под полоской дней. Рисует его сам список, поверх себя, в
 * прямоугольнике полоски ([window]): лист со светом того же места экрана (так
 * стекло не отделяется от фона и закрывает чёткие строки под собой) и свою же
 * копию, размытую. Копию, нарисованную полоской из чужого слоя, Android при
 * листании не обновлял: под полоской застывал первый кадр.
 */
internal class GlassSource(
    val blur: androidx.compose.ui.graphics.layer.GraphicsLayer,
    val dark: Boolean,
    val screen: androidx.compose.ui.geometry.Size,
    /** Низ вкладок на экране: от верха экрана до него — тоже стекло; null — только полоска. */
    val head: androidx.compose.runtime.State<Float>?,
    /** Читается при отрисовке: список перерисовывает стекло на каждом шаге листания. */
    val moved: () -> Any?,
) {
    /** Где список на экране. */
    var origin = androidx.compose.ui.geometry.Offset.Zero

    var flip = false

    /** Где полоска на экране; null — ещё не разложена. */
    var window by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
}

/** Во сколько раз уменьшена копия, которую размывает стекло. */
private const val GLASS_SCALE = 0.25f

internal fun Modifier.glassSource(source: GlassSource): Modifier =
    onGloballyPositioned { source.origin = it.positionInRoot() }
        .drawWithContent {
            source.moved()
            val strip = source.window?.translate(-source.origin)
            if (strip == null) {
                drawContent()
                return@drawWithContent
            }
            if ((source.head?.value ?: 0f) > 0f) {
                // Шапка, вкладки и полоска — прямоугольник во всю ширину от верха
                // экрана до низа полоски (в щели между ними список иначе виден
                // чётким). Чёткий список — только ниже, здесь только размытая
                // копия, а сквозь неё — свет за экраном: закрывать чёткий список
                // листом на всю шапку стоило рывка на каждом кадре листания.
                val window = androidx.compose.ui.geometry.Rect(0f, -source.origin.y, size.width, strip.bottom)
                clipRect(top = window.bottom) { this@drawWithContent.drawContent() }
                // Под шапкой лежат плашки и подписи: там список смазан сильнее и
                // проступает слабее, иначе текст поверх него плохо читается.
                drawBlurred(source, window, 18.dp.toPx(), 0.45f)
                return@drawWithContent
            }
            drawContent()
            val outline = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(strip, androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())))
            }
            clipPath(outline) {
                drawRect(glassBase(source.dark), strip.topLeft, strip.size)
                drawRect(glowTopBrush(source.dark, source.screen, source.origin), strip.topLeft, strip.size)
                this@drawWithContent.drawBlurred(source, strip, 10.dp.toPx(), 1f)
            }
        }

/** Размытая копия списка в [window]. До Android 12 размытия нет — тогда ничего. */
private fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.drawBlurred(
    source: GlassSource,
    window: androidx.compose.ui.geometry.Rect,
    radius: Float,
    alpha: Float,
) {
    if (android.os.Build.VERSION.SDK_INT < 31) return
    // Радиус каждый раз чуть другой: иначе Android считает размытый слой
    // прежним и при листании показывает его старую картинку.
    source.flip = !source.flip
    // Размывается копия вчетверо меньше, потом растягивается.
    val k = GLASS_SCALE
    val r = radius * k + if (source.flip) 0.01f else 0f
    source.blur.alpha = alpha
    // Размытие растекается за края слоя: без обрезки под полоской оставалась
    // мутная полоса шириной в радиус размытия.
    source.blur.clip = true
    source.blur.renderEffect = androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Clamp)
    source.blur.record(size = androidx.compose.ui.unit.IntSize((window.width * k).toInt().coerceAtLeast(1), (window.height * k).toInt().coerceAtLeast(1))) {
        scale(k, androidx.compose.ui.geometry.Offset.Zero) {
            translate(-window.left, -window.top) { this@drawBlurred.drawContent() }
        }
    }
    translate(window.left, window.top) {
        scale(1f / k, androidx.compose.ui.geometry.Offset.Zero) { drawLayer(source.blur) }
    }
}
