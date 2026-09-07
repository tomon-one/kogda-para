package ru.whensclass.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.whensclass.BuildConfig

/** Что лежит на сервере — приложение раздаётся файлом, а не через магазин. */
@Serializable
data class ReleaseDto(
    @SerialName("versionCode") val versionCode: Int,
    @SerialName("versionName") val versionName: String,
    @SerialName("url") val url: String,
    @SerialName("size") val size: Long = 0,
    @SerialName("notes") val notes: String = "",
)

/**
 * Проверка и установка обновлений.
 *
 * Полностью автоматически поставить обновление нельзя: Android всегда
 * спрашивает у человека подтверждение на установку файла. Поэтому здесь
 * автоматическое — только проверка и загрузка, а последний шаг открывает
 * системный установщик.
 */
class AppUpdate(private val context: Context, private val api: ScheduleApi) {

    /** Новая сборка, если она есть и она новее установленной. */
    suspend fun check(): ReleaseDto? = withContext(Dispatchers.IO) {
        val release = runCatching { api.release() }.getOrNull() ?: return@withContext null
        if (release.versionCode > BuildConfig.VERSION_CODE) release else null
    }

    /** Скачивает файл и открывает установщик, когда загрузка закончится. */
    fun download(release: ReleaseDto) {
        val name = "kogda-para-${release.versionName}.apk"
        val saved = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            name,
        )
        if (saved.exists()) saved.delete()

        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle("Когда пара? ${release.versionName}")
            .setDescription("Загрузка обновления")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, name,
            )
        context.getSystemService(DownloadManager::class.java)?.enqueue(request)
    }

    /** Открывает системный установщик для уже скачанного файла. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.files", file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Уже скачанный файл этой версии, если он есть. */
    fun downloaded(release: ReleaseDto): File? {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "kogda-para-${release.versionName}.apk",
        )
        return file.takeIf { it.exists() && it.length() > 0 }
    }
}
