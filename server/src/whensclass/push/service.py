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
import hashlib
import heapq
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
from ..service import alerts
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
# Изменения живут у службы рассылки до конца последнего дня в сводке, но не
# меньше минуты (`changes_ttl`). Было — 12 часов при любом дне: «на завтра»,
# отправленное вечером, истекало ночью, до пары, а «на сегодня» приходило
# назавтра (четвёртый аудит, контроль №5 и М37 прогона 1).
CHANGES_TTL_MIN = 60
# Сообщение целиком — до 4 КБ зашифрованным (Apple, RFC 8291: 3993 байта
# открытого текста); с запасом.
MAX_PAYLOAD = 3000
MAX_LINE = 300
# Одна повторная попытка на 429 и 5xx: через Retry-After, но не дольше двух минут.
RETRY_AFTER = 30
RETRY_MAX = 120
# Повторы ждут в одной очереди с одним потоком; больше стольких — не встают.
# Было — поток ОС на каждый повтор: 20 000 неудач за минуту упирались в
# MemoryMax (четвёртый аудит, В26 прогона 1).
MAX_PENDING_RETRIES = 2000
# Соединение со службой рассылки — не дольше трёх секунд: пока Google не
# отвечает, его подписки держали очередь по 10 с каждая (В6).
TIMEOUT = httpx.Timeout(10.0, connect=3.0)
WORKERS_PER_SERVICE = 4
# Подписку, которая не доставляется столько раз подряд, служба снимает — если
# та же служба рассылки за это время доставляла другим: сбой всей службы
# или нашего ключа подписки не стирает (В16).
DROP_AFTER_FAILS = 5
# Раз в столько секунд — сводка отказов по службам рассылки (тревога владельцу,
# М38) и проверка пропавших групп (В21).
CHECK_EVERY = 600
ALARM_MIN_FAILS = 5
# Группа или преподаватель пропали из таблицы: через час (как в приложении) —
# одно уведомление «выберите заново», через две недели запись стирается.
GONE_NOTICE_AFTER = 3600
GONE_DROP_DAYS = 14
# Напоминания, чья минута пропущена (перезапуск, выкладка), догоняются, пока
# пара не началась; после перезапуска — за столько минут назад (М25).
CATCH_UP_MINUTES = 10


def family(endpoint: str) -> str:
    """Служба рассылки: у каждой своя очередь."""
    host = _host(endpoint)
    for name, suffix in (("google", "googleapis.com"), ("mozilla", "mozilla.com"),
                         ("apple", "apple.com"), ("microsoft", "windows.com")):
        if host == suffix or host.endswith("." + suffix):
            return name
    return host


class Job(NamedTuple):
    sub: dict
    message: dict
    ttl: int
    # Позже этого (секунды эпохи) повторять незачем: пара уже началась.
    deadline: float | None = None


def endpoint_allowed(endpoint: str) -> bool:
    if not isinstance(endpoint, str) or len(endpoint) > MAX_ENDPOINT:
        return False
    # Только печатный ASCII без пробелов: urlsplit молча выкидывает \t и \n, а
    # httpx на таком адресе бросал InvalidURL мимо журнала (М76).
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


