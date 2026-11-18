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
from . import changes, digest, webpush
from .delivery import MAX_PENDING_RETRIES, TIMEOUT, Delivery, Job, _Delayed
from .messages import (
    changes_message,
    changes_ttl,
    gone_message,
    message,
    welcome_text,
)
from .quiet import Quiet, _by_day, quiet_hours
from .reminders import Reminders, _mark, _reminder as _reminder
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


class Push(Delivery, Quiet, Reminders):
    """Подписки на диске и рассылка. Один на службу."""

    def __init__(self, state_dir: pathlib.Path, vapid: webpush.Vapid | None):
        self.path = state_dir / "push.json"
        self._reminded_path = state_dir / "push-reminded.json"
        # Изменения, придержанные на ночь: (kind, id) -> строки [день, текст].
        self._held_path = state_dir / "push-held.json"
        self._held: dict[tuple[str, str], list[list[str]]] = self._load_held()
        # Часы колледжа для тихих часов; в тестах подменяются.
        self.clock = lambda: dt.datetime.now(zoneinfo.ZoneInfo(settings.timezone))
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
        # Вечерняя сводка: кому за какой завтрашний день уже ушла — на диске,
        # чтобы выкладка в минуту сводки не прислала её дважды.
        self._digest_path = state_dir / "push-digest.json"
        self._digest_sent: set[str] = set()
        self._load_digest()
        # Отложить повтор: в тестах подменяется. False — очередь полна.
        self._delayed = _Delayed(MAX_PENDING_RETRIES)
        self._later = self._delayed.add
        # Отказы подряд у подписки: адрес -> (сколько, когда начались).
        self._fails: dict[str, tuple[int, float]] = {}
        # Когда служба рассылки последний раз доставила хоть кому-то.
        self._delivered: dict[str, float] = {}
        # За окно сводки: служба рассылки -> [доставлено, отказов, последний отказ].
        self._stats: dict[str, list] = {}
        # Отказы подряд по службе рассылки через окна CHECK_EVERY: у служб, где
        # подписчиков единицы, в одно окно ALARM_MIN_FAILS не набирается никогда.
        self._fail_streak: dict[str, int] = {}
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
            if not sub["changes"] and not sub["remind"] and not sub.get("digest"):
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
        log.info("%s подписка на уведомления: %s, изменения %s, напоминание %s, "
                 "сводка %s, всего %d",
                 "новая" if new else "обновлена", _host(sub["endpoint"]),
                 "да" if sub["changes"] else "нет", sub["remind"] or "нет",
                 _clock_text(sub.get("digest")) or "нет", len(self._subs))
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
            streak = 0 if ok else self._fail_streak.get(name, 0) + failed
            self._fail_streak[name] = streak
            if streak >= ALARM_MIN_FAILS:
                # Сплошной отказ (ключ VAPID, 403 FCM, служба лежит) иначе
                # виден только строками в журнале.
                alerts.notify(
                    f"push-{name}",
                    f"Уведомления сайта через {name} не доходят: {streak} отказов подряд "
                    f"и ни одной доставки, последний — {said}.",
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
        payload_for = self._payload_for(snapshot, teachers, today)
        gone = {key for key in keys if payload_for(key) is None}
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
        old_payload = self._payload_for(before, before_teachers, today)
        new_payload = self._payload_for(snapshot, teachers, today)
        self._rewrite_reminders(old_payload, new_payload, today)
        subs = [s for s in self._snapshot_subs() if s.get("changes") and not s.get("gone")]
        if not subs:
            return
        suspects = self._suspects(before, snapshot, today)
        lines_for: dict[tuple[str, str], list[list[str]]] = {}
        for key in {(s["kind"], s["id"]) for s in subs}:
            try:
                old = old_payload(key)
                new = new_payload(key)
                if old is None or new is None:
                    continue
                lines = changes.change_lines(old, new, today, same=True)
            except Exception:
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
        if quiet_hours(self.clock()):
            self._hold(lines_for)
            return
        if self._held:
            # Утро без выпуска (служба лежала в 7:00, заморозка): сначала ночное,
            # потом свежее — иначе последней строкой у получателя встанет старое.
            with self._lock:
                held, self._held = self._held, {}
                self._save_held()
            for key, night in held.items():
                fresh = lines_for.get(key, [])
                night = [x for x in night if x[0] >= today.isoformat() and x not in fresh]
                if night:
                    lines_for[key] = _by_day(night + fresh)
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
        except Exception:
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

    def _load_digest(self) -> None:
        try:
            data = json.loads(self._digest_path.read_text("utf-8"))
            marks = data.get("marks", []) if isinstance(data, dict) else []
            self._digest_sent = {str(mark) for mark in marks}
        except (OSError, ValueError, TypeError):
            self._digest_sent = set()

    def _save_digest(self) -> None:
        try:
            write_json(self._digest_path, {"marks": sorted(self._digest_sent)})
        except OSError as exc:
            log.warning("отметки вечерней сводки не записались: %s", exc)

    def _payload_for(self, snapshot, teachers, today: dt.date):
        from ..api.published import filling

        counts = None

        def build(key: tuple[str, str]) -> dict | None:
            nonlocal counts
            if counts is None:
                counts = filling(snapshot)
            return _payload(snapshot, teachers, key, today, self.known_teacher, counts=counts)

        return build

    def _digest(self, payload_for, subs, now: dt.datetime, since: dt.datetime,
                today: dt.date) -> None:
        """Вечерняя сводка: у кого её минута пришлась на время с прошлого
        запуска (после перезапуска — тоже) и у кого ещё нет отметки за
        завтрашний день. Готовое — только когда день прочитан и пары есть."""
        wanted = [s for s in subs if s.get("digest") and not s.get("gone")]
        if not wanted or not digest.evening(today):
            return
        tomorrow = today + dt.timedelta(days=1)
        payloads: dict[tuple[str, str], dict | None] = {}
        jobs = []
        for sub in wanted:
            minutes = sub["digest"]
            moment = dt.datetime.combine(today, dt.time(minutes // 60, minutes % 60))
            if not since < moment <= now:
                continue
            mark = tomorrow.isoformat() + "|" + _mark(sub["endpoint"])
            if mark in self._digest_sent:
                continue
            key = (sub["kind"], sub["id"])
            if key not in payloads:
                try:
                    payloads[key] = payload_for(key)
                except Exception:
                    log.exception("вечерняя сводка для %s не посчиталась", key[0])
                    payloads[key] = None
            pair = digest.text(payloads[key], tomorrow)
            if pair is None:
                continue
            title, body = pair
            self._digest_sent.add(mark)
            jobs.append(Job(sub, message(sub, "digest", title, body,
                                         {"day": tomorrow.isoformat()}), 12 * 3600))
        if jobs:
            log.info("вечерняя сводка: %d", len(jobs))
            # Старые отметки — прочь: файл не должен расти годами.
            keep = today.isoformat() + "|"
            self._digest_sent = {m for m in self._digest_sent if m >= keep}
            self._save_digest()
            self._dispatch(jobs)


def _clock_text(minutes: int | None) -> str | None:
    if not minutes:
        return None
    return f"{minutes // 60:02d}:{minutes % 60:02d}"


def _payload(
    snapshot, teachers, key: tuple[str, str], today: dt.date, known_teacher=None, *, counts=None
) -> dict | None:
    """Сегодня и завтра этого субъекта — как их отдал бы API: у преподавателя
    тем же путём, что /v1/teacher, с полной записью по краткой и днями без пар
    для того, кто был в прежних снимках."""
    from ..api.payloads import schedule_payload, teacher_answer
    from ..service.bells import BELLS

    kind, subject = key
    generated = dt.datetime.now(dt.UTC)
    if kind == "group":
        return schedule_payload(snapshot, subject, today, 2, generated, bells=BELLS, today=today, counts=counts)
    if teachers is None:
        return None
    return teacher_answer(
        snapshot, teachers, known_teacher or (lambda _: None), subject, today, 2, generated,
        bells=BELLS, today=today, counts=counts,
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
