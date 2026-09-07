"""Обход листа расписания: строки таблицы -> Snapshot.

Принимает уже разобранные строки, а не текст CSV: тот же код читает и лист,
скачанный через gviz, и лист, вынутый из xlsx (когда имя листа обрезано и
обратиться к нему по имени нельзя).
"""

from __future__ import annotations

import csv
import io
import logging
import re
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date

from ..domain.models import Lesson, Snapshot, SourceFormatChanged
from .cells import parse_lesson
from .groups import build_column_map, find_header_rows

log = logging.getLogger(__name__)

_DATE_RE = re.compile(r"^(\d{2})\.(\d{2})\.(\d{4})\s+(\S+)")
_LESSON_NO_RE = re.compile(r"^([1-9])$")

_WEEKDAYS = (
    "понедельник", "вторник", "среда",
    "четверг", "пятница", "суббота", "воскресенье",
)

@dataclass(frozen=True)
class Limits:
    """Границы правдоподобия листа. Не сошлось — значит таблицу переделали."""

    min_groups: int = 100
    min_dates: int = 5
    min_lessons: int = 500


FULL_SHEET = Limits()
# Для фикстур из нескольких групп: структура та же, объём другой.
FIXTURE = Limits(min_groups=3, min_dates=2, min_lessons=5)


def read_csv(text: str) -> list[list[str]]:
    """Разбирает CSV. Кавычки и переводы строк внутри ячеек — забота модуля csv."""
    return list(csv.reader(io.StringIO(text.lstrip("﻿"))))


def _parse_date(cell: str) -> date | None:
    """'02.09.2026 среда' -> date. День недели служит только сверкой."""
    m = _DATE_RE.match((cell or "").replace("\xa0", " ").strip())
    if not m:
        return None
    day, month, year, weekday = m.groups()
    value = date(int(year), int(month), int(day))
    expected = _WEEKDAYS[value.weekday()]
    if weekday.lower() != expected:
        log.warning(
            "в таблице %s помечен как %s, а это %s — верю числу",
            value, weekday, expected,
        )
    return value


def _cell(row: list[str], col: int) -> str:
    return row[col] if col < len(row) else ""


def parse_sheet(
    rows: Iterable[list[str]], sheet_title: str, limits: Limits = FULL_SHEET
) -> Snapshot:
    rows = [list(r) for r in rows]
    groups = build_column_map(rows, limits.min_groups)
    skip = find_header_rows(rows, groups, limits.min_groups)

    snapshot = Snapshot(sheet_title=sheet_title, groups=groups)
    for group in groups:
        snapshot.schedule.setdefault(group.id, {})

    current: date | None = None
    seen_dates: list[date] = []

    for i, row in enumerate(rows):
        if i in skip:
            continue

        found = _parse_date(_cell(row, 0))
        if found is not None:
            current = found
            if current not in seen_dates:
                seen_dates.append(current)

        m = _LESSON_NO_RE.match(_cell(row, 1).strip())
        if not m:
            continue
        if current is None:
            raise SourceFormatChanged(f"пара в строке {i} раньше первой даты")
        number = int(m.group(1))

        # Строка под парой отдана преподавателям — но только если это
        # действительно она, а не начало следующей пары.
        teacher_row: list[str] = []
        nxt = i + 1
        if nxt < len(rows) and nxt not in skip:
            candidate = rows[nxt]
            if not _LESSON_NO_RE.match(_cell(candidate, 1).strip()):
                teacher_row = candidate

        for group in groups:
            lesson = parse_lesson(
                number=number,
                subject_raw=_cell(row, group.column),
                room_raw=_cell(row, group.column + 3),
                teacher_raw=_cell(teacher_row, group.column) if teacher_row else "",
            )
            if lesson is not None:
                snapshot.schedule[group.id].setdefault(current, []).append(lesson)

    for by_date in snapshot.schedule.values():
        for day, lessons in by_date.items():
            lessons.sort(key=lambda x: x.number)

    snapshot.dates = sorted(seen_dates)
    _validate(snapshot, seen_dates, limits)
    return snapshot


def _validate(snapshot: Snapshot, seen_order: list[date], limits: Limits) -> None:
    """Проверяет, что разобранное похоже на расписание, а не на обломки."""
    if len(snapshot.dates) < limits.min_dates:
        raise SourceFormatChanged(
            f"нашёл всего {len(snapshot.dates)} дней, ожидал не меньше {limits.min_dates}"
        )
    if seen_order != sorted(seen_order):
        raise SourceFormatChanged(f"даты в листе идут не по возрастанию: {seen_order}")
    total = snapshot.total_lessons()
    if total < limits.min_lessons:
        raise SourceFormatChanged(
            f"нашёл всего {total} пар, ожидал не меньше {limits.min_lessons}"
        )


def parse_csv(text: str, sheet_title: str, limits: Limits = FULL_SHEET) -> Snapshot:
    return parse_sheet(read_csv(text), sheet_title, limits)
