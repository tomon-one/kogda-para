package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.provider.Settings as SystemSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.whensclass.data.LessonDto
import ru.whensclass.widget.kindName
import ru.whensclass.widget.onlineLabel
import ru.whensclass.widget.roomLabel
import ru.whensclass.widget.lessonTime
import ru.whensclass.widget.shortenName

/**
 * Ширина колонки времени: номер и начало пары; одна и со значками групп под ними: три номера в ряд
 * и «/1» влезают, длинное чужое название ужимается многоточием — название пары начинается в одном
 * месте.
 */
private val TIME_COLUMN = 72.dp

/** Уже этого (ширина экрана в dp, делённая на шрифт) — время над названием, а не сбоку. */
private const val STACK_BELOW_DP = 340

/** Уже этого (ширина экрана в dp) компактный вид включается сам, без настройки. */
private const val COMPACT_BELOW_DP = 360f

/**
 * Компактный вид: выбор человека сильнее ширины, а пока выбора нет, решает она — экран уже
 * [COMPACT_BELOW_DP] теснее всегда (как на сайте: `compactOn`).
 */
internal fun compactView(choice: Boolean?, widthDp: Float): Boolean =
    choice ?: (widthDp < COMPACT_BELOW_DP)

/**
 * Строка пары. Колонка времени одной ширины на все строки, чтобы взгляд находил начало следующей; в
 * тесноте время уходит строкой над названием.
 */
