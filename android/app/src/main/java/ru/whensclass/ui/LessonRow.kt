package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
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

/** Ширина колонки времени: номер и начало пары; значкам групп под ними нужно больше. */
private val TIME_COLUMN = 72.dp
private val TIME_COLUMN_MARKS = 92.dp

/** Уже этого (ширина экрана в dp, делённая на шрифт) — время над названием, а не сбоку. */
private const val STACK_BELOW_DP = 340

/**
 * Строка пары. Колонка времени одной ширины на все строки, чтобы взгляд
 * находил начало следующей; в тесноте время уходит строкой над названием.
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
) {
    // Пара только у других выбранных групп: её видно сразу, а не только по
    // значкам — приглушённая строка на сером фоне и подпись.
    val foreign = groups.isNotEmpty() && 0 !in lesson.slots
    val main = if (past || foreign) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    // Колонка растёт со шрифтом: время в sp, колонка в dp.
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val timeColumn = (if (groups.isEmpty()) TIME_COLUMN else TIME_COLUMN_MARKS) * scale
    // Тесно (крупный шрифт или масштаб экрана) — время строкой над названием:
    // сбоку оно оставляет названию слишком мало, и длинные слова рвутся.
    val stacked = LocalConfiguration.current.screenWidthDp / scale < STACK_BELOW_DP
    // Пара — плитка внутри карточки дня: видно, где кончается одна и начинается другая.
    val background = Modifier
        .padding(start = 6.dp, end = 6.dp, bottom = 4.dp)
        .fillMaxWidth()
        // Скруглённый фон без обрезки: обрезка по форме на каждом кадре заметно
        // утяжеляла листание, а содержимое за край плитки и так не выходит.
        .background(
            when {
                isNow -> MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                // В тёмной теме — белый 7 % поверх карточки, как на сайте:
                // surfaceVariant от неё почти не отличим.
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
                lesson.subject,
                // Перенос — по слогам с дефисом и по дефису в слове, а не
                // посреди слова без знака.
                style = MaterialTheme.typography.bodyLarge.copy(
                    hyphens = Hyphens.Auto,
                    lineBreak = LineBreak.Paragraph,
                ),
                fontWeight = FontWeight.SemiBold,
                color = main,
                textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
            )

            Spacer(Modifier.height(4.dp))
            // Тип занятия и аудитория — то, ради чего сюда заглядывают: сразу
            // под названием и заметно.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shrink = Modifier.weight(1f, fill = false)
                if (lesson.isOnline) {
                    Place(onlineLabel(lesson).replaceFirstChar { it.uppercase() }, muted = past || foreign, modifier = shrink)
                } else {
                    // Ни кабинета, ни ссылки — так и говорим: пустая строка
                    // читается как «не загрузилось», хотя в таблице там пусто.
                    val room = roomLabel(lesson.room)
                    Place(room ?: "Не указано", muted = room == null || past || foreign, modifier = shrink)
                }
            }

            lesson.url?.let { OnlineLink(it, muted = past) }

            // Подпись группы — вместо преподавателя: в своём расписании
            // преподавателю важно, кому читается пара.
            val who = lesson.groups?.let(::listOf) ?: lesson.teachers.map(::shortenName)
            // Тип занятия — через точку после последней подписи: рядом с длинным
            // местом он висел бы посреди его строк.
            val kind = kindName(lesson.kind)
            (if (kind == null) who else if (who.isEmpty()) listOf(kind)
            else who.dropLast(1) + "${who.last()} · $kind").forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (lesson.isCancelled) {
                Text(
                    lesson.note?.let { "Отменена — $it" } ?: "Отменена",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                // У замены — «вместо: Математика»: без неё новая пара в том же
                // часе выглядела бы ошибкой таблицы.
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
        Column(modifier = background.padding(horizontal = 16.dp, vertical = 12.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${lesson.number} пара",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                lessonTime(bells, lesson.number)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
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
            Spacer(Modifier.height(6.dp))
            subject()
        }
    } else {
        Row(
            modifier = background
                .height(IntrinsicSize.Min)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.width(timeColumn)) {
                Text(
                    "${lesson.number} пара",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
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
                if (groups.isNotEmpty()) GroupMarks(lesson.slots, groups, groupsByName, Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.width(10.dp))

            Box(modifier = Modifier.weight(1f)) { subject() }
        }
    }
}

/** Место занятия — единственная выделенная пометка в строке. */
@Composable
private fun Place(text: String, muted: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        // Отсутствие места — не то, что нужно подсвечивать цветом.
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        // Три строки: аудитория бывает и текстом — «Спортзал Б.Хмельницкого
        // 2», «выездная, с 15.00». Многоточие — чтобы обрыв не читался
        // самостоятельным названием.
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}



/**
 * Точка у «идёт сейчас» — медленно дышит, как на сайте: 2,4 с на вдох и
 * выдох, мягко у краёв, только прозрачностью — сжатие шестипиксельной точки
 * дёргалось. С выключенными в системе анимациями стоит на месте.
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
    // Около 15 кадров в секунду, а не 60: для медленного дыхания хватает, а
    // экран с идущей парой не перерисовывается на каждом кадре — бережёт батарею.
    // Ждём кадра: приложение свёрнуто — кадров нет, и точка стоит.
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
