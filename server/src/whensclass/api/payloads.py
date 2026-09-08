"""Сборка тел ответов API.

Ключи короткие намеренно: ответ уезжает на телефон и разбирается виджетом при
каждом обновлении. Правило про отсутствующие поля — в docs/api.md: пустого
поля в JSON нет вовсе, чтобы не гонять null-ы.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

from ..domain.models import Lesson, Snapshot
from ..domain.teachers import TeacherIndex

API_VERSION = 1


def _lesson(lesson: Lesson) -> dict:
    out: dict = {"n": lesson.number, "s": lesson.subject}
    if lesson.kind:
        out["k"] = lesson.kind
    if lesson.teachers:
        out["t"] = list(lesson.teachers)
    if lesson.room:
        out["r"] = lesson.room
    if lesson.url:
        out["u"] = lesson.url
    if lesson.online or lesson.url:
        # Онлайн без ссылки — обычное дело: ссылку дают позже. Поэтому
        # признак отдельный, а не выводится из наличия «u».
        out["o"] = 1
    if lesson.cancelled:
        out["x"] = 1
    if lesson.note:
        out["c"] = lesson.note
    return out


def teachers_payload(index: TeacherIndex, generated: datetime) -> dict:
    return {
        "v": API_VERSION,
        "gen": _iso(generated),
        "teachers": [
            {"id": tid, "name": name}
            for tid, name in sorted(index.names.items(), key=lambda x: x[1])
        ],
    }


def teacher_payload(
    snapshot: Snapshot,
    index: TeacherIndex,
    teacher_id: str,
    start: date,
    days: int,
    generated: datetime,
    bells: dict[str, list[str]] | None = None,
) -> dict | None:
    """Расписание преподавателя. None, если такого в таблице нет."""
    name = index.names.get(teacher_id)
    if name is None:
        return None

    by_date = index.days(teacher_id)
    covered = set(snapshot.dates)

    out_days = []
    for offset in range(days):
        day = start + timedelta(days=offset)
        if day not in covered:
            continue
        lessons = []
        for entry in by_date.get(day, []):
            item = _lesson(entry.lesson)
            # Кому именно читается пара — то, чего нет в расписании группы.
            item["gr"] = entry.group_name
            # Преподаватель тут очевиден, его имя только занимает место.
            item.pop("t", None)
            lessons.append(item)
        out_days.append({"d": day.isoformat(), "l": lessons})

    payload = {
        "v": API_VERSION,
        "g": teacher_id,
        "gn": name,
        "kind": "teacher",
        "gen": _iso(generated),
        "src": snapshot.sheet_title,
        "days": out_days,
    }
    coverage = snapshot.coverage
    if coverage:
        payload["cov"] = [coverage[0].isoformat(), coverage[1].isoformat()]
    if bells:
        payload["bells"] = bells
    return payload


def groups_payload(snapshot: Snapshot, generated: datetime) -> dict:
    return {
        "v": API_VERSION,
        "gen": _iso(generated),
        "groups": [{"id": g.id, "name": g.name} for g in snapshot.groups],
    }


def schedule_payload(
    snapshot: Snapshot,
    group_id: str,
    start: date,
    days: int,
    generated: datetime,
    bells: dict[str, list[str]] | None = None,
) -> dict | None:
    """Тело для виджета. None, если такой группы в листе нет."""
    group = next((g for g in snapshot.groups if g.id == group_id), None)
    if group is None:
        return None

    by_date = snapshot.schedule.get(group_id, {})
    covered = set(snapshot.dates)

    out_days = []
    for offset in range(days):
        day = start + timedelta(days=offset)
        if day not in covered:
            # Дня нет в ответе вовсе — виджет отличит «пар нет» от
            # «расписание ещё не опубликовано».
            continue
        out_days.append({
            "d": day.isoformat(),
            "l": [_lesson(x) for x in by_date.get(day, [])],
        })

    payload = {
        "v": API_VERSION,
        "g": group.id,
        "gn": group.name,
        "gen": _iso(generated),
        "src": snapshot.sheet_title,
        "days": out_days,
    }
    coverage = snapshot.coverage
    if coverage:
        payload["cov"] = [coverage[0].isoformat(), coverage[1].isoformat()]
    if bells:
        payload["bells"] = bells
    return payload


def meta_payload(
    snapshot: Snapshot, generated: datetime, status: str, checked: datetime | None
) -> dict:
    out = {
        "v": API_VERSION,
        "gen": _iso(generated),
        "src": snapshot.sheet_title,
        "groups": len(snapshot.groups),
        "status": status,
    }
    coverage = snapshot.coverage
    if coverage:
        out["cov"] = [coverage[0].isoformat(), coverage[1].isoformat()]
    if checked:
        out["checked"] = _iso(checked)
    return out


def _iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )
