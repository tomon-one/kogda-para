"""Лист пересдач: найти в книге, прочитать, держать последний удачный.

Живёт отдельно от расписания групп: его сбой не должен ни задерживать
расписание, ни делать службу stale. Лист пропал из книги — пересдач нет,
пустой список; лист не прочитался — отдаётся прежний.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import pathlib
import re
import zoneinfo
from collections import Counter
from collections.abc import Callable
from dataclasses import asdict

from ..config import settings
from ..domain.teachers import TeacherIndex, _surname_initials
from ..parser.retakes import Retake, parse_retakes
from ..sources import gsheets, sheet_index
from . import alerts

log = logging.getLogger(__name__)

_IS_RETAKES = re.compile(r"пересдач", re.IGNORECASE)
# Сеть и Google на минуту (429, 5xx, таймаут) — не повод будить владельца:
# прежний список отдаётся, а тревога — если сбой держится столько.
NETWORK_GRACE = dt.timedelta(hours=3)
# Сколько предметов преподавателя показывать: у почасовика их два-три, у
# заведующего — десяток, и длинный список прячет главное.
SUBJECTS_SHOWN = 3


def _who(index: TeacherIndex | None, name: str) -> tuple[str | None, list[str]]:
    """«Абб А.А.» из листа пересдач -> id в расписании и что он сейчас ведёт.

    Лист пишет фамилию с инициалами, расписание — обычно полное имя. Совпали
    фамилия и инициалы ровно у одного, кто ведёт пары, — это он; двое —
    не угадываем. Предметы — нынешние, а долг может быть по прошлогоднему:
    поэтому клиенты пишут «ведёт сейчас», а не «пересдача по».
    """
    if index is None:
        return None, []
    key = _surname_initials(name)
    if not key[0] or not key[1]:
        return None, []
    found = [tid for tid, full in index.names.items() if _surname_initials(full) == key and index.schedule.get(tid)]
    if len(found) != 1:
        return None, []
    tid = found[0]
    # «Физическая культура.», «.Математика» и без точки — один предмет.
    counts = Counter(x.lesson.subject.strip(" .,;") for lessons in index.schedule[tid].values() for x in lessons)
    return tid, [subject for subject, _ in counts.most_common(SUBJECTS_SHOWN)]


def _row(row: Retake, index: TeacherIndex | None) -> dict:
    tid, subjects = _who(index, row.teacher)
    return {
        "teacher": row.teacher, "dates": row.dates, "time": row.time, "room": row.room,
        "slots": [{"from": s.start.isoformat(), "to": s.end.isoformat(), "time": s.time} for s in row.slots],
        "teacher_id": tid, "subjects": subjects,
    }


class RetakesSource:
    def __init__(self, directory: pathlib.Path, teachers: Callable[[], TeacherIndex | None] = lambda: None):
        self.teachers = teachers
        self.path = directory / "retakes.json"
        # Тело ответа /v1/retakes; None — ещё ни разу не читали.
        self.payload: dict | None = None
        # Когда начался сбой сети или Google подряд; None — последнее чтение удалось.
        self._network_since: dt.datetime | None = None
        try:
            self.payload = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            pass
        except (OSError, ValueError) as exc:
            log.warning("retakes.json не читается (%s) — начну с пустого", exc)

    def refresh(self) -> None:
        # Рычаг заморозки: в таблицу не ходит никто, пересдачи тоже.
        if settings.freeze:
            return
        # Google просил ходить реже — лист пересдач подождёт, отдаётся прежний.
        if gsheets.throttled() and self.payload is not None:
            log.info("пересдачи: Google недавно ответил 429 — пропускаю заход")
            return
        try:
            sheets = [
                s for s in sheet_index.list_sheets(max_age=dt.timedelta(hours=1))
                if not s.hidden and s.gid and _IS_RETAKES.search(s.title)
            ]
        except Exception as exc:  # сбой API: тревогу о нём шлёт list_sheets
            log.warning("пересдачи: список листов не получен (%s)", sheet_index.hide_key(exc))
            return
        if not sheets:
            self._put({"title": None, "sheet": None, "rows": [], "redirects": []})
            return
        # Листов несколько — берём последний в книге: новые колледж добавляет в конец.
        sheet = sheets[-1]
        now = dt.datetime.now(zoneinfo.ZoneInfo(settings.timezone))
        try:
            parsed = parse_retakes(gsheets.fetch_sheet_csv(sheet.gid, sheet.title), now.date())
        except Exception as exc:
            log.warning("пересдачи: лист %r не прочитан: %s", sheet.title, sheet_index.hide_key(exc))
            if sheet_index._is_network(exc):
                self._network_since = self._network_since or now
                if now - self._network_since < NETWORK_GRACE:
                    return
            alerts.notify(
                "retakes",
                f"Лист пересдач «{sheet.title}» не прочитан: {sheet_index.hide_key(exc)}. "
                "Отдаётся прежний список.",
                window=24 * 60 * 60,
            )
            return
        self._network_since = None
        alerts.forget("retakes")
        self._put({
            "title": parsed.title,
            "sheet": sheet.title,
            "rows": [_row(r, self.teachers()) for r in parsed.rows],
            "redirects": [asdict(r) for r in parsed.redirects],
        })

    def _put(self, body: dict) -> None:
        old = dict(self.payload or {})
        old.pop("updated", None)
        if old == body:
            return
        body = {**body, "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")}
        self.payload = body
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        tmp.replace(self.path)
        log.info("пересдачи: %s, строк %d", body["sheet"] or "листа нет", len(body["rows"]))
