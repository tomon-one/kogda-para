package ru.whensclass.data

/**
 * Домены, на которых колледж проводит вебинары. Сняты с архива листов за
 * 13–23 сентября 2026: 430 тысяч ссылок на my.mts-link.ru и 578 на
 * us06web.zoom.us, других нет; webinar.ru — прежний домен МТС Линка.
 *
 * Ссылку в ячейку пишет любой, кто правит таблицу, и хост не из этого списка
 * приложение показывает как чужой адрес (второй аудит, М37). Не запрет: новая
 * площадка колледжа тоже окажется «чужой», пока её не допишут сюда.
 */
private val WEBINAR_DOMAINS = listOf("mts-link.ru", "webinar.ru", "zoom.us")

/** Ведёт ли ссылка на известную площадку вебинаров (домен или его поддомен, https). */
fun isKnownWebinar(url: String): Boolean {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase()?.removeSuffix(".") ?: return false
    return WEBINAR_DOMAINS.any { host == it || host.endsWith(".$it") }
}