@Composable
internal fun LessonRow(
    lesson: LessonDto,
    bells: Map<String, List<String>>,
    isNow: Boolean,
    past: Boolean = false,
    /** Выбранные группы: значки под временем ([GroupMarks]); пусто — их нет. */
    groups: List<String> = emptyList(),
    groupsByName: Boolean = true,
    /** «Пары только у …» уже сказано под датой — у пары не повторять. */
    saidAbove: Boolean = false,
    /** Название предмета как его показывать: короткое от службы или своё. */
    subject: String = lesson.subject,
    /** Компактный вид: время одной строкой, подписи в одну строку, отступы меньше; null — по ширине. */
    compact: Boolean? = null,
) {
    // Пара только у других выбранных групп: видна сразу — приглушённая строка на сером фоне и подпись.
    val foreign = groups.isNotEmpty() && 0 !in lesson.slots
    val main = if (past || foreign) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    // Колонка растёт со шрифтом: время в sp, колонка в dp.
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val timeColumn = TIME_COLUMN * scale
    // Тесно (крупный шрифт или масштаб) — время строкой над названием: сбоку названию остаётся
    // мало, и длинные слова рвутся.
    val width = LocalConfiguration.current.screenWidthDp / scale
    val stacked = width < STACK_BELOW_DP
    val tight = compactView(choice = compact, widthDp = width)
    // Пара — плитка внутри карточки дня: видно, где кончается одна и начинается другая.
    val background = Modifier
        .padding(start = 6.dp, end = 6.dp, bottom = if (tight) 3.dp else 4.dp)
        .fillMaxWidth()
        // Скруглённый фон без обрезки: обрезка по форме на каждом кадре утяжеляла листание, а
        // содержимое за край плитки не выходит.
        .background(
            when {
                isNow -> MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                // В тёмной теме — белый 7 % поверх карточки, как на сайте: surfaceVariant от неё почти не отличим.
                foreign -> if (MaterialTheme.colorScheme.isDark)
                    Color.White.copy(alpha = 0.07f).compositeOver(MaterialTheme.colorScheme.surface)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.tile
            },
            RoundedCornerShape(14.dp),
        )
    val subject: @Composable () -> Unit = {
        Column {
            // Чья пара — первой строкой: сначала видно, что она не своя.
            if (foreign && !saidAbove) {
                Text(
                    onlyAt(lesson.slots, groups),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                subject,
                // Перенос — по слогам с дефисом и по дефису в слове, а не посреди слова без знака.
                style = MaterialTheme.typography.bodyLarge.copy(
                    // Компактно — на пункт меньше: строк в карточке помещается больше.
                    fontSize = if (tight) 15.sp else MaterialTheme.typography.bodyLarge.fontSize,
                    hyphens = Hyphens.Auto,
                    lineBreak = LineBreak.Paragraph,
                ),
                fontWeight = FontWeight.SemiBold,
                color = main,
                textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
            )

            Spacer(Modifier.height(if (tight) 2.dp else 4.dp))
            // Тип занятия и аудитория — то, ради чего сюда заглядывают: сразу под названием.
            val place: @Composable (Modifier) -> Unit = { placeModifier ->
                if (lesson.isOnline) {
                    Place(onlineLabel(lesson).replaceFirstChar { it.uppercase() }, muted = past || foreign, modifier = placeModifier)
                } else {
                    // Ни кабинета, ни ссылки — так и говорим: пустая строка читается как «не загрузилось».
                    val room = roomLabel(lesson.room)
                    Place(room ?: "Не указано", muted = room == null || past || foreign, modifier = placeModifier)
                }
            }

            // Подпись группы — вместо преподавателя: в своём расписании преподавателю важно, кому читается пара.
            val who = lesson.groups?.let(::listOf) ?: lesson.teachers.map(::shortenName)
            // Тип занятия — через точку после последней подписи: рядом с длинным местом он висел бы посреди его строк.
            val kind = kindName(lesson.kind)
            val line = if (kind == null) who else if (who.isEmpty()) listOf(kind)
            else who.dropLast(1) + "${who.last()} · $kind"
            if (tight) {
                // Тесно: место и подпись — одной строкой, а не столбиком; длинная переносится по словам.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    place(Modifier)
                    if (line.isNotEmpty()) {
                        Text(
                            line.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // Обычный вид: ссылка на пару идёт сразу за местом, подписи — под ней (как было).
                Row(verticalAlignment = Alignment.CenterVertically) { place(Modifier.weight(1f, fill = false)) }
                lesson.url?.let { OnlineLink(it, muted = past) }
                line.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (tight) {
                lesson.url?.let { OnlineLink(it, muted = past) }
            }

            if (lesson.isCancelled) {
                Text(
                    lesson.note?.let { "Отменена — $it" } ?: "Отменена",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                // У замены — «вместо: Математика»: без неё новая пара в том же часе выглядела бы ошибкой таблицы.
                lesson.note?.let {
                    Text(
                        it.replaceFirstChar { c -> c.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        }
    }
    if (stacked) {
        Column(modifier = background.padding(horizontal = if (tight) 8.dp else 16.dp, vertical = if (tight) 6.dp else 12.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (tight) 8.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (tight) 2.dp else 4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${lesson.number} пара",
                    style = if (tight) MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                    else MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                lessonTime(bells, lesson.number)?.let {
                    Text(
                        it,
                        style = if (tight) MaterialTheme.typography.labelMedium
                            .copy(fontSize = 11.sp, lineHeight = 14.sp)
                        else MaterialTheme.typography.labelLarge,
                        fontWeight = if (isNow) FontWeight.Bold else FontWeight.Medium,
                        color = if (isNow) MaterialTheme.colorScheme.primary else main,
                        maxLines = 1,
                    )
                }
                if (isNow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NowDot()
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "идёт",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (groups.isNotEmpty()) GroupMarks(lesson.slots, groups, groupsByName)
            }
            Spacer(Modifier.height(if (tight) 4.dp else 6.dp))
            subject()
        }
    } else {
        Row(
            modifier = background
                .height(IntrinsicSize.Min)
                .padding(horizontal = if (tight) 8.dp else 12.dp, vertical = if (tight) 6.dp else 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.width(timeColumn)) {
                Text(
                    "${lesson.number} пара",
                    style = if (tight) MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                    else MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (tight) {
                    // Компактно — одна строка «09:00–10:30» вместо начала крупно и конца под ним.
                    lessonTime(bells, lesson.number)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 14.sp),
                            fontWeight = if (isNow) FontWeight.Bold else FontWeight.Medium,
                            color = if (isNow) MaterialTheme.colorScheme.primary else main,
                            maxLines = 1,
                        )
                    }
                } else {
                    // Начало крупно, конец под ним.
                    bells[lesson.number.toString()]?.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { start ->
                        Text(
                            start,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 22.sp),
                            fontWeight = FontWeight.Bold,
                            color = if (isNow) MaterialTheme.colorScheme.primary else main,
                            maxLines = 1,
                        )
                        bells[lesson.number.toString()]?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { end ->
                            Text(
                                end,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (isNow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NowDot()
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "идёт",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (groups.isNotEmpty()) GroupMarks(lesson.slots, groups, groupsByName, Modifier.padding(top = if (tight) 4.dp else 6.dp))
            }

            Spacer(Modifier.width(if (tight) 8.dp else 10.dp))

            Box(modifier = Modifier.weight(1f)) { subject() }
        }
    }
}

/** Место занятия — единственная выделенная пометка в строке. */
@Composable
private fun Place(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        // Отсутствие места — не то, что нужно подсвечивать цветом.
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        // Три строки: аудитория бывает и текстом — «Спортзал Б.Хмельницкого 2», «выездная, с
        // 15.00». Многоточие — чтобы обрыв не читался самостоятельным названием.
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}



/**
 * Точка у «идёт сейчас» — медленно дышит, как на сайте: 2,4 с на вдох и выдох, только прозрачностью
 * (сжатие шестипиксельной точки дёргалось). С выключенными анимациями стоит.
 */
/** Вдох и выдох точки «идёт сейчас», как на сайте (motion.css, pulse). */
private const val BREATH_MILLIS = 2400L

@Composable
private fun NowDot() {
    val context = LocalContext.current
    val still = remember {
        SystemSettings.Global.getFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val color = MaterialTheme.colorScheme.primary
    if (still) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        return
    }
    // Около 15 кадров в секунду, а не 60: для дыхания хватает и бережёт батарею. Ждём кадра:
    // приложение свёрнуто — точка стоит.
    val breath = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            val t = withFrameMillis { it } - start
            breath.floatValue = ((kotlin.math.cos(t % BREATH_MILLIS / BREATH_MILLIS.toDouble() * 2 * Math.PI) + 1) / 2).toFloat()
            kotlinx.coroutines.delay(66)
        }
    }
    Box(
        Modifier
            .size(6.dp)
            // Значение читается при отрисовке слоя: дыхание не пересобирает строку.
            .graphicsLayer { alpha = 0.35f + 0.65f * breath.floatValue }
            .background(color, CircleShape),
    )
}
