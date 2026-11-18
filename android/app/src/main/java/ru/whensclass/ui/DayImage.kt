package ru.whensclass.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.IOException
import ru.whensclass.data.DayDto
import ru.whensclass.data.HttpFailure

/**
 * Отправить картинку дня в чат: системное «Поделиться» с файлом кадра и подписью дня.
 *
 * Файл лежит в кэше приложения, наружу отдаётся через FileProvider: путь другому приложению не
 * передать (res/xml/file_paths.xml).
 */
internal fun shareDayImage(context: Context, file: java.io.File, text: String? = null) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (!text.isNullOrBlank()) send.putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Картинка дня"))
}

/**
 * Подпись к картинке: первой строкой — во сколько начинаются и кончаются пары по звонкам дня,
 * дальше только пары со ссылкой. Диктор чата читает это, не разглядывая картинку.
 */
internal fun dayShareText(day: DayDto, bells: Map<String, List<String>>): String? {
    // Свои пары дня: чужие подгруппы и отменённые в подпись не идут.
    val own = day.lessons.filter { !it.isCancelled && (it.slots.isEmpty() || 0 in it.slots) }
    if (own.isEmpty()) return null
    val lines = mutableListOf<String>()
    val from = bells[own.minOf { it.number }.toString()]?.getOrNull(0)?.takeIf { it.isNotBlank() }
    val to = bells[own.maxOf { it.number }.toString()]?.getOrNull(1)?.takeIf { it.isNotBlank() }
    if (from != null && to != null) lines += "Пары с $from до $to"
    own.filter { !it.url.isNullOrBlank() }
        // Одна ссылка на один номер может повторяться у нескольких подгрупп.
        .distinctBy { it.number to it.url }
        .sortedBy { it.number }
        .forEach {
            // Название — как на картинке: короткое из словаря службы, без него полное.
            val subject = it.shortSubject?.replaceFirstChar { first -> first.uppercase() } ?: it.subject
            lines += "${it.number} Пара: $subject, ${it.url}"
        }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** Почему картинка не пришла — словами, а не кодом ответа. */
internal fun dayImageError(error: Throwable): String = when {
    error is HttpFailure && error.code == 404 -> "Картинка за этот день недоступна"
    error is HttpFailure -> "Сервер не смог собрать картинку. Попробуйте позже"
    error is IOException -> "Картинка не пришла: проверьте связь"
    else -> "Картинка не собралась. Попробуйте ещё раз"
}
