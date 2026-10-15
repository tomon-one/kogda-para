"""Лист пересдач: кто из преподавателей когда и где принимает долги.

Колледж ведёт его вручную, по преподавателям, без групп и предметов: даты,
время и кабинет — свободным текстом («12-23.10», «в течение дня», «по
расписанию занятий»). Текст отдаётся как есть: любое толкование здесь
разошлось бы с тем, что человек увидит, открыв таблицу.

Под основной таблицей — вторая: у кого из преподавателей долги принимает
другой («обращаться к … по расписанию»). Она нужна поиску: человек ищет того,
у кого должен, а принимать будет другой.
"""

from __future__ import annotations

import csv
import io
import re
from dataclasses import dataclass, field

from ..domain.models import SourceFormatChanged


@dataclass
class Retake:
    teacher: str
    dates: str
    time: str
    room: str


@dataclass
class Redirect:
    """Долги преподавателей `teachers` принимает `to` (человек или кабинет)."""

    to: str
    teachers: list[str] = field(default_factory=list)


@dataclass
class Retakes:
    title: str
    rows: list[Retake]
    redirects: list[Redirect]


_HEADS = {
    "teacher": re.compile(r"фио|преподавател", re.IGNORECASE),
    "dates": re.compile(r"дата", re.IGNORECASE),
    "time": re.compile(r"время", re.IGNORECASE),
    "room": re.compile(r"кабинет|ауд", re.IGNORECASE),
}
_REDIRECTS = re.compile(r"обращаться", re.IGNORECASE)
_TITLE = re.compile(r"пересдач|задолженност", re.IGNORECASE)


def _clean(cell: str) -> str:
    return " ".join((cell or "").split())


def _names(cell: str) -> list[str]:
    """«Лапенко Н.А., Оленйник, Пьянкова,Фомин» -> четыре имени."""
    return [part for part in (_clean(p) for p in cell.split(",")) if part]


def parse_retakes(text: str) -> Retakes:
    rows = [[_clean(c) for c in row] for row in csv.reader(io.StringIO(text.lstrip("﻿")))]

    head = None
    for i, row in enumerate(rows):
        cols = {}
        for key, pattern in _HEADS.items():
            col = next((c for c, v in enumerate(row) if v and pattern.search(v)), None)
            if col is not None:
                cols[key] = col
        if len(cols) == len(_HEADS):
            head = i, cols
            break
    if head is None:
        raise SourceFormatChanged("лист пересдач: не нашёл заголовка «ФИО преподавателя / Дата / Время / Кабинет»")
    start, cols = head

    def cell(row: list[str], key: str) -> str:
        col = cols[key]
        return row[col] if col < len(row) else ""

    title = next(
        (v for row in rows[:start] for v in row if v and _TITLE.search(v)),
        "Пересдачи",
    )

    retakes: list[Retake] = []
    i = start + 1
    while i < len(rows) and not any(_REDIRECTS.search(v) for v in rows[i] if v):
        row = rows[i]
        teacher = cell(row, "teacher")
        if teacher:
            retakes.append(Retake(teacher, cell(row, "dates"), cell(row, "time"), cell(row, "room")))
        i += 1
    if not retakes:
        raise SourceFormatChanged("лист пересдач: под заголовком нет ни одной строки")

    # Вторая таблица: номер, кому обращаться (колонка ФИО), чьи долги — в
    # колонке дат, по строке на имя или через запятую в одной.
    redirects: list[Redirect] = []
    for row in rows[i + 1:]:
        to, whose = cell(row, "teacher"), cell(row, "dates")
        if to:
            redirects.append(Redirect(to))
        if whose and redirects:
            redirects[-1].teachers.extend(_names(whose))
    redirects = [r for r in redirects if r.teachers]
    return Retakes(title, retakes, redirects)
