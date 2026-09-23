"""Хранение разобранного расписания.

Держим снимок в памяти и его копию на диске: после перезапуска сервиса
телефоны не должны остаться без расписания, пока не отработает первый обход
таблицы. Предыдущая копия хранится рядом — на случай отката.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import pathlib
import threading

from ..domain.models import GroupRef, Lesson, SheetPlace, Snapshot
from ..domain.teachers import TeacherIndex, build_index

log = logging.getLogger(__name__)


class SnapshotStore:
    def __init__(self, state_dir: pathlib.Path):
        self.path = state_dir / "snapshot.json"
        self.previous = state_dir / "snapshot.prev.json"
        self._lock = threading.Lock()
        self._snapshot: Snapshot | None = None
        self._generated: dt.datetime | None = None
        self._teachers: TeacherIndex | None = None

    @property
    def snapshot(self) -> Snapshot | None:
        return self._snapshot

    @property
    def generated(self) -> dt.datetime | None:
        return self._generated

    @property
    def teachers(self) -> TeacherIndex | None:
        """Расписание преподавателей — перевёрнутый снимок, считается один раз."""
        if self._snapshot is None:
            return None
        if self._teachers is None:
            self._teachers = build_index(self._snapshot)
        return self._teachers

    def put(
        self, snapshot: Snapshot, generated: dt.datetime, teachers: TeacherIndex | None = None
    ) -> None:
        """`teachers` — индекс, уже собранный по этому снимку: его не строят заново."""
        with self._lock:
            self._snapshot = snapshot
            self._generated = generated
            self._teachers = teachers
            self._write(snapshot, generated)

    def load(self) -> bool:
        """Поднимает снимок с диска. False, если его нет или он испорчен."""
        try:
            data = json.loads(self.path.read_text("utf-8"))
        except (OSError, ValueError) as exc:
            log.info("снимок с диска не поднялся: %s", exc)
            return False
        try:
            self._snapshot = _from_dict(data["snapshot"])
            self._generated = dt.datetime.fromisoformat(data["generated"])
            self._teachers = None
        except (KeyError, ValueError, TypeError) as exc:
            log.warning("снимок на диске испорчен: %s", exc)
            return False
        return True

    def _write(self, snapshot: Snapshot, generated: dt.datetime) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists():
            try:
                self.previous.write_bytes(self.path.read_bytes())
            except OSError as exc:
                log.warning("не смог сохранить предыдущий снимок: %s", exc)
        payload = {"generated": generated.isoformat(), "snapshot": _to_dict(snapshot)}
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, ensure_ascii=False), "utf-8")
        tmp.replace(self.path)  # атомарно: читатель не увидит половину файла


def _to_dict(snapshot: Snapshot) -> dict:
    return {
        "sheet_title": snapshot.sheet_title,
        "groups": [
            {"name": g.name, "id": g.id, "column": g.column} for g in snapshot.groups
        ],
        "dates": [d.isoformat() for d in snapshot.dates],
        "places": {
            day.isoformat(): {"gid": place.gid, "row": place.row}
            for day, place in snapshot.places.items()
        },
        "schedule": {
            gid: {
                day.isoformat(): [_lesson_to_dict(x) for x in lessons]
                for day, lessons in by_date.items()
            }
            for gid, by_date in snapshot.schedule.items()
        },
    }


def _lesson_to_dict(lesson: Lesson) -> dict:
    return {
        "number": lesson.number,
        "subject": lesson.subject,
        "kind": lesson.kind,
        "teachers": list(lesson.teachers),
        "room": lesson.room,
        "url": lesson.url,
        "online": lesson.online,
        "cancelled": lesson.cancelled,
        "note": lesson.note,
    }


def _from_dict(data: dict) -> Snapshot:
    snapshot = Snapshot(
        sheet_title=data["sheet_title"],
        groups=[GroupRef(**g) for g in data["groups"]],
        dates=[dt.date.fromisoformat(d) for d in data["dates"]],
    )
    # Снимки до 14 сентября 2026 записаны без мест: тогда ссылка «открыть
    # таблицу» просто откроет книгу, как и раньше.
    snapshot.places = {
        dt.date.fromisoformat(day): SheetPlace(gid=place.get("gid"), row=int(place["row"]))
        for day, place in data.get("places", {}).items()
    }
    snapshot.schedule = {
        gid: {
            dt.date.fromisoformat(day): [
                Lesson(
                    number=x["number"],
                    subject=x["subject"],
                    kind=x["kind"],
                    teachers=tuple(x["teachers"]),
                    room=x["room"],
                    url=x["url"],
                    # Снимок на диске мог быть записан до того, как
                    # признак появился: тогда его просто нет.
                    online=x.get("online", x["url"] is not None),
                    cancelled=x["cancelled"],
                    note=x.get("note"),
                )
                for x in lessons
            ]
            for day, lessons in by_date.items()
        }
        for gid, by_date in data["schedule"].items()
    }
    return snapshot
