package ru.whensclass.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private companion object {
        const val POLL_MS = 400L
        const val DOWNLOAD_TIMEOUT_MS = 120_000L
    }

    /** Новая сборка, если она есть и она новее установленной. */
    suspend fun check(): ReleaseDto? = withContext(Dispatchers.IO) {
        val release = runCatching { api.release() }.getOrNull() ?: return@withContext null
        if (release.versionCode > BuildConfig.VERSION_CODE) release else null
    }

    /**
     * Скачивает обновление и открывает установщик, когда файл готов.
     *
     * Раньше загрузка и установка были двумя нажатиями: первое ставило файл в
     * очередь, второе — открывало установщик. Со стороны это выглядело как
     * кнопка, срабатывающая через раз.
     */
    suspend fun downloadAndInstall(release: ReleaseDto): Boolean {
        downloaded(release)?.let {
            install(it)
            return true
        }

        val id = download(release)
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false

        // Ждём окончания загрузки, поглядывая на её состояние: файл небольшой,
        // но на плохой связи это может занять с минуту.
        withContext(Dispatchers.IO) {
            var waited = 0L
            while (waited < DOWNLOAD_TIMEOUT_MS) {
                val status = manager.query(DownloadManager.Query().setFilterById(id)).use { row ->
                    if (!row.moveToFirst()) DownloadManager.STATUS_FAILED
                    else row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL ||
                    status == DownloadManager.STATUS_FAILED
                ) {
                    return@withContext
                }
                delay(POLL_MS)
                waited += POLL_MS
            }
        }

        val file = downloaded(release) ?: return false
        install(file)
        return true
    }

    /** Ставит файл в очередь загрузки и возвращает её номер. */
    private fun download(release: ReleaseDto): Long {
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
        return context.getSystemService(DownloadManager::class.java)?.enqueue(request) ?: -1
    }

    /** Открывает системный установщик для уже скачанного файла. */
    private fun install(file: File) {
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
