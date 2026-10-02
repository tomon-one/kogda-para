package ru.whensclass.data

import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.whensclass.BuildConfig

/**
 * Сервер ответил кодом, а не расписанием. Отдельный класс: 404 на группу —
 * это «группы в таблице больше нет», а не отвалившаяся сеть.
 */
class HttpFailure(val code: Int, path: String) : IOException("сервер ответил $code на $path")

/**
 * Номер сборки в основном счёте: у tested — номер основной, к которой она идёт
 * (versionCode × 100 + попытка, build.gradle.kts).
 */
internal fun appBuild(versionCode: Int, channel: String): Int =
    if (channel == "main") versionCode else versionCode / 100

/** Файл обновления оказался больше объявленного — загрузка остановлена. */
class TooLarge(limit: Long) : IOException("файл больше объявленных $limit байт")

/**
 * Обращения к серверу. Условные запросы делает кэш OkHttp сам (ETag,
 * If-None-Match, 304 из кэша), поэтому неизменное расписание почти ничего не
 * стоит.
 */
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
        // Без явных сроков запрос в плохой сети висит минутами.
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
     * Скачивает файл обновления в [target] и возвращает число записанных байт.
     *
     * Сами, а не системным загрузчиком: тот пишет в общую внешнюю память
     * (на Android 8–10 туда может залезть любое приложение с правом на неё), не
     * умеет сказать «файл не дошёл целиком» и сообщает серверу модель телефона.
     *
     * Больше [limit] байт не пишем: бесконечный поток иначе тянул бы трафик и
     * забивал память.
     */
    fun downloadTo(url: String, target: java.io.File, limit: Long): Long {
        val request = Request.Builder().url(url).build()
        // Свой клиент без кэша и с долгими сроками: сборка на слабой связи
        // идёт дольше, чем позволено обычному запросу.
        val downloader = client.newBuilder()
            .cache(null)
            .callTimeout(java.time.Duration.ofMinutes(10))
            .readTimeout(java.time.Duration.ofMinutes(2))
            .build()
        downloader.newCall(request).execute().use { response ->
            // HttpFailure, а не голый IOException: по коду 429 [updateFailure]
            // скажет «сервер занят».
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
     * Свой ли это адрес: тот же протокол, хост и порт, что у сервера
     * расписания, или выпуски этого репозитория на GitHub. Файл обновления
     * качаем только оттуда, а не с любого хоста из ответа /v1/app.
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
        // Номер сборки — чтобы сервер мог сказать «обновите», когда эта
        // перестанет его понимать (docs/api.md, README «Что уходит на сервер»).
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path)
            .header("X-App-Build", appBuild(BuildConfig.VERSION_CODE, channel).toString())
            .build()
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
