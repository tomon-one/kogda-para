package ru.whensclass.data

import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.whensclass.BuildConfig

/**
 * Обращения к серверу.
 *
 * Условные запросы не пишем руками: кэш OkHttp сам хранит ETag, добавляет
 * If-None-Match и разворачивает ответ 304 в тело из кэша. Поэтому обновление,
 * при котором расписание не изменилось, стоит нам почти ничего.
 */
/**
 * Сервер ответил кодом, а не расписанием.
 *
 * Отдельный класс, а не текст в IOException: 404 на группу — это «группы в
 * таблице больше нет», и приложение должно сказать человеку выбрать заново,
 * а не молча держать прежнее, как при отвалившейся сети.
 */
class HttpFailure(val code: Int, path: String) : IOException("сервер ответил $code на $path")

/** Файл обновления оказался больше объявленного — загрузка остановлена. */
class TooLarge(limit: Long) : IOException("файл больше объявленных $limit байт")

class ScheduleApi(
    cacheDir: java.io.File,
    private val baseUrl: String = BuildConfig.BASE_URL,
    private val githubApi: String = GITHUB_API,
    /** «main» — основное приложение, «tested» — сборка для проверки нового (build.gradle.kts). */
    private val channel: String = BuildConfig.CHANNEL,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .cache(Cache(cacheDir.resolve("http"), CACHE_BYTES))
        // Без явных сроков запрос в плохой сети висит минутами, а виджет всё
        // это время ждёт обновления, которого не будет.
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .readTimeout(java.time.Duration.ofSeconds(20))
        .callTimeout(java.time.Duration.ofSeconds(30))
        .build()

    fun meta(): MetaDto = get("/v1/meta").let(json::decodeFromString)

    fun release(): ReleaseDto =
        get(if (channel == MAIN) "/v1/app" else "/v1/app?channel=$channel").let(json::decodeFromString)

    /**
     * Самая новая сборка среди выпусков на GitHub; null — ни в одном нет файла.
     * У tested-сборки выпусков нет: на GitHub только основные, а их файл —
     * другое приложение.
     */
    fun githubRelease(): ReleaseDto? {
        if (channel != MAIN) return null
        val request = Request.Builder()
            .url(githubApi.trimEnd('/') + "/repos/$REPO/releases?per_page=10")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code, "выпуски GitHub")
            return newestOnGithub(json.decodeFromString<List<GithubRelease>>(response.body.string()))
        }
    }

    fun groups(): GroupsDto = get("/v1/groups").let(json::decodeFromString)

    fun schedule(groupId: String, from: java.time.LocalDate? = null, days: Int = 7):
        ScheduleDto = get(
        "/v1/schedule/$groupId?days=$days" + (from?.let { "&from=$it" } ?: ""),
    ).let(json::decodeFromString)

    fun teachers(): TeachersDto = get("/v1/teachers").let(json::decodeFromString)

    fun teacher(teacherId: String, from: java.time.LocalDate? = null, days: Int = 7):
        ScheduleDto = get(
        "/v1/teacher/$teacherId?days=$days" + (from?.let { "&from=$it" } ?: ""),
    ).let(json::decodeFromString)

    /**
     * Скачивает файл обновления в [target].
     *
     * Своими руками, а не системным загрузчиком: тот пишет в общую внешнюю
     * память, куда на Android 8-10 может залезть любое приложение с правом на
     * неё, не умеет сказать «файл не дошёл целиком» и представляется серверу
     * строкой с моделью телефона и версией прошивки. Нам нужно ровно обратное.
     *
     * Больше [limit] байт не пишем: размер раньше сверялся только после
     * загрузки, и бесконечный поток десять минут тянул трафик и забивал память.
     *
     * Возвращает число записанных байт.
     */
    fun downloadTo(url: String, target: java.io.File, limit: Long): Long {
        val request = Request.Builder().url(url).build()
        // Свой клиент без кэша и без общих сроков: два с половиной мегабайта на
        // слабой связи идут дольше, чем позволено обычному запросу.
        val downloader = client.newBuilder()
            .cache(null)
            .callTimeout(java.time.Duration.ofMinutes(10))
            .readTimeout(java.time.Duration.ofMinutes(2))
            .build()
        downloader.newCall(request).execute().use { response ->
            // HttpFailure, а не голый IOException: иначе «сервер занят» на 429
            // от предела nginx не срабатывал никогда.
            if (!response.isSuccessful) throw HttpFailure(response.code, "загрузку")
            val body = response.body
            if (body.contentLength() > limit) throw TooLarge(limit)
            target.outputStream().use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) throw TooLarge(limit)
                    out.write(buffer, 0, read)
                }
            }
        }
        return target.length()
    }

    /**
     * Наш ли это адрес: тот же протокол, хост и порт, что у сервера
     * расписания, или выпуски этого репозитория на GitHub. Файл обновления
     * качаем только оттуда — адрес из ответа /v1/app иначе уводил загрузку на
     * любой хост.
     */
    fun isOurs(url: String): Boolean {
        val target = url.toHttpUrlOrNull() ?: return false
        val base = baseUrl.toHttpUrlOrNull()
        // Только файл сборки, а не любой путь: иначе захваченная служба
        // отдала бы APK по адресу вроде /v1/что-угодно, а файлы в /download/
        // раздаёт nginx из каталога root.
        if (base != null && target.scheme == base.scheme && target.host == base.host && target.port == base.port) {
            return OUR_APK.matches(target.encodedPath)
        }
        return target.scheme == "https" && target.host == "github.com" && target.port == 443 &&
            GITHUB_APK.matches(target.encodedPath)
    }

    private fun get(path: String): String {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpFailure(response.code, path)
            }
            return body
        }
    }

    private companion object {
        const val CACHE_BYTES = 2L * 1024 * 1024
        const val GITHUB_API = "https://api.github.com"
        const val REPO = "tomon-one/kogda-para"
        const val MAIN = "main"
        // tested — файлы tested-сборок; чужой пакет всё равно отсечёт genuine().
        val OUR_APK = Regex("""/download/kogda-para-(tested-)?\d+\.apk""")
        val GITHUB_APK = Regex("""/$REPO/releases/download/[^/]+/kogda-para-\d+\.apk""")
    }
}