class Push:
    """Подписки на диске и рассылка. Один на службу."""

    def __init__(self, state_dir: pathlib.Path, vapid: webpush.Vapid | None):
        self.path = state_dir / "push.json"
        self._reminded_path = state_dir / "push-reminded.json"
        self.vapid = vapid
        self._lock = threading.RLock()
        self._subs: dict[str, dict] = self._load()
        # Своя очередь у каждой службы рассылки: пока Google не отвечает, его
        # подписки не держат айфоны (четвёртый аудит, В6 прогона 1).
        self._pools: dict[str, concurrent.futures.ThreadPoolExecutor] = {}
        self._client = httpx.Client(timeout=TIMEOUT, headers={"User-Agent": settings.user_agent})
        # Какие напоминания уже ушли: (хеш адреса, номер пары) за день — на
        # диске, чтобы догонялка после перезапуска не повторила ушедшее.
        self._reminded: set[tuple[str, int]] = set()
        self._reminded_day: str | None = None
        self._last_remind: dt.datetime | None = None
        self._load_reminded()
        # Отложить повтор: в тестах подменяется. False — очередь полна.
        self._delayed = _Delayed(MAX_PENDING_RETRIES)
        self._later = self._delayed.add
        # Отказы подряд у подписки: адрес -> (сколько, когда начались).
        self._fails: dict[str, tuple[int, float]] = {}
        # Когда служба рассылки последний раз доставила хоть кому-то.
        self._delivered: dict[str, float] = {}
        # За окно сводки: служба рассылки -> [доставлено, отказов, последний отказ].
        self._stats: dict[str, list] = {}
        self._checked = time.monotonic()
        # Кого нет в снимке: (kind, id) -> с какого часа (monotonic).
        self._missing: dict[tuple[str, str], float] = {}
        # Имя преподавателя из прежних снимков (SnapshotStore.known_teacher):
        # уведомления считаются тем же путём, что /v1/teacher (М41).
        self.known_teacher = lambda teacher_id: None

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
        на новый адрес. Знать прежний адрес — и есть право: он секретен.
        False — прежней записи нет (её уже стёрли по 410): маршрут отвечает
        404, и страница перешлёт подписку сама (М75)."""
        with self._lock:
            record = self._subs.pop(old, None)
            if record is None:
                return False
            self._subs[fresh["endpoint"]] = {**record, **fresh}
            self._fails.pop(old, None)
            self._save()
        log.info("подписка на уведомления перенесена браузером: %s -> %s",
                 _host(old), _host(fresh["endpoint"]))
        return True

    def unsubscribe(self, endpoint: str, why: str = "выключили") -> None:
        with self._lock:
            self._fails.pop(endpoint, None)
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
        """Одна доставка. Любое исключение — в журнал, а не в Future, которую
        никто не читает (М76)."""
        try:
            self._deliver(job, attempt)
        except Exception as exc:  # noqa: BLE001 — страховка потока пула
            log.warning("уведомление не ушло (%s): %s", _host(job.sub["endpoint"]),
                        type(exc).__name__)
            self._failed(job.sub, "error", type(exc).__name__)

    def _deliver(self, job: Job, attempt: int) -> None:
        if self.vapid is None:
            return
        sub = job.sub
        kind = job.message["notification"]["data"]["t"]
        if job.deadline is not None and time.time() >= job.deadline:
            # Очередь продержала напоминание до начала пары — оно уже не нужно.
            log.info("напоминание опоздало к началу пары (%s) — не шлю", _host(sub["endpoint"]))
            return
        try:
            result = webpush.send(
                self._client, self.vapid, sub["endpoint"], sub["p256dh"], sub["auth"],
                job.message, job.ttl, urgency="high" if kind == "lesson" else "normal",
            )
        except webpush.TooLarge as exc:
            # Наша ошибка, не подписки: не снимаем и не повторяем.
            log.warning("уведомление не влезло (%s): %s", kind, exc)
            return
        except ValueError as exc:
            # Ключи подписки не расшифровываются (не точка кривой) — до сети:
            # доставить такую нельзя никогда.
            self.unsubscribe(sub["endpoint"], why=f"ключи не годятся: {exc}")
            return
        except httpx.HTTPError as exc:
            # Адрес — только хост: путь подписки и есть её секрет.
            log.warning("уведомление не ушло (%s): %s", _host(sub["endpoint"]), type(exc).__name__)
            if not self._retry(job, attempt, None):
                self._failed(sub, "net", type(exc).__name__)
            return
        if result.status < 300:
            self._succeeded(sub)
            return
        said = f"{result.status}" + (f" {result.reason}" if result.reason else "")
        if result.status in webpush.GONE or result.reason == "VapidPkHashMismatch":
            # Подписки нет или она под другим ключом — слать дальше незачем;
            # страница при открытии подпишется заново.
            self.unsubscribe(sub["endpoint"], why=f"служба рассылки ответила {said}")
            return
        log.warning("служба рассылки %s ответила %s (%s)", _host(sub["endpoint"]), said, kind)
        if result.status in webpush.RETRY and self._retry(job, attempt, result.retry_after):
            return
        self._failed(sub, "status", said)

    def _succeeded(self, sub: dict) -> None:
        name = family(sub["endpoint"])
        with self._lock:
            self._delivered[name] = time.monotonic()
            self._fails.pop(sub["endpoint"], None)
            self._stats.setdefault(name, [0, 0, None])[0] += 1

    def _failed(self, sub: dict, how: str, said: str) -> None:
        """Доставка не удалась окончательно (повторы кончились или не нужны)."""
        endpoint, name = sub["endpoint"], family(sub["endpoint"])
        now = time.monotonic()
        with self._lock:
            stat = self._stats.setdefault(name, [0, 0, None])
            stat[1] += 1
            stat[2] = said
            count, since = self._fails.get(endpoint, (0, now))
            count += 1
            self._fails[endpoint] = (count, since)
            dead = count >= DROP_AFTER_FAILS and self._delivered.get(name, -1.0) > since
        if dead:
            self.unsubscribe(endpoint, why=f"не доставляется {count} раз подряд: {said}")

    def _retry(self, job: Job, attempt: int, after: int | None) -> bool:
        """Одна повторная попытка; напоминание — только пока пара не началась.
        True — повтор встал в очередь."""
        if attempt > 0:
            return False
        delay = min(max(after or RETRY_AFTER, 1), RETRY_MAX)
        if job.deadline is not None and time.time() + delay >= job.deadline:
            return False
        again = job._replace(ttl=max(60, job.ttl - delay))
        if self._later(delay, lambda: self._submit(again, attempt + 1)) is False:
            log.warning("очередь повторов полна (%d) — повтор не встал", MAX_PENDING_RETRIES)
            return False
        return True

    def _submit(self, job: Job, attempt: int = 0) -> None:
        name = family(job.sub["endpoint"])
        with self._lock:
            pool = self._pools.get(name)
            if pool is None:
                pool = concurrent.futures.ThreadPoolExecutor(
                    max_workers=WORKERS_PER_SERVICE, thread_name_prefix=f"push-{name}"[:15]
                )
                self._pools[name] = pool
        pool.submit(self._send, job, attempt)

    def _dispatch(self, jobs: list[Job]) -> None:
        for job in jobs:
            self._submit(job)

    def _check(self, snapshot, teachers, today: dt.date, fresh: bool) -> None:
        """Раз в CHECK_EVERY: тревога о службах рассылки и пропавшие группы."""
        now = time.monotonic()
        if now - self._checked < CHECK_EVERY:
            return
        self._checked = now
        with self._lock:
            stats, self._stats = self._stats, {}
        for name, (ok, failed, said) in sorted(stats.items()):
            if failed >= ALARM_MIN_FAILS and ok == 0:
                # Сплошной отказ (ключ VAPID, 403 FCM, служба лежит) раньше
                # был виден только строками в журнале (М38).
                alerts.notify(
                    f"push-{name}",
                    f"Уведомления сайта через {name} не доходят: {failed} отказов за "
                    f"{CHECK_EVERY // 60} минут и ни одной доставки, последний — {said}.",
                )
        if fresh and snapshot is not None:
            self._check_gone(snapshot, teachers, today)

    def _check_gone(self, snapshot, teachers, today: dt.date) -> None:
        """Группы или преподавателя больше нет в таблице (переименовали): через
        час — одно уведомление «выберите заново», через GONE_DROP_DAYS — запись
        стёрта. Было — тишина навсегда и вечная запись (В21, М79)."""
        now = time.monotonic()
        subs = self._snapshot_subs()
        keys = {(s["kind"], s["id"]) for s in subs}
        gone = {key for key in keys if _payload(snapshot, teachers, key, today, self.known_teacher) is None}
        self._missing = {key: self._missing.get(key, now) for key in gone}
        notices, drop, changed = [], [], False
        with self._lock:
            for sub in subs:
                key = (sub["kind"], sub["id"])
                record = self._subs.get(sub["endpoint"])
                if record is None:
                    continue
                if key not in gone:
                    if record.pop("gone", None) is not None:
                        changed = True
                    continue
                since = record.get("gone")
                if since is None and now - self._missing[key] >= GONE_NOTICE_AFTER:
                    record["gone"] = today.isoformat()
                    changed = True
                    notices.append(Job(record, gone_message(record), 7 * 86400))
                elif since is not None and (today - dt.date.fromisoformat(since)).days >= GONE_DROP_DAYS:
                    drop.append(sub["endpoint"])
            if changed:
                self._save()
        for endpoint in drop:
            self.unsubscribe(endpoint, why=f"нет в таблице {GONE_DROP_DAYS} дней")
        if notices:
            log.info("группы или преподавателя нет в таблице: %d уведомлений", len(notices))
            self._dispatch(notices)

    # --- изменения -----------------------------------------------------------

    def after_refresh(self, before, before_teachers, snapshot, teachers, today: dt.date) -> None:
        """Снимок обновился — сравнить сегодня и завтра у каждого, кто подписан."""
        if not self.enabled or before is None or settings.freeze:
            return
        subs = [s for s in self._snapshot_subs() if s.get("changes") and not s.get("gone")]
        if not subs:
            return
        suspects = self._suspects(before, snapshot, today)
        lines_for: dict[tuple[str, str], list[list[str]]] = {}
        for key in {(s["kind"], s["id"]) for s in subs}:
            try:
                old = _payload(before, before_teachers, key, today, self.known_teacher)
                new = _payload(snapshot, teachers, key, today, self.known_teacher)
                if old is None or new is None:
                    continue
                lines = changes.change_lines(old, new, today)
            except Exception:  # noqa: BLE001
                # Сбой одного не глушит остальных (В27).
                log.exception("изменения для %s не посчитались", key[0])
                continue
            if suspects:
                if key[0] == "group" and key[1] in suspects["ids"]:
                    continue
                lines = [x for x in lines
                         if not any(f"({n}" in x[1] or f", {n}" in x[1] for n in suspects["names"])]
            if lines:
                lines_for[key] = lines
        if not lines_for:
            return
        jobs = []
        for sub in subs:
            lines = lines_for.get((sub["kind"], sub["id"]))
            if lines:
                jobs.append(Job(sub, changes_message(sub, lines), changes_ttl(lines)))
        log.info("изменения в расписании: %d субъектов, %d уведомлений", len(lines_for), len(jobs))
        self._dispatch(jobs)

    def _suspects(self, before, snapshot, today: dt.date) -> dict | None:
        """Группы, чья правка похожа на пары соседа (сдвиг, который отказ не
        поймал): им — без уведомлений, владельцу — тревога (М32)."""
        from ..parser.csv_schedule import neighbour_runs

        try:
            ids = neighbour_runs(snapshot, before, today)
        except Exception:  # noqa: BLE001
            log.exception("сверка с соседями для уведомлений упала")
            return None
        if not ids:
            return None
        names = sorted(g.name for g in snapshot.groups if g.id in ids)
        log.warning("правка похожа на пары соседа у %s — уведомления им не шлю", ", ".join(names))
        alerts.notify(
            "push-shift",
            f"Правка листа у групп {', '.join(names[:6])} похожа на пары соседа (сдвиг ячеек?) — "
            "уведомления сайта им не ушли. Присмотреться (руководство по серверу, «Когда "
            "что-то не так»).",
        )
        return {"ids": ids, "names": names}

    # --- напоминания ---------------------------------------------------------

    def _load_reminded(self) -> None:
        try:
            data = json.loads(self._reminded_path.read_text("utf-8"))
            self._reminded_day = str(data["day"])
            self._reminded = {(str(h), int(n)) for h, n in data["marks"]}
        except (OSError, ValueError, KeyError, TypeError):
            self._reminded, self._reminded_day = set(), None

    def _save_reminded(self) -> None:
        try:
            write_json(self._reminded_path,
                       {"day": self._reminded_day, "marks": sorted(self._reminded)})
        except OSError as exc:
            log.warning("отметки напоминаний не записались: %s", exc)

    def remind(self, snapshot, teachers, now: dt.datetime | None = None, fresh: bool = True) -> None:
        """Раз в минуту: кому пора напомнить о паре.

        Шлётся всё, чья минута пришлась на время с прошлого запуска (после
        перезапуска — за CATCH_UP_MINUTES), пока пара не началась: раньше
        минута сверялась на равенство, и выкладка в минуту напоминания его
        теряла (М25). При заморозке — молчит: снимок заведомо чужой (М36).
        """
        if not self.enabled or snapshot is None:
            return
        zone = zoneinfo.ZoneInfo(settings.timezone)
        now = (now or dt.datetime.now(zone)).replace(tzinfo=None, second=0, microsecond=0)
        today = now.date()
        self._check(snapshot, teachers, today, fresh)
        if settings.freeze:
            return
        since = self._last_remind
        if since is None or since.date() != today or since >= now:
            since = now - dt.timedelta(minutes=CATCH_UP_MINUTES)
        self._last_remind = now
        if self._reminded_day != today.isoformat():
            self._reminded = set()
            self._reminded_day = today.isoformat()
        subs = [s for s in self._snapshot_subs() if s.get("remind") and not s.get("gone")]
        plans: dict[tuple[str, str, int], list[dict]] = {}
        jobs = []
        for sub in subs:
            key = (sub["kind"], sub["id"], sub["remind"])
            if key not in plans:
                payload = _payload(snapshot, teachers, key[:2], today, self.known_teacher)
                plans[key] = changes.reminders(payload, key[2], today) if payload else []
            for alarm in plans[key]:
                if not since < alarm["at"] <= now or alarm["start"] <= now:
                    continue
                mark = (_mark(sub["endpoint"]), alarm["number"])
                if mark in self._reminded:
                    continue
                self._reminded.add(mark)
                start = alarm["start"].replace(tzinfo=zone).timestamp()
                end = alarm["end"].replace(tzinfo=zone).timestamp()
                jobs.append(Job(
                    sub,
                    message(sub, "lesson", alarm["title"][:MAX_LINE], alarm["text"][:MAX_LINE], {
                        "subject": alarm["subject"][:MAX_LINE],
                        "start": int(start * 1000),
                        "end": int(end * 1000),
                    }),
                    max(60, int((alarm["start"] - now).total_seconds())),
                    deadline=start,
                ))
        if jobs:
            log.info("напоминания о паре: %d", len(jobs))
            self._save_reminded()
            self._dispatch(jobs)


class _Delayed:
    """Отложенные повторы — одна очередь и один поток, а не поток ОС на
    каждый повтор (четвёртый аудит, В26 прогона 1)."""

    def __init__(self, limit: int):
        self._heap: list[tuple[float, int, object]] = []
        self._cond = threading.Condition()
        self._seq = 0
        self._limit = limit
        self._thread: threading.Thread | None = None

    def add(self, delay: float, action) -> bool:
        with self._cond:
            if len(self._heap) >= self._limit:
                return False
            self._seq += 1
            heapq.heappush(self._heap, (time.monotonic() + delay, self._seq, action))
            if self._thread is None:
                self._thread = threading.Thread(target=self._run, name="push-retry", daemon=True)
                self._thread.start()
            self._cond.notify()
        return True

    def _run(self) -> None:
        while True:
            with self._cond:
                while not self._heap:
                    self._cond.wait()
                due, _, action = self._heap[0]
                wait = due - time.monotonic()
                if wait > 0:
                    self._cond.wait(wait)
                    continue
                heapq.heappop(self._heap)
            try:
                action()
            except Exception:  # noqa: BLE001
                log.exception("повтор уведомления упал")


def _mark(endpoint: str) -> str:
    """Отметка напоминания на диске — хеш адреса, а не сам адрес: он секретен."""
    return hashlib.sha256(endpoint.encode("utf-8")).hexdigest()[:16]


def changes_ttl(lines: list[list[str]], now: dt.datetime | None = None) -> int:
    """Сколько сводке жить у службы рассылки: до конца последнего её дня по
    времени колледжа. Сводка «на сегодня» не придёт назавтра, «на завтра» —
    не истечёт ночью."""
    zone = zoneinfo.ZoneInfo(settings.timezone)
    now = now or dt.datetime.now(zone)
    last = max(dt.date.fromisoformat(day) for day, _ in lines)
    end = dt.datetime.combine(last + dt.timedelta(days=1), dt.time(), tzinfo=zone)
    return max(CHANGES_TTL_MIN, int((end - now).total_seconds()))


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
    с висящим. Не влезает в MAX_PAYLOAD — прочь завтрашний хвост, а не
    сегодняшнее: строки идут по дням (М33 прогона 1 аудита 4)."""
    kept = [[day, text[:MAX_LINE]] for day, text in lines]
    while True:
        out = message(sub, "changes", "Расписание изменилось", "\n".join(t for _, t in kept),
                      {"days": [d for d, _ in kept]})
        if len(kept) == 1 or len(json.dumps(out, ensure_ascii=False).encode("utf-8")) <= MAX_PAYLOAD:
            return out
        kept = kept[:-1]


def gone_message(sub: dict) -> dict:
    """Группы или преподавателя больше нет в таблице — как в приложении."""
    if sub.get("kind") == "teacher":
        title, what = "Вас больше нет в таблице", "себя"
    else:
        title, what = "Группы больше нет в таблице", "группу"
    return message(sub, "gone", title,
                   f"Откройте сайт и выберите {what} заново: до этого уведомлений не будет.")


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


def _payload(
    snapshot, teachers, key: tuple[str, str], today: dt.date, known_teacher=None
) -> dict | None:
    """Сегодня и завтра этого субъекта — как их отдал бы API: у преподавателя
    тем же путём, что /v1/teacher, с полной записью по краткой и днями без пар
    для того, кто был в прежних снимках (М41)."""
    from ..api.payloads import schedule_payload, teacher_answer
    from ..service.bells import BELLS

    kind, subject = key
    generated = dt.datetime.now(dt.timezone.utc)
    if kind == "group":
        return schedule_payload(snapshot, subject, today, 2, generated, bells=BELLS, today=today)
    if teachers is None:
        return None
    return teacher_answer(
        snapshot, teachers, known_teacher or (lambda _: None), subject, today, 2, generated,
        bells=BELLS, today=today,
    )


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
