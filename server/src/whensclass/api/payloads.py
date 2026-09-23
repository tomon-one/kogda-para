"""Сборка тел ответов API.

Ключи короткие намеренно: ответ уезжает на телефон и разбирается виджетом при
каждом обновлении. Правило про отсутствующие поля — в docs/api.md: пустого
поля в JSON нет вовсе, чтобы не гонять null-ы.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

from ..config import settings
from ..domain.models import Lesson, SheetPlace, Snapshot
from ..domain.teachers import TeacherIndex

API_VERSION = 1


def a1_column(column: int) -> str:
    """Номер колонки с нуля -> буквы, как в Sheets: 0 -> A, 26 -> AA, 664 -> YO."""
    letters = ""
    n = column + 1
    while n:
        n, rem = divmod(n - 1, 26)
        letters = chr(65 + rem) + letters
    return letters


def sheet_url(gid: str | None = None) -> str:
    """Адрес таблицы колледжа — с листом, если известно, на каком мы.

    Адрес отсюда, а не из APK: переедет таблица — переживём правкой
    настроек, а не сборкой. Приложение дописывает к нему `&range=<колонка><строка>`
    из `col` и `row` того же ответа, и Sheets подводит к ячейке.
    """
    base = f"https://docs.google.com/spreadsheets/d/{settings.spreadsheet_id}/edit"
    return f"{base}#gid={gid}" if gid else base


def _place_days(
    snapshot: Snapshot, start: date, days: int
) -> tuple[str | None, dict[date, SheetPlace]]:
    """Лист окна и строки его дней.

    Окно может перешагнуть границу листа, а `range` в ссылке относится к
    одному листу: строки отдаём только для дней с того листа, на который
    ведёт ссылка, — с первого покрытого дня окна.
    """
    places = snapshot.places
    window = [start + timedelta(days=offset) for offset in range(days)]
    gid = next((places[d].gid for d in window if d in places and places[d].gid), None)
    if gid is None:
        return None, {}
    return gid, {d: places[d] for d in window if d in places and places[d].gid == gid}


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
    known_name: str | None = None,
) -> dict | None:
    """Расписание преподавателя. None, если такого в таблице нет.

    `known_name` — имя преподавателя, которого в этом снимке нет, но который
    был в прошлых: ему отвечаем днями без пар, а не 404.
    """
    name = index.names.get(teacher_id) or known_name
    if name is None:
        return None

    by_date = index.days(teacher_id)
    covered = set(snapshot.dates)
    gid, placed = _place_days(snapshot, start, days)

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
            if entry.column is not None:
                # У преподавателя каждая пара в своей колонке — колонке группы.
                item["col"] = a1_column(entry.column)
            lessons.append(item)
        out_day = {"d": day.isoformat(), "l": lessons}
        if day in placed:
            out_day["row"] = placed[day].row
        out_days.append(out_day)

    payload = {
        "v": API_VERSION,
        "g": teacher_id,
        "gn": name,
        "kind": "teacher",
        "gen": _iso(generated),
        "src": snapshot.sheet_title,
        "days": out_days,
    }
    if gid:
        payload["src_url"] = sheet_url(gid)
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
    gid, placed = _place_days(snapshot, start, days)

    out_days = []
    for offset in range(days):
        day = start + timedelta(days=offset)
        if day not in covered:
            # Дня нет в ответе вовсе — виджет отличит «пар нет» от
            # «расписание ещё не опубликовано».
            continue
        out_day = {
            "d": day.isoformat(),
            "l": [_lesson(x) for x in by_date.get(day, [])],
        }
        if day in placed:
            out_day["row"] = placed[day].row
        out_days.append(out_day)

    payload = {
        "v": API_VERSION,
        "g": group.id,
        "gn": group.name,
        "gen": _iso(generated),
        "src": snapshot.sheet_title,
        # Колонка группы в листе, на который ведёт ссылка: вместе с `row` дня
        # даёт ячейку, к которой ссылка «открыть таблицу» подводит человека.
        "col": a1_column(snapshot.column_of(group, gid=gid)),
        "days": out_days,
    }
    if gid:
        payload["src_url"] = sheet_url(gid)
    coverage = snapshot.coverage
    if coverage:
        payload["cov"] = [coverage[0].isoformat(), coverage[1].isoformat()]
    if bells:
        payload["bells"] = bells
    return payload


def meta_payload(
    snapshot: Snapshot,
    generated: datetime,
    status: str,
    checked: datetime | None,
    today: date | None = None,
    failing_since: datetime | None = None,
    error: str | None = None,
) -> dict:
    # Куда идти, когда мы подвели: на лист, где лежит сегодняшний день, а
    # без него — просто в книгу. Ближайший известный день годится тоже:
    # в воскресенье это завтрашний понедельник.
    place = None
    if today is not None and snapshot.places:
        place = snapshot.places.get(today) or min(
            snapshot.places.items(), key=lambda item: abs((item[0] - today).days)
        )[1]
    out = {
        "v": API_VERSION,
        "gen": _iso(generated),
        "src": snapshot.sheet_title,
        "groups": len(snapshot.groups),
        "status": status,
        "src_url": sheet_url(place.gid if place else None),
    }
    coverage = snapshot.coverage
    if coverage:
        out["cov"] = [coverage[0].isoformat(), coverage[1].isoformat()]
    if checked:
        out["checked"] = _iso(checked)
    if status != "ok":
        # С какого часа и почему: двое суток stale не должны выглядеть как
        # минута. Текст ошибки уже без адресов — его чистит refresher.
        if failing_since:
            out["since"] = _iso(failing_since)
        if error:
            out["err"] = error
    return out


def _iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )
