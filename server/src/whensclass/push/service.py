"""Уведомления сайта: подписки, изменения после обновления, напоминания о паре.

Приложение считает всё это само, на телефоне; сайт так не может — браузер не
будит страницу по расписанию. Поэтому для сайта то же считает служба и шлёт
через службу рассылки браузера (Web Push). Правила — как в приложении
(changes.py).

Что хранится: адрес подписки браузера и два его ключа, чьё расписание (группа
или преподаватель), что присылать и дата подписки. Больше ничего — ни адреса
человека, ни браузера. Выключил уведомления — запись стёрта; служба рассылки
ответила, что подписки нет, — тоже.
"""

from __future__ import annotations

import concurrent.futures
import datetime as dt
import json
import logging
import pathlib
import threading
import time
import urllib.parse
import zoneinfo
from typing import NamedTuple

import httpx

from ..config import settings
from ..storage.atomic import write_json
from . import changes, webpush

log = logging.getLogger(__name__)

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
# Потолок подписок: запись в файл целиком, и без потолка его можно раздуть.
MAX_SUBSCRIPTIONS = 20000
# Как «За сколько предупредить» в приложении: от 10 минут до 4 часов.
REMIND_MIN, REMIND_MAX = 10, 240
# Изменения про сегодня и завтра: через сутки новость уже не нужна.
CHANGES_TTL = 12 * 3600
# Сообщение целиком — до 4 КБ зашифрованным (Apple, RFC 8291: 3993 байта
# открытого текста); с запасом.
MAX_PAYLOAD = 3000
MAX_LINE = 300
# Одна повторная попытка на 429 и 5xx: через Retry-After, но не дольше двух минут.
RETRY_AFTER = 30
RETRY_MAX = 120


class Job(NamedTuple):
    sub: dict
    message: dict
    ttl: int
    # Позже этого (секунды эпохи) повторять незачем: пара уже началась.
    deadline: float | None = None


def endpoint_allowed(endpoint: str) -> bool:
    if not isinstance(endpoint, str) or len(endpoint) > MAX_ENDPOINT:
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


