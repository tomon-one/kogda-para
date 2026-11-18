package ru.whensclass.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.IOException
import ru.whensclass.data.HttpFailure

/**
 * Отправить картинку дня в чат: системное «Поделиться» с файлом кадра.
 *
 * Файл лежит в кэше приложения, наружу отдаётся через FileProvider: путь другому приложению не
 * передать (res/xml/file_paths.xml).
 */
internal fun shareDayImage(context: Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Картинка дня"))
}

/** Почему картинка не пришла — словами, а не кодом ответа. */
internal fun dayImageError(error: Throwable): String = when {
    error is HttpFailure && error.code == 404 -> "Такого дня в таблице нет"
    error is HttpFailure -> "Сервер не смог собрать картинку. Попробуйте позже"
    error is IOException -> "Картинка не пришла: проверьте связь"
    else -> "Картинка не собралась. Попробуйте ещё раз"
}
