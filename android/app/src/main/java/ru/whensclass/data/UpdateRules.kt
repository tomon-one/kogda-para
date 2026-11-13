package ru.whensclass.data

import android.content.pm.PackageInstaller
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Правила обновления без Android: какая сборка новее, объявлять ли её, тексты отказов.
// Их проверяет AppUpdateTest.

/** Что лежит на сервере — приложение раздаётся файлом, а не через магазин. */
@Serializable
data class ReleaseDto(
    @SerialName("versionCode") val versionCode: Int,
    @SerialName("versionName") val versionName: String,
    @SerialName("url") val url: String,
    @SerialName("size") val size: Long = 0,
    @SerialName("notes") val notes: String = "",
)

/** Выпуск на GitHub — запасной источник, когда сервер приложения не отвечает. */
@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("assets") val assets: List<GithubAsset> = emptyList(),
)

@Serializable
internal data class GithubAsset(
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
)

private val APK_NAME = Regex("""kogda-para-(\d+)\.apk""")

/**
 * Самая новая сборка среди выпусков на GitHub. Номер — из имени файла (`kogda-para-2.apk`), версия
 * — из тега; выпуск без такого файла не в счёт.
 */
internal fun newestOnGithub(releases: List<GithubRelease>): ReleaseDto? = releases
    .filterNot { it.draft }
    .mapNotNull { release ->
        release.assets.firstNotNullOfOrNull { asset ->
            APK_NAME.matchEntire(asset.name)?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
                ReleaseDto(versionCode = code, versionName = release.tag, url = asset.url, size = asset.size)
            }
        }
    }
    .maxByOrNull { it.versionCode }

// «-t2» — tested-сборка (build.gradle.kts): счёт тот же, прежним он не считается.
private val VERSION_NAME = Regex("""(b|pr|r)-\p{L}+\.(\d+)\.(\d+)\.(\d+)(?:-t\d+)?""")

/**
 * Сборка прежнего счёта. До 27.09.2026 номера дошли до 86-го, а имена были «0.x», «b-…» и
 * «pr-Зерно.0.10.x»; потом счёт начат заново. Прежние сборки подписаны тем же ключом, и Android
 * поставил бы такую поверх — обновления встали бы навсегда. Отсекаем по имени версии: оно зашито в
 * подписанный файл.
 */
internal fun olderNumbering(versionName: String?): Boolean {
    val match = VERSION_NAME.matchEntire(versionName ?: return true) ?: return true
    val (prefix, major, minor) = match.destructured
    return major.toInt() == 0 && (prefix == "b" || minor.toInt() >= 10)
}

/**
 * Объявлять ли сборку. Про одну и ту же — раз; «уже объявлено» сверяется на равенство, не «не
 * меньше»: номер с сервера приходит без проверки, и огромный versionCode заглушил бы объявления
 * навсегда.
 */
internal fun shouldAnnounce(release: Int, installed: Int, announced: Int): Boolean =
    release > installed && release != announced &&
        // Уже объявлена сборка новее (GitHub отстаёт от сервера): о старшей не напоминать — только
        // для правдоподобного номера.
        announced !in (release + 1)..(installed + PLAUSIBLE_JUMP)

/** На сколько сборок вперёд номер с сервера ещё правдоподобен. */
private const val PLAUSIBLE_JUMP = 100

/**
 * Годится ли подпись скачанного файла: нынешний сертификат установленного приложения должен быть
 * среди сертификатов файла — так судит Android, так проходит ротация ключа (история [старый,
 * новый]). Подделать историю без ключа нельзя.
 */
internal fun signedAlike(installedCurrent: Set<String>, archiveAll: Set<String>): Boolean =
    installedCurrent.isNotEmpty() && archiveAll.containsAll(installedCurrent)

/** Почему загрузка не удалась — словами человека, а не сырым английским текстом Java вроде «write failed: ENOSPC». */
internal fun updateFailure(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        // 429 — предел скачиваний с одного адреса в nginx; за общим адресом оператора или Wi-Fi
        // колледжа в день раздачи он реален.
        error is HttpFailure && (error.code == 429 || error.code == 503) ->
            "сервер занят, попробуйте через минуту"
        error is HttpFailure -> "сервер ответил ${error.code}"
        error is TooLarge -> "файл больше объявленного — загрузка остановлена"
        chain.any { it.message.orEmpty().let { m -> "ENOSPC" in m || "No space left" in m } } ->
            "на телефоне не хватает места"
        error is java.net.UnknownHostException -> "нет связи с сервером"
        // SocketTimeoutException — частный случай; общий срок загрузки OkHttp бросает голым
        // InterruptedIOException("timeout").
        error is java.io.InterruptedIOException -> "сервер не ответил вовремя"
        chain.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException } ->
            // «Сертификат» новичку ничего не говорит.
            "эта сеть не пускает к серверу — возможно, Wi-Fi ждёт входа на своей странице. " +
                "Попробуйте через мобильную сеть"
        error is java.io.IOException -> "связь оборвалась, попробуйте ещё раз"
        // Показывается как «Не удалось: …» — не «не удалось скачать» второй раз.
        else -> "причина неизвестна"
    }
}

/**
 * Почему установщик Android не поставил сборку — словами человека. Статусы —
 * [PackageInstaller.STATUS_FAILURE] и соседние.
 */
internal fun installFailure(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_ABORTED -> "установку отменили"
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "телефон не дал поставить — проверьте защиту от неизвестных приложений"
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "файл не встаёт поверх установленного: другая подпись или сборка старее"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "сборка не подходит этому телефону"
    PackageInstaller.STATUS_FAILURE_INVALID -> "файл повреждён — попробуйте ещё раз"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "на телефоне не хватает места"
    else -> "установщик Android отказал"
}
