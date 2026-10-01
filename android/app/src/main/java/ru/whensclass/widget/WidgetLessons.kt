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
        // Подстраховка: список без строк оставлял виджет пустым, и человек
        // видел только шапку на чёрном фоне.
        MissingHint("Пар нет", colors)
        return
    }
    val current = currentLessonNumber(bells, day, now)
    // Обычный список, не ленивый. Ленивый прокручивался пальцем, но жил только
    // пока жив процесс приложения: система выгружала его — и виджет чернел
    // насовсем, не оживая ни обновлением, ни запуском приложения.
    // Высоты перемерены по снимку экрана 9 сентября (TECNO LF7n, плотность 480):
    // шаг между плашками пар — 44,3 dp, от верха карточки до первой плашки —
    // 47,7 dp. Прежние 52 и 58+16 были сняты на глаз и завышены: на виджете
    // в 200 dp они съедали целую строку.
    //
    // Округлено вверх намеренно: ошибиться в меньшую сторону значит обрезать
    // последнюю пару корпусом виджета, а прокрутки внутри виджета нет.
    val rowHeight = (if (fit.dense) 41.dp else 45.dp) * fontScale()
    // Шапка с группой и стрелками плюс строка «ещё N»: их место списку не
    // достаётся. Раньше «ещё» отнимало строку у пары, и вместо двух занятий
    // виджет показывал одно — хуже, чем не показать остаток вовсе.
    val free = LocalSize.current.height - (if (fit.dense) 42.dp else 50.dp) * fontScale()
    // Обычно показываем не меньше двух пар: одна пара на весь виджет
    // выглядит как поломка. Но если оболочка ужала виджет ниже собственного
    // минимума — а Nova это умеет, — вторая строка не влезет и обрежется
    // корпусом. Тогда честнее показать одну целиком.
    // Строка «прошло N пар» или «ещё N пар» тоже занимает место, и сколько их
    // будет — видно только после того, как выбрано окно. Поэтому прикидка,
    // потом уточнение: одного круга хватает, дальше число не меняется.
    val lineHeight = 17.dp * fontScale()

    // Сколько целых пар помещается — и не больше. Раньше при месте на одну-две
    // пары (1 ≤ left/rowHeight < 2) виджет всё равно ставил две, и вторую
    // обрезал корпус. Одна
    // пара — минимум: пустой виджет хуже одной строки.
    fun room(reserved: Dp): Int {
        val left = free - reserved
        return (left / rowHeight).toInt().coerceAtLeast(1).coerceAtMost(lessons.size)
    }

    var fits = room(0.dp)
    var start = windowStart(lessons, bells, day, fits, now)
    val labels = (if (start > 0) 1 else 0) + (if (start + fits < lessons.size) 1 else 0)
    if (labels > 0) {
        // Больше восьми пар и так не бывает, но подписи занимают места в
        // контейнере наравне с парами, а их всего десять.
        fits = room(lineHeight * labels).coerceAtMost(MAX_CHILDREN - labels)
        start = windowStart(lessons, bells, day, fits, now)
    }

    val shown = lessons.subList(start, minOf(lessons.size, start + fits))
    // Два разных числа, а не одно. Сверху прячется прожитое, снизу —
    // предстоящее, и человеку это не одно и то же: «ещё две пары» под списком
    // обещает пары впереди, даже когда они давно кончились.
    val passed = start
    val ahead = lessons.size - start - shown.size

    Column(modifier = modifier) {
        // Сверху — прожитое: список едет вниз вместе с днём, и то, что уехало
        // за верхний край, должно быть названо там же, где исчезло.
        if (passed > 0) HiddenLine(passedPairs(passed), day, colors)
        shown.forEach { lesson ->
            // Пара и отступ под ней — одним контейнером: разметка виджета
            // вмещает не больше десяти детей, и по два на пару их не хватало бы
            // на длинный день.
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
        // Ширины хватает на «09:00–10:30» одной строкой: время, переносимое
        // пополам, читается как опечатка. На узком виджете диапазон не влезает —
        // тогда показываем только начало пары.
        Column(modifier = GlanceModifier.width((if (fit.narrow) 48.dp else 72.dp) * fit.scale)) {
            // У текущей пары номер уступает место словам: номер и так виден по
            // времени рядом, а «идёт сейчас» ищут глазами первым. Строка та же,
            // поэтому высота пары не меняется.
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
    // Одной строкой, а не тремя. Раньше тип, место и преподаватель занимали по
    // строке каждый, пара выходила в четыре строки высотой, и в виджет помещалась
    // одна — при том что смотрят в него ради двух ближайших.
    val parts = buildList {
        if (lesson.isCancelled) add(lesson.note?.let { "отменена — $it" } ?: "отменена")
        // Место — раньше типа: строка одна, и тип вытеснял кабинет в
        // многоточие.
        // Замена — первым словом, как «Вместо: …» на экране.
        if (lesson.replaces != null && !lesson.isCancelled) add("замена")
        add(if (lesson.isOnline) onlineLabel(lesson) else roomLabel(lesson.room) ?: "место не указано")
        kindName(lesson.kind)?.let { add(it) }
        // В расписании преподавателя вместо его имени — группы, которым читается
        // пара: сам он и так знает, кто ведёт.
        (lesson.groups ?: lesson.teachers.firstOrNull()?.let(::surnameOnly))?.let { add(it) }
    }
    if (parts.isEmpty()) return

    val line = GlanceModifier.fillMaxWidth()
    val context = LocalContext.current
    // Ссылка на чужой адрес одним нажатием не копируется: без хоста и без
    // пометки её вставляли в браузер, не глядя. Нажатие ведёт на экран пары,
    // где хост назван.
    val foreign = lesson.url?.let { !isKnownWebinar(it) } == true
    Text(
        // Значок впереди строки, а не в хвосте: строка одна и обрезается
        // справа, так что длинная фамилия преподавателя утаскивала за край
        // единственную кнопку, ради которой на пару и нажимают.
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
