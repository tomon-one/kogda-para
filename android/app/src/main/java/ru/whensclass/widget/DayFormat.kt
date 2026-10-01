package ru.whensclass.widget

import ru.whensclass.data.LessonDto
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale("ru")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMMM, EEEE", RU)
private val SHORT_DAY = DateTimeFormatter.ofPattern("d MMMM", RU)

/**
 * Пояс колледжа. Сетка звонков и дни листа — по Новосибирску, и сравнивать их
 * надо с тамошним «сейчас», а не с часами телефона: у студента в другом поясе
 * (онлайн-пары) подсветка и напоминания приходили не в те часы. Моменты вроде «обновлено в 17:00» показываются по телефону — это время
 * на часах человека.
 */
val COLLEGE_ZONE: ZoneId = ZoneId.of("Asia/Novosibirsk")

fun collegeNow(): LocalDateTime = LocalDateTime.now(COLLEGE_ZONE)

fun collegeToday(): LocalDate = LocalDate.now(COLLEGE_ZONE)

/**
 * Ближайший момент, когда содержимое меняется само: звонок (начало или конец
 * пары) сегодня позже `now` или полночь с минутой.
 *
 * Полночь — ближайшие 00:01, а не завтрашние: будильник, заведённый в первую
 * минуту суток, перешагивал сегодняшние 00:01 и вставал на первый звонок, и
 * листание «на завтра» держалось до 09:00.
 */
fun nextTick(bells: Map<String, List<String>>, now: LocalDateTime): LocalDateTime {
    val midnight = now.toLocalDate().atTime(LocalTime.of(0, 1))
        .let { if (it.isAfter(now)) it else it.plusDays(1) }
    val bell = bells.values.flatten()
        .mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
        .map { now.toLocalDate().atTime(it) }
        .filter { it.isAfter(now) }
        .minOrNull()
    return listOfNotNull(bell, midnight).min()
}

/** Короткая подпись дня для виджета: «сегодня, 7 сентября», «пт, 11 сентября». */
fun formatDayTitleShort(day: LocalDate): String {
    val today = collegeToday()
    return when (day) {
        today -> "сегодня, " + day.format(SHORT_DAY)
        today.plusDays(1) -> "завтра, " + day.format(SHORT_DAY)
        // Виджет листается и назад, к прожитым дням недели.
        today.minusDays(1) -> "вчера, " + day.format(SHORT_DAY)
        else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMMM", RU))
    }
}

/** Подпись дня в недельном виджете: «пн, 8 сент.». */
fun formatWeekDay(day: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern("EEE, d MMM", RU))

/**
 * Заголовок недельного виджета: «Неделя 7–12 сент.».
 *
 * Одного слова «Неделя» мало: на экране видны не все её дни, и какая именно это
 * неделя — по ним не всегда понятно. Месяц пишем один раз, если неделя его не
 * пересекает.
 */
fun formatWeekRange(from: LocalDate, to: LocalDate): String {
    val month = DateTimeFormatter.ofPattern("MMM", RU)
    // В субботу и в последний день листа виден один день, и «12–12 сент.»
    // читается как опечатка.
    if (from == to) return "Неделя, ${from.dayOfMonth} ${from.format(month)}"
    return if (from.month == to.month) {
        "Неделя ${from.dayOfMonth}–${to.dayOfMonth} ${to.format(month)}"
    } else {
        "Неделя ${from.dayOfMonth} ${from.format(month)} – ${to.dayOfMonth} ${to.format(month)}"
    }
}

fun formatDayTitle(day: LocalDate): String {
    val today = collegeToday()
    // «Послезавтра» человек и так посчитает по дате, а вот «вчера» помогает:
    // прошедшие дни остаются в списке, и их надо отличать с одного взгляда.
    val prefix = when (day) {
        today -> "сегодня"
        today.plusDays(1) -> "завтра"
        today.minusDays(1) -> "вчера"
        else -> null
    }
    val date = day.format(DAY_FORMAT)
    return if (prefix != null) "$prefix, $date" else date
}

fun formatFetchedAt(millis: Long): String {
    // «Проверено», а не «обновлено»: время ставится при каждой удачной
    // проверке, и в сбой «обновлено 5 минут назад» стояло над плашкой «сбой с
    // позавчера».
    if (millis <= 0) return "ещё не проверялось"
    val moment = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    val time = moment.format(DateTimeFormatter.ofPattern("HH:mm", RU))
    return when (moment.toLocalDate()) {
        LocalDate.now() -> "проверено в $time"
        LocalDate.now().minusDays(1) -> "проверено вчера в $time"
        else -> "проверено " + moment.format(DateTimeFormatter.ofPattern("d MMMM в HH:mm", RU))
    }
}

