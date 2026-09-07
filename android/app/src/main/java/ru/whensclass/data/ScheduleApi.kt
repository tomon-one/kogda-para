package ru.whensclass.data

import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.Cache
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
class ScheduleApi(cacheDir: java.io.File, private val baseUrl: String = BuildConfig.BASE_URL) {

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

    fun groups(): GroupsDto = get("/v1/groups").let(json::decodeFromString)

    fun schedule(groupId: String, days: Int = 7): ScheduleDto =
        get("/v1/schedule/$groupId?days=$days").let(json::decodeFromString)

    private fun get(path: String): String {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("сервер ответил ${response.code} на $path")
            }
            return body
        }
    }

    private companion object {
        const val CACHE_BYTES = 2L * 1024 * 1024
    }
}