class Push:
    """Подписки на диске и рассылка. Один на службу."""

    def __init__(self, state_dir: pathlib.Path, vapid: webpush.Vapid | None):
        self.path = state_dir / "push.json"
        self.vapid = vapid
        self._lock = threading.RLock()
        self._subs: dict[str, dict] = self._load()
        self._pool = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix="push")
        self._client = httpx.Client(timeout=10.0, headers={"User-Agent": settings.user_agent})
        # Какие напоминания уже ушли: (адрес, день, номер пары). Перезапуск
        # посреди той же минуты мог бы повторить одно — это меньшее зло, чем
        # писать на диск каждую минуту.
        self._reminded: set[tuple[str, str, int]] = set()
        self._reminded_day: str | None = None
        # Отложить повтор: в тестах подменяется.
        self._later = _timer

    @property
    def enabled(self) -> bool:
        return self.vapid is not None

    def _load(self) -> dict[str, dict]:
        try:
            data = json.loads(self.path.read_text("utf-8"))
        except (OSError, ValueError):
            return {}
        subs = data.get("subs", []) if isinstance(data, dict) else []
        return {s["endpoint"]: s for s in subs if isinstance(s, dict) and "endpoint" in s}

    def _save(self) -> None:
        try:
            write_json(self.path, {"subs": list(self._subs.values())})
        except OSError as exc:
            log.error("подписки уведомлений не записались: %s", exc)

    def subscribe(self, sub: dict, today: dt.date) -> bool:
        """Записать или заменить подписку. False — мест нет.

        Новый адрес — сразу «Уведомления включены»: человек видит, что они
        доходят, а не ждёт первой отмены пары (Tomon 28.09: проверить на
        телефоне).
        """
        with self._lock:
            if not sub["changes"] and not sub["remind"]:
                self.unsubscribe(sub["endpoint"])
                return True
            new = sub["endpoint"] not in self._subs
            if new and len(self._subs) >= MAX_SUBSCRIPTIONS:
                return False
            self._subs[sub["endpoint"]] = {**sub, "since": today.isoformat()}
            self._save()
        # В журнал — служба рассылки и выбор, без адреса и группы: по ним
        # видно, откуда взялось лишнее «Уведомления включены» (Tomon 28.09).
        log.info("%s подписка на уведомления: %s, изменения %s, напоминание %s, всего %d",
                 "новая" if new else "обновлена", _host(sub["endpoint"]),
                 "да" if sub["changes"] else "нет", sub["remind"] or "нет", len(self._subs))
        if new:
            self._dispatch([Job(sub, message(sub, "hello", "Уведомления включены",
                                             welcome_text(sub)), 3600)])
        return True

    def move(self, old: str, fresh: dict) -> bool:
        """Браузер сменил подписку сам (pushsubscriptionchange): прежний выбор —
        на новый адрес. Знать прежний адрес — и есть право: он секретен."""
        with self._lock:
            record = self._subs.pop(old, None)
            if record is None:
                return False
            self._subs[fresh["endpoint"]] = {**record, **fresh}
            self._save()
        log.info("подписка на уведомления перенесена браузером: %s -> %s",
                 _host(old), _host(fresh["endpoint"]))
        return True

    def unsubscribe(self, endpoint: str, why: str = "выключили") -> None:
        with self._lock:
            if self._subs.pop(endpoint, None) is not None:
                self._save()
                log.info("подписка на уведомления снята (%s): %s, осталось %d",
                         why, _host(endpoint), len(self._subs))

    def count(self) -> int:
        return len(self._subs)

    def _snapshot_subs(self) -> list[dict]:
        with self._lock:
            return list(self._subs.values())

    # --- рассылка ------------------------------------------------------------

    def _send(self, job: Job, attempt: int = 0) -> None:
        if self.vapid is None:
            return
        sub = job.sub
        kind = job.message["notification"]["data"]["t"]
        try:
            result = webpush.send(
                self._client, self.vapid, sub["endpoint"], sub["p256dh"], sub["auth"],
                job.message, job.ttl, urgency="high" if kind == "lesson" else "normal",
            )
        except (httpx.HTTPError, ValueError) as exc:
            # Адрес — только хост: путь подписки и есть её секрет.
            log.warning("уведомление не ушло (%s): %s", _host(sub["endpoint"]), type(exc).__name__)
            self._retry(job, attempt, None)
            return
        if result.status < 300:
            return
        said = f"{result.status}" + (f" {result.reason}" if result.reason else "")
        if result.status in webpush.GONE or result.reason == "VapidPkHashMismatch":
            # Подписки нет или она под другим ключом — слать дальше незачем;
            # страница при открытии подпишется заново.
            self.unsubscribe(sub["endpoint"], why=f"служба рассылки ответила {said}")
            return
        log.warning("служба рассылки %s ответила %s (%s)", _host(sub["endpoint"]), said, kind)
        if result.status in webpush.RETRY:
            self._retry(job, attempt, result.retry_after)

    def _retry(self, job: Job, attempt: int, after: int | None) -> None:
        """Одна повторная попытка; напоминание — только пока пара не началась."""
        if attempt > 0:
            return
        delay = min(max(after or RETRY_AFTER, 1), RETRY_MAX)
        if job.deadline is not None and time.time() + delay >= job.deadline:
            return
        again = job._replace(ttl=max(60, job.ttl - delay))
        self._later(delay, lambda: self._pool.submit(self._send, again, attempt + 1))

    def _dispatch(self, jobs: list[Job]) -> None:
        for job in jobs:
            self._pool.submit(self._send, job)

    # --- изменения -----------------------------------------------------------

    def after_refresh(self, before, before_teachers, snapshot, teachers, today: dt.date) -> None:
        """Снимок обновился — сравнить сегодня и завтра у каждого, кто подписан."""
        if not self.enabled or before is None:
            return
        subs = [s for s in self._snapshot_subs() if s.get("changes")]
        if not subs:
            return
        lines_for: dict[tuple[str, str], list[list[str]]] = {}
        for key in {(s["kind"], s["id"]) for s in subs}:
            old = _payload(before, before_teachers, key, today)
            new = _payload(snapshot, teachers, key, today)
            if old is None or new is None:
                continue
            lines = changes.change_lines(old, new, today)
            if lines:
                lines_for[key] = lines
        if not lines_for:
            return
        jobs = []
        for sub in subs:
            lines = lines_for.get((sub["kind"], sub["id"]))
            if lines:
                jobs.append(Job(sub, changes_message(sub, lines), CHANGES_TTL))
        log.info("изменения в расписании: %d субъектов, %d уведомлений", len(lines_for), len(jobs))
        self._dispatch(jobs)

    # --- напоминания ---------------------------------------------------------

    def remind(self, snapshot, teachers, now: dt.datetime | None = None) -> None:
        """Раз в минуту: кому пора напомнить о паре."""
        if not self.enabled or snapshot is None:
            return
        zone = zoneinfo.ZoneInfo(settings.timezone)
        now = (now or dt.datetime.now(zone)).replace(tzinfo=None, second=0, microsecond=0)
        today = now.date()
        if self._reminded_day != today.isoformat():
            self._reminded = set()
            self._reminded_day = today.isoformat()
        subs = [s for s in self._snapshot_subs() if s.get("remind")]
        plans: dict[tuple[str, str, int], list[dict]] = {}
        jobs = []
        for sub in subs:
            key = (sub["kind"], sub["id"], sub["remind"])
            if key not in plans:
                payload = _payload(snapshot, teachers, key[:2], today)
                plans[key] = changes.reminders(payload, key[2], today) if payload else []
            for alarm in plans[key]:
                if alarm["at"] != now:
                    continue
                mark = (sub["endpoint"], today.isoformat(), alarm["number"])
                if mark in self._reminded:
                    continue
                self._reminded.add(mark)
                start = alarm["start"].replace(tzinfo=zone).timestamp()
                end = alarm["end"].replace(tzinfo=zone).timestamp()
                jobs.append(Job(
                    sub,
                    message(sub, "lesson", alarm["title"], alarm["text"], {
                        "subject": alarm["subject"],
                        "start": int(start * 1000),
                        "end": int(end * 1000),
                    }),
                    max(60, int((alarm["start"] - now).total_seconds())),
                    deadline=start,
                ))
        if jobs:
            log.info("напоминания о паре: %d", len(jobs))
            self._dispatch(jobs)


