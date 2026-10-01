package ru.whensclass.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import java.time.LocalDate
import java.time.LocalDateTime
import ru.whensclass.data.LessonDto
import ru.whensclass.data.isKnownWebinar

@Composable
internal fun Lessons(
    lessons: List<LessonDto>,
    bells: Map<String, List<String>>,
    day: LocalDate,
    now: LocalDateTime,
    fit: Fit,
    colors: Palette,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    if (lessons.isEmpty()) {
        // Подстраховка: без строк виджет остался бы одной шапкой.
        MissingHint("Пар нет", colors)
        return
    }
    val current = currentLessonNumber(bells, day, now)
    // Обычный список, не ленивый: ленивый живёт, пока жив процесс приложения,
    // и после его выгрузки виджет чернеет насовсем.
    // Высоты сняты со снимка экрана (TECNO LF7n, плотность 480): шаг между
    // плашками пар — 44,3 dp, от верха карточки до первой — 47,7 dp. Округлено
    // вверх: ошибка в меньшую сторону обрежет последнюю пару корпусом.
    val rowHeight = (if (fit.dense) 41.dp else 45.dp) * fontScale()
    // Место под шапкой; строки «прошло/ещё N» вычитаются ниже, отдельно.
    val free = LocalSize.current.height - (if (fit.dense) 42.dp else 50.dp) * fontScale()
    // Строки «прошло N пар» и «ещё N пар» занимают место, а нужны ли они —
    // видно только после выбора окна. Поэтому прикидка, потом уточнение:
    // одного круга хватает.
    val lineHeight = 17.dp * fontScale()

    // Сколько целых пар помещается — и не больше, иначе последнюю обрежет
    // корпус. Одна пара — минимум: пустой виджет хуже одной строки.
    fun room(reserved: Dp): Int {
        val left = free - reserved
        return (left / rowHeight).toInt().coerceAtLeast(1).coerceAtMost(lessons.size)
    }

    var fits = room(0.dp)
    var start = windowStart(lessons, bells, day, fits, now)
    val labels = (if (start > 0) 1 else 0) + (if (start + fits < lessons.size) 1 else 0)
    if (labels > 0) {
        // Подписи занимают места в контейнере наравне с парами, а их всего десять.
        fits = room(lineHeight * labels).coerceAtMost(MAX_CHILDREN - labels)
        start = windowStart(lessons, bells, day, fits, now)
    }

    val shown = lessons.subList(start, minOf(lessons.size, start + fits))
    // Два разных числа: сверху прячется прожитое, снизу — предстоящее.
    val passed = numbers(lessons.subList(0, start))
    val ahead = numbers(lessons.subList(start + shown.size, lessons.size))

    Column(modifier = modifier) {
        // Прожитое называем сверху — там, где оно исчезло.
        if (passed > 0) HiddenLine(passedPairs(passed), day, colors)
        shown.forEach { lesson ->
            // Пара и отступ под ней — одним контейнером: в контейнере виджета
            // не больше десяти детей.
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                // Отменённая пара в своё время — не «идёт сейчас».
                LessonRow(lesson, day, bells, isNow = lesson.number == current && !lesson.isCancelled, fit, colors)
                Spacer(GlanceModifier.height(if (fit.dense) 3.dp else 4.dp))
            }
        }
        if (ahead > 0) HiddenLine(morePairs(ahead), day, colors)
    }
}

/** Строка о парах, которые в виджет не поместились. Нажатие открывает день. */
@Composable
private fun HiddenLine(text: String, day: LocalDate, colors: Palette) {
    val context = LocalContext.current
    Text(
        text,
        maxLines = 1,
        style = TextStyle(fontSize = 11.sp, color = colors.textDim),
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .clickable(actionStartActivity(openDay(context, day))),
    )
}


@Composable
private fun LessonRow(
    lesson: LessonDto,
    day: LocalDate,
    bells: Map<String, List<String>>,
    isNow: Boolean,
    fit: Fit,
    colors: Palette,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(if (isNow) colors.nowSurface else colors.surface)
            .cornerRadius(10.dp)
            .padding(horizontal = 8.dp, vertical = if (fit.dense) 3.dp else 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Ширина — на «09:00–10:30» одной строкой; на узком виджете — только
        // начало пары.
        Column(modifier = GlanceModifier.width((if (fit.narrow) 48.dp else 72.dp) * fit.scale)) {
            // У текущей пары номер уступает место «идёт сейчас» — в той же
            // строке, высота пары не меняется.
            Text(
                when {
                    isNow && fit.narrow -> "сейчас"
                    isNow -> "идёт сейчас"
                    else -> "${lesson.number} пара"
                },
                maxLines = 1,
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = if (isNow) FontWeight.Medium else FontWeight.Normal,
                    color = if (isNow) colors.accent else colors.textDim,
                ),
            )
            val time = if (fit.narrow) {
                lessonStart(bells, lesson.number)
            } else {
                lessonTime(bells, lesson.number)
            }
            time?.let { time ->
                Text(
                    time,
                    maxLines = 1,
                    style = TextStyle(fontSize = 11.sp, color = colors.text),
                )
            }
        }
        Spacer(GlanceModifier.width(6.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                lesson.subject,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
                ),
            )
            Details(lesson, day, colors)
        }
    }
}

@Composable
private fun Details(lesson: LessonDto, day: LocalDate, colors: Palette) {
    // Тип, место и преподаватель — одной строкой, чтобы в виджет помещались
    // хотя бы две пары.
    val parts = buildList {
        if (lesson.isCancelled) add(lesson.note?.let { "отменена — $it" } ?: "отменена")
        // Замена — первым словом, как «Вместо: …» на экране; место — раньше
        // типа: строка обрезается справа.
        if (lesson.replaces != null && !lesson.isCancelled) add("замена")
        add(if (lesson.isOnline) onlineLabel(lesson) else roomLabel(lesson.room) ?: "место не указано")
        kindName(lesson.kind)?.let { add(it) }
        // В расписании преподавателя вместо его имени — группы.
        (lesson.groups ?: lesson.teachers.firstOrNull()?.let(::surnameOnly))?.let { add(it) }
    }
    if (parts.isEmpty()) return

    val line = GlanceModifier.fillMaxWidth()
    val context = LocalContext.current
    // Ссылка на чужой адрес одним нажатием не копируется: нажатие ведёт на
    // экран пары, где хост назван.
    val foreign = lesson.url?.let { !isKnownWebinar(it) } == true
    Text(
        // Значок впереди строки: в хвосте его утащила бы за край длинная фамилия.
        when {
            foreign -> "⚠ чужая ссылка · " + parts.joinToString(" · ")
            lesson.url != null -> "⧉  " + parts.joinToString(" · ")
            else -> parts.joinToString(" · ")
        },
        maxLines = 1,
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                lesson.isCancelled || foreign -> colors.error
                lesson.isOnline -> colors.accent
                else -> colors.text
            },
        ),
        modifier = when {
            foreign -> line.clickable(actionStartActivity(openDay(context, day)))
            lesson.url != null ->
                line.clickable(actionStartActivity(CopyLinkActivity.intent(context, lesson.url)))
            else -> line
        },
    )
}