/**
 * «вс, 27 сентября» — день без «сегодня» и «завтра». Для уведомлений: они
 * висят и после полуночи, и утром «завтра, 28» — это уже сегодня.
 */
fun formatDayDate(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("EEE, d MMMM", RU))

/**
 * То же время, но коротко — для шапки виджета, где на счету каждый пиксель.
 * «19:12», «вчера 21:40», «5 сен».
 *
 * [nowMillis] виджеты передают из корня: вложенная шапка, читавшая часы
 * сама, пропускалась Compose и замирала — «вчера» не наступало до полуночи
 * колледжа.
 */
fun formatFetchedShort(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (millis <= 0) return "—"
    val zone = ZoneId.systemDefault()
    val moment = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
    val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).toLocalDate()
    val time = moment.format(DateTimeFormatter.ofPattern("HH:mm", RU))
    return when (moment.toLocalDate()) {
        today -> time
        today.minusDays(1) -> "вчера $time"
        else -> moment.format(DateTimeFormatter.ofPattern("d MMM", RU))
    }
}

/** Момент `now` в миллисекундах — для шапок виджетов, которые получают время колледжа. */
fun millisOf(now: LocalDateTime): Long = now.atZone(COLLEGE_ZONE).toInstant().toEpochMilli()

fun parseTime(value: String?): LocalTime? =
    value?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

/**
 * Номер пары, которая идёт в момент `now`, — по сетке звонков с сервера.
 * Пока сетка неизвестна, подсвечивать нечего.
 *
 * Момент передаётся снаружи, а не берётся из часов здесь. В виджетах это
 * зовётся из функций, которые Compose пропускает, пока не изменились их
 * параметры, — и часы внутри такой функции стоят: 14 сентября 2026 недельный
 * виджет пять часов подсвечивал кончившуюся пару. Время должно входить в
 * параметры, тогда пропускать нечего.
 */
fun currentLessonNumber(
    bells: Map<String, List<String>>,
    day: LocalDate,
    now: LocalDateTime,
): Int? {
    if (bells.isEmpty() || day != now.toLocalDate()) return null
    val time = now.toLocalTime()
    return bells.entries.firstNotNullOfOrNull { (number, range) ->
        val start = parseTime(range.getOrNull(0))
        val end = parseTime(range.getOrNull(1))
        if (start != null && end != null && time >= start && time <= end) {
            number.toIntOrNull()
        } else {
            null
        }
    }
}

/** Только начало пары: «09:00». Для виджета, где на диапазон нет ширины. */
fun lessonStart(bells: Map<String, List<String>>, number: Int): String? =
    bells[number.toString()]?.getOrNull(0)

fun lessonTime(bells: Map<String, List<String>>, number: Int): String? {
    val range = bells[number.toString()] ?: return null
    val start = range.getOrNull(0) ?: return null
    val end = range.getOrNull(1) ?: return start
    return "$start–$end"
}

/**
 * Расшифровка сокращений из таблицы колледжа.
 *
 * «Лек» и «Пр» понятны не всем и не сразу, а тип занятия — это первое, что
 * ищут глазами вместе с аудиторией. Незнакомое сокращение оставляем как есть:
 * колледж может завести новое, и лучше показать непонятное, чем ничего.
 */
fun kindName(kind: String?): String? = when (kind?.trim()?.lowercase()) {
    null, "" -> null
    "лек" -> "Лекция"
    "пр" -> "Практика"
    "лаб" -> "Лабораторная"
    "сем" -> "Семинар"
    "конс" -> "Консультация"
    "экз" -> "Экзамен"
    "зач" -> "Зачёт"
    "диф.зач" -> "Диф. зачёт"
    "курс.р." -> "Курсовая"
    else -> kind
}

/**
 * «онлайн» или «онлайн · 12»: у онлайн-пары бывает номер комнаты.
 *
 * С 14 сентября 2026 колледж раздаёт нумерованные онлайн-комнаты, как
 * кабинеты. Сервер кладёт номер в `r` при `o: 1`; здесь он не кабинет, и
 * «каб.» к нему не приписывается.
 */
fun onlineLabel(lesson: LessonDto): String =
    lesson.room?.trim()?.takeIf { it.isNotEmpty() }?.let { "онлайн · $it" } ?: "онлайн"

/**
 * Подпись аудитории.
 *
 * Голая «272» в строке читается как что угодно — номер пары, число студентов.
 * К номеру дописываем «каб.», а осмысленные названия вроде «Спортзал
 * Б.Хмельницкого 2» оставляем как есть.
 */
fun roomLabel(room: String?): String? {
    val text = room?.trim().orEmpty()
    if (text.isEmpty()) return null
    val looksLikeNumber = text.length <= 8 && text.all { it.isDigit() || it in "/-.абвгАБВГ" }
    return if (looksLikeNumber) "каб. $text" else text
}

