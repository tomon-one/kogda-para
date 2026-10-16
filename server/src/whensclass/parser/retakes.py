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
import datetime as dt
import io
import re
from dataclasses import dataclass, field

from ..domain.models import SourceFormatChanged


@dataclass
class Slot:
    """Дни пересдачи: с `start` по `end` включительно (один день — равны) и время."""

    start: dt.date
    end: dt.date
    time: str


@dataclass
class Retake:
    teacher: str
    dates: str
    time: str
    room: str
    # Даты, разобранные из текста; пусто — текст не разобрался, показывать как есть.
    slots: list[Slot] = field(default_factory=list)


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
_MONTHS = ("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")


def _anchor(title: str, today: dt.date) -> dt.date:
    """От какого дня считать год дат без года: месяц и год из заголовка листа
    («График пересдач, октябрь 2026 г.»), если они там есть, иначе сегодня.
    Иначе лист, пролежавший полгода, «переехал» бы в следующий год."""
    year = re.search(r"\b(20\d{2})\b", title)
    if not year:
        return today
    words = re.findall(r"[а-яё]+", title.lower())
    month = next(
        (i + 1 for i, stem in enumerate(_MONTHS) for w in words if w.startswith(stem) and (stem != "ма" or w in ("май", "мая"))),
        None,
    )
    return dt.date(int(year[1]), month or 7, 15 if month else 1)


def _clean(cell: str) -> str:
    return " ".join((cell or "").split())


def _names(cell: str) -> list[str]:
    """«Лапенко Н.А., Оленйник, Пьянкова,Фомин» -> четыре имени."""
    return [part for part in (_clean(p) for p in cell.split(",")) if part]


# Год у даты бывает («22.10.2026»): тогда он и берётся, а не ближайший.
_YEAR = r"(?:\.(\d{4}|\d{2}))?"
_RANGE = re.compile(r"(\d{1,2})\.?\s*[-–]\s*(\d{1,2})\.(\d{1,2})" + _YEAR + r"\.?")
_DAY = re.compile(r"(\d{1,2})\.(\d{1,2})" + _YEAR + r"\.?")
_CLOCK = re.compile(r"(\d{1,2})[.:](\d{2})\s*[-–]\s*(\d{1,2})[.:](\d{2})")
_ONE_CLOCK = re.compile(r"(\d{1,2})[.:](\d{2})")


def _clock(text: str) -> str:
    """«16.00-17.30» -> «16:00–17:30», «17.40» -> «17:40»; не время — как есть."""
    if m := _CLOCK.fullmatch(text.strip()):
        return f"{int(m[1])}:{m[2]}–{int(m[3])}:{m[4]}"
    if m := _ONE_CLOCK.fullmatch(text.strip()):
        return f"{int(m[1])}:{m[2]}"
    return text.strip()


def _day(day: int, month: int, today: dt.date, year: str | None = None) -> dt.date | None:
    """День без года — ближайший к сегодня: в декабре «14.01» — январь
    следующего года, в январе «22.12» — прошлого. С годом — он."""
    if year:
        try:
            return dt.date(int(year) if len(year) == 4 else 2000 + int(year), month, day)
        except ValueError:
            return None
    found = []
    for year in (today.year - 1, today.year, today.year + 1):
        try:
            found.append(dt.date(year, month, day))
        except ValueError:
            pass
    return min(found, key=lambda d: abs((d - today).days), default=None)


def parse_slots(dates: str, time: str, today: dt.date) -> list[Slot]:
    """«14.10; 16.10» и «10.00-14.30; 9.30-17.30» -> два дня, каждый со своим временем.

    Дат через «;» столько же, сколько времён, — идут парами; время одно — у
    всех дат; иначе у каждой даты все времена. Хоть одна дата не разобралась —
    пусто: половина разобранного хуже текста как есть.
    """
    parts = [p.strip() for p in dates.split(";") if p.strip()]
    times = [_clock(t) for t in time.split(";") if t.strip()]
    spans: list[tuple[dt.date, dt.date]] = []
    for part in parts:
        if m := _RANGE.fullmatch(part):
            start = _day(int(m[1]), int(m[3]), today, m[4])
            try:
                end = start.replace(day=int(m[2])) if start else None
            except ValueError:
                end = None
        elif m := _DAY.fullmatch(part):
            start = end = _day(int(m[1]), int(m[2]), today, m[3])
        else:
            return []
        if start is None or end is None or end < start:
            return []
        spans.append((start, end))
    if len(times) == len(spans):
        paired = times
    else:
        paired = [", ".join(times)] * len(spans)
    return [Slot(start, end, t) for (start, end), t in zip(spans, paired)]


def parse_retakes(text: str, today: dt.date | None = None) -> Retakes:
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

    anchor = _anchor(title, today or dt.date.today())
    retakes: list[Retake] = []
    i = start + 1
    while i < len(rows):
        row = rows[i]
        teacher = cell(row, "teacher")
        # Вторая таблица начинается строкой «Обращаться к …» без преподавателя;
        # то же слово в датах или кабинете строки с преподавателем — её текст.
        if not teacher and any(_REDIRECTS.search(v) for v in row if v):
            break
        if teacher:
            dates, time = cell(row, "dates"), cell(row, "time")
            retakes.append(Retake(
                teacher.rstrip(","), dates, time, cell(row, "room"),
                parse_slots(dates, time, anchor),
            ))
        i += 1
    # Заголовок есть, а строк нет — таблицу очистили: пересдач нет, это не сбой.

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
