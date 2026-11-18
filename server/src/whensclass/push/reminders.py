"""Планирование и обновление напоминаний сайта о парах."""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import logging
import zoneinfo

from ..config import settings
from . import changes
from .delivery import Job
from .labels import _cancelled
from .messages import MAX_LINE, MAX_PAYLOAD, message
from .quiet import quiet_hours

log = logging.getLogger(__name__)

# Напоминания, чья минута пропущена (перезапуск, выкладка), догоняются, пока
# пара не началась; после перезапуска — за столько минут назад.
CATCH_UP_MINUTES = 10


class Reminders:
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
        if self._held and not quiet_hours(now):
            self._release(today)
        since = self._last_remind
        if since is None or since.date() != today or since >= now:
            since = now - dt.timedelta(minutes=CATCH_UP_MINUTES)
        self._last_remind = now
        if self._reminded_day != today.isoformat():
            self._reminded = set()
            self._reminded_day = today.isoformat()
        subs = [s for s in self._snapshot_subs() if s.get("remind") and not s.get("gone")]
        payload_for = self._payload_for(snapshot, teachers, today)
        plans: dict[tuple[str, str, int], list[dict]] = {}
        jobs = []
        for sub in subs:
            key = (sub["kind"], sub["id"], sub["remind"])
            if key not in plans:
                payload = payload_for(key[:2])
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
                    _reminder(sub, alarm, int(start * 1000), int(end * 1000)),
                    max(60, int((alarm["start"] - now).total_seconds())),
                    deadline=start,
                ))
        if jobs:
            log.info("напоминания о паре: %d", len(jobs))
            self._save_reminded()
            self._dispatch(jobs)
        self._digest(payload_for, self._snapshot_subs(), now, since, today)

    def _rewrite_reminders(self, before, after, today: dt.date) -> None:
        """Ушедшее сегодня напоминание о паре, которую потом отменили или
        перенесли, — переписать, как приложение переписывает своё.

        Только тем, кто без «изменений»: у остальных напоминание закрывает
        строка об изменении (sw.js). Молча снять уведомление браузер не даёт —
        на его месте встаёт новое: прежнее закрывает сервис-воркер.
        """
        now = self.clock().replace(tzinfo=None)
        with self._lock:
            reminded = set(self._reminded) if self._reminded_day == today.isoformat() else set()
        if not reminded:
            return
        subs = [s for s in self._snapshot_subs()
                if s.get("remind") and not s.get("changes") and not s.get("gone")]
        zone = zoneinfo.ZoneInfo(settings.timezone)

        def ms(moment: dt.datetime) -> int:
            return int(moment.replace(tzinfo=zone).timestamp() * 1000)

        plans: dict[tuple[str, str, int], tuple[dict, dict, dict] | None] = {}
        jobs = []
        for sub in subs:
            mark = _mark(sub["endpoint"])
            numbers = sorted(n for h, n in reminded if h == mark)
            if not numbers:
                continue
            key = (sub["kind"], sub["id"], sub["remind"])
            if key not in plans:
                try:
                    old = before(key[:2])
                    new = after(key[:2])
                except Exception:
                    log.exception("напоминания для %s не пересчитались", key[0])
                    old = new = None
                if old is None or new is None:
                    plans[key] = None
                else:
                    day = next((d for d in new.get("days", []) if d["d"] == today.isoformat()), {"l": []})
                    plans[key] = (
                        {a["number"]: a for a in changes.reminders(old, key[2], today)},
                        {a["number"]: a for a in changes.reminders(new, key[2], today)},
                        # Номера, где пара осталась: напоминание о ней могло
                        # не попасть в план из-за соседней пары, но пара есть.
                        {x["n"] for x in day["l"] if not _cancelled(x)},
                    )
            plan = plans[key]
            if plan is None:
                continue
            was, now_plan, standing = plan
            for number in numbers:
                old_alarm = was.get(number)
                if old_alarm is None or old_alarm["end"] <= now:
                    continue
                fresh = now_plan.get(number)
                if fresh is not None:
                    if all(fresh.get(f) == old_alarm.get(f) for f in ("title", "text", "join")):
                        continue
                    data = _reminder(sub, fresh, ms(fresh["start"]), ms(fresh["end"]))
                    end = fresh["end"]
                elif number in standing:
                    continue
                else:
                    end = old_alarm["end"]
                    data = message(sub, "lesson", f"Пару отменили — {old_alarm['subject']}"[:MAX_LINE],
                                   f"{old_alarm['start']:%H:%M}, {number} пара",
                                   {"end": ms(end), "n": number})
                jobs.append(Job(sub, data, max(60, int((end - now).total_seconds())), deadline=ms(end) / 1000))
        if jobs:
            log.info("напоминания переписаны: %d", len(jobs))
            self._dispatch(jobs)


def _reminder(sub: dict, alarm: dict, start: int, end: int) -> dict:
    """Напоминание, которое влезает в сообщение: больше MAX_PAYLOAD служба
    рассылки не примет, и напоминание молча не пришло бы никому. Сначала
    уходит «Войти» (длинная ссылка), потом строки короче."""
    join = {"join": alarm["join"]} if alarm.get("join") else {}
    for cut, extra in ((MAX_LINE, join), (MAX_LINE, {}), (100, {}), (40, {})):
        data = message(sub, "lesson", alarm["title"][:cut], alarm["text"][:cut], {
            "subject": alarm["subject"][:cut], "start": start, "end": end,
            "n": alarm["number"], **extra,
        })
        if len(json.dumps(data, ensure_ascii=False).encode("utf-8")) <= MAX_PAYLOAD:
            break
    return data


def _mark(endpoint: str) -> str:
    """Отметка напоминания на диске — хеш адреса, а не сам адрес: он секретен."""
    return hashlib.sha256(endpoint.encode("utf-8")).hexdigest()[:16]