/**
 * «Трухачев Даниил Дмитриевич» -> «Трухачев»: для строки, где место на исходе.
 *
 * Инициалы в виджете не помогают: однофамильцев в колледже нет, а «Старостина
 * Е. А.» обрывалась на «Старост…» — это хуже, чем короткая, но целая фамилия.
 */
fun surnameOnly(fullName: String): String =
    fullName.trim().split(" ").firstOrNull()?.takeIf { it.isNotEmpty() } ?: fullName

/** «Трухачев Даниил Дмитриевич» -> «Трухачев Д. Д.»: иначе не влезает в строку. */
fun shortenName(fullName: String): String {
    val parts = fullName.trim().split(" ").filter { it.isNotEmpty() }
    if (parts.size < 2) return fullName
    val initials = parts.drop(1).joinToString(" ") { "${it.first()}." }
    return "${parts.first()} $initials"
}

/** «3 ч 5 мин» — там, где место дорого: подписи кнопок и виджеты. */
fun formatDurationShort(minutes: Int): String = when {
    minutes < 60 -> "$minutes мин"
    minutes % 60 == 0 -> "${minutes / 60} ч"
    else -> "${minutes / 60} ч ${minutes % 60} мин"
}

/**
 * «20 минут», «час», «3 часа 5 минут» — там, где место есть: уведомления.
 *
 * Сокращения экономят место, которого в уведомлении не жалко, зато заставляют
 * человека делить в уме: «185 мин» — это сколько?
 */
fun formatDurationLong(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    val h = if (hours == 1) "час" else plural(hours, "час", "часа", "часов")
    // «минуту» без числа звучит естественно только само по себе: «через минуту».
    // Рядом с часами число нужно — «час 1 минуту».
    val m = if (hours == 0 && rest == 1) "минуту" else plural(rest, "минуту", "минуты", "минут")
    return when {
        hours == 0 -> m
        rest == 0 -> h
        else -> "$h $m"
    }
}

/** Русский счёт: 1 минута, 2 минуты, 5 минут, 11 минут. */
fun plural(n: Int, one: String, few: String, many: String): String {
    val word = when {
        n % 100 in 11..14 -> many
        n % 10 == 1 -> one
        n % 10 in 2..4 -> few
        else -> many
    }
    return "$n $word"
}

/**
 * «11 сентября, 11:00 — уже 2 дня»: с какого момента сервер лежит.
 *
 * Двое суток сбоя не должны выглядеть как минута: рядом с «сбой у нас»
 * стояло честное «обновлено 5 минут назад», и по нему выходило, что всё
 * свежее. Время сервера приходит в UTC, показываем по телефону.
 */
fun formatSince(iso: String, now: Instant = Instant.now()): String {
    val since = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    val local = LocalDateTime.ofInstant(since, ZoneId.systemDefault())
    val when_ = local.format(DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale("ru")))
    val minutes = Duration.between(since, now).toMinutes().coerceAtLeast(0)
    val ago = when {
        minutes < 60 -> plural(minutes.toInt().coerceAtLeast(1), "минуту", "минуты", "минут")
        minutes < 48 * 60 -> plural((minutes / 60).toInt(), "час", "часа", "часов")
        else -> plural((minutes / 60 / 24).toInt(), "день", "дня", "дней")
    }
    return "$when_ — уже $ago"
}

/**
 * Начало сбоя без «уже N часов» — для уведомления: оно выходит один раз и
 * висит сутками, и «уже 2 часа» через день было неправдой.
 */
fun formatSinceMoment(iso: String): String {
    val since = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    return LocalDateTime.ofInstant(since, ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale("ru")))
}

/** «6 пар», «2 пары», «1 пара» — счёт занятий по-русски. */
fun pairsCount(count: Int): String = plural(count, "пара", "пары", "пар")

/** «ещё 2 пары» — строка вместо тех занятий, что не поместились в виджет. */
/** «Ещё 2 пары» — о том, что не поместилось ниже списка, то есть впереди. */
fun morePairs(count: Int): String = "ещё " + plural(count, "пара", "пары", "пар")

/**
 * «Прошла 1 пара», «прошли 2 пары», «прошло 5 пар» — о том, что не
 * поместилось выше списка.
 *
 * Отдельная строка, а не общий счёт вместе с предстоящими: «ещё две пары» над
 * списком обещало бы пары впереди, а речь о тех, что уже кончились. Глагол
 * согласуется с числом, как и существительное: «прошло 1 пара» читалось
 * как ошибка — ею и было.
 */
fun passedPairs(count: Int): String {
    val verb = when {
        count % 100 in 11..14 -> "прошло"
        count % 10 == 1 -> "прошла"
        count % 10 in 2..4 -> "прошли"
        else -> "прошло"
    }
    return "$verb " + plural(count, "пара", "пары", "пар")
}
