"""Уведомления сайта: подписки, изменения после обновления, напоминания о паре.

Приложение считает всё это само, на телефоне; сайт так не может — браузер не
будит страницу по расписанию. Поэтому для сайта то же считает служба и шлёт
через службу рассылки браузера (Web Push). Правила — как в приложении
(changes.py).

Что хранится: адрес подписки браузера и два его ключа, чьё расписание (группа
или преподаватель), что присылать, какой сайт, дата последней пересылки
подписки, дата первой доставки и дата уведомления «нет в таблице». Больше
ничего — ни адреса человека, ни браузера. Выключил уведомления — запись
стёрта; служба рассылки ответила, что подписки нет, — тоже.
"""

from __future__ import annotations

import concurrent.futures
import datetime as dt
import hashlib
import json
import logging
import pathlib
import threading
import time
import zoneinfo

import httpx

from ..config import settings
from ..service import alerts
from ..storage.atomic import write_json
from . import changes, webpush
from .delivery import MAX_PENDING_RETRIES, TIMEOUT, Delivery, Job, _Delayed
from .messages import (
    MAX_LINE,
    changes_message,
    changes_ttl,
    gone_message,
    message,
    welcome_text,
)
from .subscription import _host

log = logging.getLogger(__name__)

# Потолок подписок: запись в файл целиком, и без потолка его можно раздуть.
MAX_SUBSCRIPTIONS = 20000
# Раз в столько секунд — сводка отказов по службам рассылки (тревога владельцу)
# и проверка пропавших групп.
CHECK_EVERY = 600
ALARM_MIN_FAILS = 5
# Группа или преподаватель пропали из таблицы: через час (как в приложении) —
# одно уведомление «выберите заново», через две недели запись стирается.
GONE_NOTICE_AFTER = 3600
GONE_DROP_DAYS = 14
# Напоминания, чья минута пропущена (перезапуск, выкладка), догоняются, пока
# пара не началась; после перезапуска — за столько минут назад.
CATCH_UP_MINUTES = 10


class Push(Delivery):
    """Подписки на диске и рассылка. Один на службу."""

    def __init__(self, state_dir: pathlib.Path, vapid: webpush.Vapid | None):
        self.path = state_dir / "push.json"
        self._reminded_path = state_dir / "push-reminded.json"
        self.vapid = vapid
        self._lock = threading.RLock()
        self._subs: dict[str, dict] = self._load()
        # Своя очередь у каждой службы рассылки: пока Google не отвечает, его
        # подписки не держат айфоны.
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
        # уведомления считаются тем же путём, что /v1/teacher.
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
        доходят, а не ждёт первой отмены пары.
        """
        with self._lock:
            if not sub["changes"] and not sub["remind"]:
                self.unsubscribe(sub["endpoint"])
                return True
            old = self._subs.get(sub["endpoint"])
            new = old is None
            if new and len(self._subs) >= MAX_SUBSCRIPTIONS and not self._evict_undelivered():
                return False
            record = {**sub, "since": today.isoformat()}
            # Страница пересылает подписку раз в сутки: отметка «доставлялось»
            # при этом не стирается, иначе настоящая подписка снова выглядит
            # мусором и под потолком уходит раньше свежего мусора.
            if old is not None and old.get("ok"):
                record["ok"] = old["ok"]
            # И отметка «нет в таблице» о том же выборе: иначе «выберите
            # заново» приходило бы после каждой пересылки.
            if old is not None and old.get("gone") and (old["kind"], old["id"]) == (sub["kind"], sub["id"]):
                record["gone"] = old["gone"]
            self._subs[sub["endpoint"]] = record
            self._save()
        # В журнал — служба рассылки и выбор, без адреса и группы: по ним
        # видно, откуда взялось лишнее «Уведомления включены».
        log.info("%s подписка на уведомления: %s, изменения %s, напоминание %s, всего %d",
                 "новая" if new else "обновлена", _host(sub["endpoint"]),
                 "да" if sub["changes"] else "нет", sub["remind"] or "нет", len(self._subs))
        if new:
            self._dispatch([Job(sub, message(sub, "hello", "Уведомления включены",
                                             welcome_text(sub)), 3600)])
        return True

    def _evict_undelivered(self) -> bool:
        """Мест нет — снять самую старую подписку, которой ни разу не
        доставили (у настоящей «Уведомления включены» доходит сразу). Мусор с
        выдуманными адресами забивает потолок за часы, и новые люди получили бы
        «сервер не принял». Под замком."""
        victim = min((s for s in self._subs.values() if not s.get("ok")),
                     key=lambda s: s.get("since", ""), default=None)
        if victim is None:
            return False
        self._subs.pop(victim["endpoint"], None)
        self._fails.pop(victim["endpoint"], None)
        log.info("подписок под потолок — снята не доставленная ни разу: %s", _host(victim["endpoint"]))
        return True

    def move(self, old: str, fresh: dict) -> bool:
        """Браузер сменил подписку сам (pushsubscriptionchange): прежний выбор —
        на новый адрес. Знать прежний адрес — и есть право: он секретен.
        False — прежней записи нет (её уже стёрли по 410): маршрут отвечает
        404, и страница перешлёт подписку сама."""
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
                # Сплошной отказ (ключ VAPID, 403 FCM, служба лежит) иначе
                # виден только строками в журнале.
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
        стёрта."""
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
                lines = changes.change_lines(old, new, today, same=True)
            except Exception:  # noqa: BLE001
                # Сбой одного не глушит остальных.
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
        поймал): им — без уведомлений, владельцу — тревога."""
        from ..parser.shift import neighbour_runs, vertical_groups

        try:
            # И пары, съехавшие по вертикали в одной колонке: лист принят с
            # подозрением, номера пар под вопросом.
            ids = neighbour_runs(snapshot, before, today) | vertical_groups(snapshot, before)
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
        перезапуска — за CATCH_UP_MINUTES), пока пара не началась: выкладка в
        минуту напоминания не должна его терять. При заморозке — молчит: снимок
        заведомо чужой.
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


def _mark(endpoint: str) -> str:
    """Отметка напоминания на диске — хеш адреса, а не сам адрес: он секретен."""
    return hashlib.sha256(endpoint.encode("utf-8")).hexdigest()[:16]


def _payload(
    snapshot, teachers, key: tuple[str, str], today: dt.date, known_teacher=None
) -> dict | None:
    """Сегодня и завтра этого субъекта — как их отдал бы API: у преподавателя
    тем же путём, что /v1/teacher, с полной записью по краткой и днями без пар
    для того, кто был в прежних снимках."""
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
