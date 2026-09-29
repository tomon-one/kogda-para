package ru.whensclass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Свои переключатели и кнопки.
 *
 * Материаловские выглядят как чужие: круглый тумблер-таблетка и кнопка без
 * границ, которая «летает» по карточке, не показывая, где по ней нажимать.
 * Здесь всё прямоугольное со скруглением — в тон карточкам настроек и
 * виджетам, где углы такие же.
 *
 * Анимация считается в фазах разметки и отрисовки, а не пересобирает дерево
 * на каждый кадр: в списке настроек это заметно.
 */

/**
 * Размеры кнопок — одни на всё приложение (Tomon 29.09: «все элементы ровные,
 * единого неизменяемого размера»). Высота кнопки не зависит от надписи и
 * крупного шрифта: надпись в одну строку и, если не влезает, мельчает сама
 * ([FIT]). Кнопки в строке с текстом — одной ширины [ROW_BUTTON], остальные
 * — во всю ширину карточки, и смена надписи («Проверка…») их не двигает.
 */
val BUTTON_HEIGHT = 44.dp

/** Ширина кнопки в строке с названием: «Сменить», «Убрать», «Скопировать», минуты. */
val ROW_BUTTON = 116.dp

/** Надпись, которой тесно, мельчает до 11 sp, а не рвётся на две строки. */
internal val FIT = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp, stepSize = 0.5.sp)

/**
 * Движение бегунка.
 *
 * Пружина, а не отсчёт по времени: сдвиг за ровные 140 мс читался как рывок.
 * Без подпрыгивания — тумблер не игрушка.
 */
private val MOTION = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** Тумблер: прямоугольный трек, квадратный бегунок. */
/**
 * Тумблер. С [onCheckedChange] = null — только вид, без своей нажимаемости:
 * так он ведёт себя внутри [SwitchRow], где нажимается вся строка.
 */
@Composable
fun MinimalSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Одна доля пути на всё: и сдвиг, и цвет считаются из неё в фазах разметки
    // и отрисовки. Раньше каждый кадр анимации пересобирал разметку целиком —
    // отсюда и дёрганье.
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = MOTION,
        label = "switch",
    )
    val trackOn = MaterialTheme.colorScheme.primary
    val trackOff = MaterialTheme.colorScheme.surfaceVariant
    val thumbOn = MaterialTheme.colorScheme.onPrimary
    val thumbOff = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .size(width = 42.dp, height = 24.dp)
            .drawBehind {
                drawRoundRect(
                    color = lerp(trackOff, trackOn, progress),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
            }
            .then(
                if (onCheckedChange == null) Modifier
                else Modifier.toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    role = Role.Switch,
                ),
            )
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset((18.dp.toPx() * progress).roundToInt(), 0) }
                .size(18.dp)
                .drawBehind {
                    drawRoundRect(
                        color = lerp(thumbOff, thumbOn, progress),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                },
        )
    }
}

/** Выбор одного из нескольких — вместо круглой точки Material. */
@Composable
fun MinimalCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MOTION,
        label = "check",
    )
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .size(20.dp)
            .drawBehind {
                val color = lerp(off, on, progress)
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                if (progress > 0f) {
                    val side = 10.dp.toPx() * progress
                    drawRoundRect(
                        color = color,
                        topLeft = Offset((size.width - side) / 2, (size.height - side) / 2),
                        size = Size(side, side),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            },
    )
}

/**
 * Выбор одного из нескольких одной строкой. Три варианта темы столбиком с
 * галочками занимали полкарточки ради одной настройки.
 */
@Composable
fun <T> Segmented(
    options: List<Pair<String, T>>,
    selected: T,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BUTTON_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        options.forEach { (label, value) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                    )
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = FIT,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/**
 * Дно длинного списка.
 *
 * Видит только тот, кто долистал до конца вместо того, чтобы искать
 * поиском. Ничего не делает — говорит, что дальше ничего нет, и сколько
 * было. Показывать её под отфильтрованным списком нельзя: это будет уже
 * не дно, а середина.
 */
@Composable
fun ListEnd(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp),
    )
}

/**
 * Пометка «нажатие уводит из приложения».
 *
 * Строка с тумблером обещает, что переключение случится здесь. Для точного
 * времени напоминаний это неправда: разрешение выдаёт система, и приложение
 * может только открыть нужный экран. Без пометки нажатие выглядит поломкой —
 * тумблер не двигается, а вместо этого куда-то уносит.
 *
 * Рисуем сами: рамка с вырезанным углом и стрелка наружу. Своего набора
 * значков в приложении нет, а тащить материаловский ради одной картинки — это
 * лишние полтора мегабайта в APK.
 */
@Composable
fun ExternalMark(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(16.dp)
            .drawBehind {
                val w = size.width
                val h = size.height
                val line = 1.5.dp.toPx()
                val cap = StrokeCap.Round
                val left = line / 2
                val bottom = h - line / 2
                val right = w * 0.66f
                val top = h * 0.34f
                // Угол не дорисовываем: в разрыв уходит стрелка.
                val gap = w * 0.20f
                drawLine(color, Offset(left, top), Offset(left, bottom), line, cap)
                drawLine(color, Offset(left, bottom), Offset(right, bottom), line, cap)
                drawLine(color, Offset(right, bottom), Offset(right, top + gap), line, cap)
                drawLine(color, Offset(left, top), Offset(right - gap, top), line, cap)

                val tipX = w - line / 2
                val tipY = line / 2
                drawLine(color, Offset(w * 0.40f, h * 0.60f), Offset(tipX, tipY), line, cap)
                drawLine(color, Offset(tipX, tipY), Offset(tipX - w * 0.32f, tipY), line, cap)
                drawLine(color, Offset(tipX, tipY), Offset(tipX, tipY + h * 0.32f), line, cap)
            },
    )
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
    /** Отступ сверху — от строки над кнопкой; в одной строке с текстом не нужен. */
    top: Dp = 6.dp,
    /** Поля слева и справа: у кнопок в ряд и в строке — уже, чтобы влезло слово. */
    side: Dp = 14.dp,
    /** Что читать чтецу экрана вместо надписи: «Убрать ИСП-924/2», а не «Убрать». */
    spoken: String? = null,
) {
    // По центру: кнопкам одной ширины (раздел «Группа», ряд виджетов)
    // текст у левого края выглядел съехавшим.
    Box(
        modifier = modifier
            .padding(top = top)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .height(BUTTON_HEIGHT)
            .padding(horizontal = side),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            // Надпись чтецу — не нужна, когда есть полная (spoken).
            modifier = if (spoken != null) Modifier.clearAndSetSemantics {} else Modifier,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            autoSize = FIT,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Положить текст в буфер обмена и сказать об этом.
 *
 * С Android 13 система показывает это сама, и собственное сообщение поверх
 * системного читается как заикание. Поэтому тост только для тех, кто иначе
 * не поймёт, случилось ли что-нибудь.
 */
fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
    toast: String = "Скопировано",
) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Строка настройки с тумблером — нажимается целиком.
 *
 * Сам тумблер 42×24 dp: вдвое ниже той высоты, до которой палец достаёт не
 * целясь. А нажимают всё равно по названию — соседние строки того же экрана,
 * выбор темы и точные уведомления, именно так и устроены, и человек ждёт того
 * же здесь. Раньше нажатие по надписи не делало ничего.
 */
@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        MinimalSwitch(checked = checked, onCheckedChange = null)
    }
}
