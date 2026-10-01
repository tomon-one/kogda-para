"""Подписка на уведомления сайта: что прислал браузер и можно ли этому верить.

Адрес подписки приходит от браузера, то есть от кого угодно, — здесь его
проверяют, прежде чем служба пошлёт на него POST.
"""

from __future__ import annotations

import urllib.parse

from . import webpush


# Службы рассылки браузеров. Адрес подписки приходит от браузера, то есть от
# кого угодно: без списка служба слала бы POST на любой адрес, который ей
# назовут, — в том числе во внутреннюю сеть машины.
PUSH_HOSTS = (
    "fcm.googleapis.com",                   # Chrome, Яндекс Браузер, Samsung
    "updates.push.services.mozilla.com",    # Firefox
    "web.push.apple.com",                   # Safari, сайт со значком на айфоне
)
PUSH_SUFFIXES = (".notify.windows.com", ".push.apple.com")

MAX_ENDPOINT = 1024
# Как «За сколько предупредить» в приложении: от 10 минут до 4 часов.
REMIND_MIN, REMIND_MAX = 10, 240


def endpoint_allowed(endpoint: str) -> bool:
    if not isinstance(endpoint, str) or len(endpoint) > MAX_ENDPOINT:
        return False
    # Только печатный ASCII без пробелов: urlsplit молча выкидывает \t и \n, а
    # httpx на таком адресе бросал InvalidURL мимо журнала.
    if any(not 32 < ord(c) < 127 for c in endpoint):
        return False
    try:
        parts = urllib.parse.urlsplit(endpoint)
        port = parts.port
    except ValueError:
        return False
    host = (parts.hostname or "").lower()
    if parts.scheme != "https" or port not in (None, 443) or parts.username or parts.password:
        return False
    return host in PUSH_HOSTS or any(host.endswith(s) for s in PUSH_SUFFIXES)


class BadSubscription(ValueError):
    pass


def parse_subscription(body: object) -> dict:
    """Проверить тело POST /v1/push/subscribe и вернуть запись для хранения."""
    if not isinstance(body, dict):
        raise BadSubscription("ждали объект JSON")
    endpoint = body.get("endpoint")
    if not endpoint_allowed(endpoint):
        raise BadSubscription("адрес подписки не от службы рассылки браузера")
    keys = body.get("keys") if isinstance(body.get("keys"), dict) else {}
    try:
        p256dh = webpush.b64decode(str(keys.get("p256dh", "")))
        auth = webpush.b64decode(str(keys.get("auth", "")))
    except ValueError as exc:
        raise BadSubscription("ключи подписки — не base64url") from exc
    if len(p256dh) != 65 or p256dh[0] != 4 or len(auth) != 16:
        raise BadSubscription("ключи подписки не той длины")
    if not webpush.valid_key(p256dh):
        raise BadSubscription("ключ подписки — не точка кривой P-256")
    kind = body.get("kind")
    subject = body.get("id")
    if kind not in ("group", "teacher") or not isinstance(subject, str) or not subject \
            or len(subject) > 200:
        raise BadSubscription("чьё расписание — kind group|teacher и id")
    remind = body.get("remind", 0)
    if isinstance(remind, bool) or not isinstance(remind, int) \
            or not (remind == 0 or REMIND_MIN <= remind <= REMIND_MAX):
        raise BadSubscription(f"remind — 0 или от {REMIND_MIN} до {REMIND_MAX} минут")
    changes_on = body.get("changes", False)
    if not isinstance(changes_on, bool):
        raise BadSubscription("changes — true или false")
    # Какой сайт подписан — туда и ведёт нажатие на уведомление.
    site = body.get("site", "main")
    if site not in ("main", "tested"):
        raise BadSubscription("site — main или tested")
    return {
        "endpoint": endpoint,
        "p256dh": webpush.b64encode(p256dh),
        "auth": webpush.b64encode(auth),
        "kind": kind,
        "id": subject,
        "changes": changes_on,
        "remind": remind,
        "site": site,
    }


def parse_keys(body: object) -> dict:
    """Новый адрес и ключи — для переноса подписки (POST /v1/push/move)."""
    if not isinstance(body, dict) or not isinstance(body.get("old"), str):
        raise BadSubscription("нужны old, endpoint и keys")
    full = parse_subscription({**body, "kind": "group", "id": "-"})
    return {"endpoint": full["endpoint"], "p256dh": full["p256dh"], "auth": full["auth"]}


def _host(endpoint: str) -> str:
    """Только хост: путь адреса подписки и есть её секрет."""
    return urllib.parse.urlsplit(endpoint).hostname or "?"