def _timer(delay: float, action) -> None:
    timer = threading.Timer(delay, action)
    timer.daemon = True
    timer.start()


def message(sub: dict, kind: str, title: str, body: str, data: dict | None = None) -> dict:
    """Сообщение в декларативном формате Apple (iOS 18.4+, поиск 28.09):
    сервис-воркер показывает его сам, а не проснулся или упал — айфон покажет
    это же и не отзовёт подписку за «невидимое» уведомление. Chrome и Firefox
    получают тот же JSON в сервис-воркер как обычные данные.

    tag на айфоне уведомления не заменяет (WebKit 258922) — склеивает строки и
    закрывает прежнее сервис-воркер.
    """
    return {
        "web_push": 8030,
        "notification": {
            "title": title,
            "body": body,
            "navigate": site_url(sub),
            "tag": kind,
            "data": {"t": kind, **(data or {})},
        },
        "mutable": True,
    }


def changes_message(sub: dict, lines: list[list[str]]) -> dict:
    """Изменения: строки — текстом, их дни — рядом, чтобы сервис-воркер склеил
    с висящим. Не влезает в MAX_PAYLOAD — старые строки прочь, как в шторке."""
    kept = [[day, text[:MAX_LINE]] for day, text in lines]
    while True:
        out = message(sub, "changes", "Расписание изменилось", "\n".join(t for _, t in kept),
                      {"days": [d for d, _ in kept]})
        if len(kept) == 1 or len(json.dumps(out, ensure_ascii=False).encode("utf-8")) <= MAX_PAYLOAD:
            return out
        kept = kept[1:]


def site_url(sub: dict) -> str:
    return f"https://{settings.domain}/" + ("tested/" if sub.get("site") == "tested" else "")


def _host(endpoint: str) -> str:
    """Только хост: путь адреса подписки и есть её секрет."""
    return urllib.parse.urlsplit(endpoint).hostname or "?"


def welcome_text(sub: dict) -> str:
    what = []
    if sub["changes"]:
        what.append("отмены и замены на сегодня и завтра")
    if sub["remind"]:
        what.append("напоминания о паре")
    return "Сюда будут приходить " + " и ".join(what) + "."


def _payload(snapshot, teachers, key: tuple[str, str], today: dt.date) -> dict | None:
    """Сегодня и завтра этого субъекта — как их отдал бы API."""
    from ..api.payloads import schedule_payload, teacher_payload
    from ..service.bells import BELLS

    kind, subject = key
    generated = dt.datetime.now(dt.timezone.utc)
    if kind == "group":
        return schedule_payload(snapshot, subject, today, 2, generated, bells=BELLS, today=today)
    if teachers is None:
        return None
    return teacher_payload(snapshot, teachers, subject, today, 2, generated, bells=BELLS, today=today)


def load_vapid() -> webpush.Vapid | None:
    """Ключ из окружения; нет или битый — уведомлений сайта нет, служба живёт."""
    if not settings.vapid_key:
        return None
    try:
        return webpush.Vapid(settings.vapid_key, f"https://{settings.domain}")
    except (ValueError, TypeError) as exc:
        log.error("WHENSCLASS_VAPID_KEY не читается (%s) — уведомлений сайта не будет",
                  type(exc).__name__)
        return None
