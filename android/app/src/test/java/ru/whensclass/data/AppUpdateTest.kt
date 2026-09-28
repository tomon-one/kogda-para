package ru.whensclass.data

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Загрузка обновления: что видит человек, когда не вышло, и чего телефон не
 * делает по одному слову сервера. До 26.09.2026 тестов на AppUpdate не было
 * вовсе, и мёртвую ветку «сервер занят» ничто не поймало.
 */
class AppUpdateTest {

    private lateinit var server: HttpServer
    private lateinit var dir: File
    private lateinit var api: ScheduleApi
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/busy") { exchange ->
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        server.createContext("/endless") { exchange ->
            // Тело без длины и без конца: только предел остановит загрузку.
            exchange.sendResponseHeaders(200, 0)
            val chunk = ByteArray(64 * 1024)
            runCatching { exchange.responseBody.use { out -> repeat(10_000) { out.write(chunk) } } }
            exchange.close()
        }
        server.createContext("/repos/tomon-one/kogda-para/releases") { exchange ->
            val body = """[
              {"tag_name":"pr-Зерно.0.1.3","draft":true,
               "assets":[{"name":"kogda-para-4.apk","size":3,"browser_download_url":"https://github.com/x/4"}]},
              {"tag_name":"pr-Зерно.0.1.2","draft":false,
               "assets":[{"name":"kogda-para-3.apk","size":2500000,
                          "browser_download_url":"https://github.com/tomon-one/kogda-para/releases/download/t/kogda-para-3.apk"}]},
              {"tag_name":"pr-Зерно.0.1.1","assets":[{"name":"kogda-para-2.apk","size":1,"browser_download_url":"u"}]}
            ]""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/apk") { exchange ->
            val body = ByteArray(1000)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        dir = Files.createTempDirectory("whensclass-update").toFile()
        api = ScheduleApi(dir, base, githubApi = base)
    }

    @After
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    @Test
    fun `429 на загрузке — «сервер занят», а не сырой текст`() {
        val error = runCatching { api.downloadTo("$base/busy", File(dir, "a.part"), 1000) }.exceptionOrNull()
        assertTrue("ждали HttpFailure, пришло $error", error is HttpFailure)
        assertEquals("сервер занят, попробуйте через минуту", updateFailure(error!!))
    }

    @Test
    fun `бесконечный поток останавливается на объявленном размере`() {
        val target = File(dir, "b.part")
        try {
            api.downloadTo("$base/endless", target, 100_000)
            fail("поток без конца скачался целиком")
        } catch (error: TooLarge) {
            assertEquals("файл больше объявленного — загрузка остановлена", updateFailure(error))
        }
        assertTrue("записано ${target.length()}", target.length() <= 100_000)
    }

    @Test
    fun `файл в пределах скачивается`() {
        assertEquals(1000L, api.downloadTo("$base/apk", File(dir, "c.part"), 1000))
    }

    @Test
    fun `качаем только со своего сервера`() {
        assertTrue(api.isOurs("$base/download/kogda-para-2.apk"))
        assertFalse(api.isOurs("https://evil.example/download/kogda-para-2.apk"))
        assertFalse(api.isOurs("http://127.0.0.1:1/download/kogda-para-2.apk"))
        assertFalse(api.isOurs("не адрес"))
        assertTrue(api.isOurs("https://github.com/tomon-one/kogda-para/releases/download/t/kogda-para-2.apk"))
        assertFalse(api.isOurs("https://github.com/someone/else/releases/download/t/kogda-para-2.apk"))
        assertFalse(api.isOurs("http://github.com/tomon-one/kogda-para/releases/download/t/kogda-para-2.apk"))
        // Только файл сборки: не любой путь на своём сервере и в выпусках.
        assertFalse(api.isOurs("$base/v1/schedule/kogda-para-2.apk"))
        assertFalse(api.isOurs("$base/download/other.apk"))
        assertTrue(api.isOurs("$base/download/kogda-para-tested-501.apk"))
        assertFalse(api.isOurs("$base/download/kogda-para-tested.apk"))
        assertFalse(api.isOurs("https://github.com/tomon-one/kogda-para/releases/download/t/x/kogda-para-2.apk"))
    }

    @Test
    fun `сборки прежнего счёта не ставятся, даже подписанные тем же ключом`() {
        for (old in listOf("0.2", "0.26", "b-Тень.0.3.1", "b-Сон.0.9.12", "pr-Зерно.0.10.0", "pr-Зерно.0.10.3", "pr-Зерно.0.10.0-t1", null)) {
            assertTrue(old.toString(), olderNumbering(old))
        }
        for (new in listOf("pr-Зерно.0.1.0", "pr-Росток.0.2.3", "pr-Плод.0.9.12", "r-Алтай.1.0.0", "r-Байкал.1.1.0", "pr-Зерно.0.1.4-t2")) {
            assertFalse(new, olderNumbering(new))
        }
    }

    @Test
    fun `tested-сборка не ищет обновлений на GitHub`() {
        // Там только основные сборки — для tested это другое приложение.
        assertNull(ScheduleApi(dir, base, githubApi = base, channel = "tested").githubRelease())
    }

    @Test
    fun `сервер молчит — сборка берётся из выпусков GitHub, черновики не в счёт`() {
        val release = api.githubRelease()!!
        assertEquals(3, release.versionCode)
        assertEquals("pr-Зерно.0.1.2", release.versionName)
        assertEquals(2_500_000L, release.size)
        assertTrue(api.isOurs(release.url))
    }

    @Test
    fun `выпуск без файла сборки не в счёт`() {
        val noApk = GithubRelease("pr-Зерно.0.1.5", assets = listOf(GithubAsset("notes.txt", 10, "u")))
        val apk = GithubRelease("pr-Зерно.0.1.4", assets = listOf(GithubAsset("kogda-para-4.apk", 10, "u")))
        assertEquals(4, newestOnGithub(listOf(noApk, apk))?.versionCode)
        assertEquals(null, newestOnGithub(listOf(noApk)))
    }

    @Test
    fun `причины отказа — словами человека`() {
        assertEquals(
            "на телефоне не хватает места",
            updateFailure(java.io.IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals("сервер не ответил вовремя", updateFailure(java.io.InterruptedIOException("timeout")))
        assertEquals("сервер не ответил вовремя", updateFailure(java.net.SocketTimeoutException()))
        assertEquals("нет связи с сервером", updateFailure(java.net.UnknownHostException("x")))
        assertTrue(
            updateFailure(
                javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found."),
            ).startsWith("эта сеть не пускает к серверу"),
        )
        assertEquals("связь оборвалась, попробуйте ещё раз", updateFailure(java.io.IOException("unexpected end of stream")))
        assertEquals("сервер ответил 404", updateFailure(HttpFailure(404, "загрузку")))
    }

    @Test
    fun `объявленный номер с потолка не глушит настоящие сборки`() {
        // Один ответ с 2147483647 раньше навсегда выключал объявления.
        assertTrue(shouldAnnounce(release = 82, installed = 81, announced = Int.MAX_VALUE))
        assertFalse(shouldAnnounce(release = 82, installed = 81, announced = 82))
        assertFalse(shouldAnnounce(release = 81, installed = 81, announced = 0))
    }

    @Test
    fun `подпись с ротацией ключа принимается, чужая — нет`() {
        // Установлена сборка со старым ключом; новая подписана новым с
        // историей [старый, новый] — Android её поставит, и мы тоже.
        assertTrue(signedAlike(installedCurrent = setOf("старый"), archiveAll = setOf("старый", "новый")))
        assertTrue(signedAlike(setOf("старый"), setOf("старый")))
        assertFalse(signedAlike(setOf("старый"), setOf("чужой")))
        assertFalse(signedAlike(emptySet(), setOf("старый")))
    }
}
