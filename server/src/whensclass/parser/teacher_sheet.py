"""Лист преподавателей колледжа: неделя, строка — преподаватель, блок колонок — день.

Колледж выгружает его отдельно от листа групп, из той же системы, откуда
вставляет недели в лист групп. Поэтому он годится для сверки: пара в нём
есть — у названной группы она должна быть и у нас. Обратное не доказывает
ничего: лекцию на поток лист записывает одной, первой группой.

Раскладка (19.10.2026): строка «Преподаватель | Пара | Время | <дата> …»,
у каждого дня четыре колонки — «Дисциплина», две пустые, «Ауд.»; пара — две
строки: предмет с номером и аудиторией, под ним группа. Имя преподавателя —
в первой колонке у первой пары.
"""

from __future__ import annotations

import csv
import datetime as dt
import io
import re
from dataclasses import dataclass

from ..domain.models import SourceFormatChanged

_DATE = re.compile(r"(\d{2})\.(\d{2})\.(\d{4})")


@dataclass(frozen=True)
class TeacherPair:
    teacher: str
    day: dt.date
    number: int
    subject: str
    room: str
    group: str


def parse_teacher_sheet(text: str) -> list[TeacherPair]:
    rows = list(csv.reader(io.StringIO(text.lstrip("﻿"))))
    head = next((i for i, r in enumerate(rows) if r and r[0].strip() == "Преподаватель"), None)
    if head is None:
        raise SourceFormatChanged("лист преподавателей: нет строки «Преподаватель | Пара | …»")
    days = []
    for col, cell in enumerate(rows[head]):
        if m := _DATE.match(cell.strip()):
            days.append((col, dt.date(int(m[3]), int(m[2]), int(m[1]))))
    if not days:
        raise SourceFormatChanged("лист преподавателей: в заголовке нет дат")

    def cell(row: list[str], col: int) -> str:
        return " ".join(row[col].split()) if col < len(row) else ""

    out: list[TeacherPair] = []
    teacher = ""
    for i in range(head + 1, len(rows)):
        row = rows[i]
        if cell(row, 0):
            teacher = cell(row, 0)
        number = cell(row, 1)
        if not teacher or not number.isdigit():
            continue
        below = rows[i + 1] if i + 1 < len(rows) else []
        for col, day in days:
            subject = cell(row, col)
            if subject:
                out.append(TeacherPair(teacher, day, int(number), subject, cell(row, col + 3), cell(below, col)))
    return out


def sheet_week(title: str, year: int) -> tuple[dt.date, dt.date] | None:
    """«расписание преподавателей 19.10-24.10» -> даты недели; не такое имя — None."""
    m = re.search(r"преподавател\w*\s+(\d{1,2})\.(\d{1,2})\s*[-–]\s*(\d{1,2})\.(\d{1,2})", title, re.IGNORECASE)
    if not m:
        return None
    try:
        return dt.date(year, int(m[2]), int(m[1])), dt.date(year, int(m[4]), int(m[3]))
    except ValueError:
        return None
