package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.provider.Settings as SystemSettings
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import ru.whensclass.data.LessonDto
import ru.whensclass.widget.kindName
import ru.whensclass.widget.onlineLabel
import ru.whensclass.widget.roomLabel
import ru.whensclass.widget.lessonTime
import ru.whensclass.widget.shortenName

/** Ширина колонки времени: «09:00–10:30» должно помещаться в одну строку. */
private val TIME_COLUMN = 92.dp

/** Уже этого (ширина экрана в dp, делённая на шрифт) — время над названием, а не сбоку. */
private const val STACK_BELOW_DP = 340

@Composable
internal fun LessonRow(
    lesson: LessonDto,
    bells: Map<String, List<String>>,
    isNow: Boolean,
    past: Boolean = false,
    /** Выбранные группы: значки под временем ([GroupMarks]); пусто — их нет. */
    groups: List<String> = emptyList(),
    groupsByName: Boolean = true,
) {
    // Пара только у других выбранных групп: её видно сразу, а не только по
    // значкам — приглушённая строка на сером фоне и подпись.
    val foreign = groups.isNotEmpty() && 0 !in lesson.slots
    val main = if (past || foreign) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    // Колонка растёт со шрифтом: «09:00–10:30» в sp, колонка в dp, и с
    // крупным шрифтом конец пары уходил в многоточие.
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val timeColumn = TIME_COLUMN * scale
    // Тесно (крупный шрифт или крупный масштаб экрана) — время строкой над
    // названием: сбоку оно оставляло названию ~170 dp, и «алгоритмизации»
    // рвалось посреди слова без дефиса (tested 603, шрифт 1,3).
    val stacked = LocalConfiguration.current.screenWidthDp / scale < STACK_BELOW_DP
    val background = Modifier
        .fillMaxWidth()
        .background(
            when {
                isNow -> MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                // В тёмной теме — белый 7 % поверх карточки, как на сайте:
                // surfaceVariant отличался от неё на 1,07:1, «серый фон»
                // из подсказки был не виден.
                foreign -> if (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
                    Color.White.copy(alpha = 0.07f).compositeOver(MaterialTheme.colorScheme.surface)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.surface
            },
        )
    val subject: @Composable () -> Unit = {
        Column {
            Text(
                lesson.subject,
                // Перенос — по слогам с дефисом и по дефису в слове: без него
                // «Оперативно-розыскная» рвалась «Оперативно-розыскна / я» без
                // всякого знака.
                style = MaterialTheme.typography.bodyLarge.copy(
                    hyphens = Hyphens.Auto,
                    lineBreak = LineBreak.Paragraph,
                ),
                fontWeight = FontWeight.Medium,
                color = main,
                textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
            )
            if (foreign) {
                Text(
                    "Не у вашей группы",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            // Тип занятия и аудитория — то, ради чего сюда и заглядывают,
            // поэтому они идут сразу под названием и заметно, а не подписью
            // мелким шрифтом.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Аудитория ужимается, тип занятия — нет: «Лекция» короче
                // и важнее, а длинное текстовое название места иначе съедало
                // строку целиком.
                val shrink = Modifier.weight(1f, fill = false)
                if (lesson.isOnline) {
                    Place(onlineLabel(lesson).replaceFirstChar { it.uppercase() }, muted = past || foreign, modifier = shrink)
                } else {
                    // Ни кабинета, ни ссылки — так и говорим: пустая строка
                    // читается как «не загрузилось», хотя в таблице там пусто.
                    val room = roomLabel(lesson.room)
                    Place(room ?: "Не указано", muted = room == null || past || foreign, modifier = shrink)
                }
                kindName(lesson.kind)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            lesson.url?.let { OnlineLink(it) }

            // Подпись группы стоит вместо преподавателя: у преподавателя в
            // своём расписании важно, кому читается пара. Чья пара у
            // выбранных групп — видно по значкам, там преподаватель.
            val group = lesson.groups
            if (group != null) {
                Text(
                    group,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                lesson.teachers.forEach {
                    Text(
                        shortenName(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                            "идёт сейчас",
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.width(timeColumn)) {
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
                        // Колонка времени шириной ровно под «09:00–10:30» при
                        // обычном шрифте. С крупным системным диапазон перестаёт
                        // помещаться, и обрыв без многоточия читается как другое
                        // время.
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isNow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NowDot()
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "идёт сейчас",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (groups.isNotEmpty()) GroupMarks(lesson.slots, groups, groupsByName, Modifier.padding(top = 6.dp))
            }

            VerticalDivider(
                modifier = Modifier.fillMaxHeight().padding(start = 12.dp, end = 12.dp),
            )

            Box(modifier = Modifier.weight(1f)) { subject() }
        }
    }
}

/**
 * Место занятия — единственная выделенная пометка в строке.
 *
 * Раньше рядом стояла вторая такая же, для типа занятия, и две капсулы подряд
 * выбивались из спокойного вида остальных строк.
 */
@Composable
private fun Place(text: String, muted: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        // Отсутствие места — не то, что нужно подсвечивать цветом.
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        // Две строки: аудитория бывает и текстом — «Спортзал Б.Хмельницкого
        // 2», «выездная, с 15.00», — и в одну строку её хвост уходил в
        // многоточие рядом с типом занятия. Без
        // многоточия обрыв читался как самостоятельное короткое название, а
        // без weight эта строка отбирала место у типа, и «Лекция» пропадала.
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(end = 8.dp),
    )
}



/**
 * Точка у «идёт сейчас» — медленно дышит, как на сайте: 2,4 с на вдох и
 * выдох, мягко у краёв, только прозрачностью — сжатие шестипиксельной точки
 * дёргалось. С выключенными в системе анимациями стоит на месте.
 */
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
    val breath by rememberInfiniteTransition(label = "идёт сейчас").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "точка",
    )
    Box(
        Modifier
            .size(6.dp)
            // Значение читается при отрисовке слоя: дыхание не пересобирает строку.
            .graphicsLayer { alpha = 0.35f + 0.65f * breath }
            .background(color, CircleShape),
    )
}
