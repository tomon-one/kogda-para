"""Календарный график: сессия, каникулы и практика по неделям.

Лист «Календарный график» колледжа — строки групп, столбцы недель: сверху
месяц, номер недели и диапазон дат («7-13»). В клетке — что у группы на этой
неделе: пусто — обычное обучение, `::` — сессия, `=` — каникулы, `УП` и `ПП` —
практика. Остальное (`Дфз` — зачёт на обычном занятии, `Х`, `Δ`, `III`, `*`)
днём не помечаем: в приложении от этого зависело бы «пар нет», а оно неверно.

Строка — диапазон групп («БП-926/1-4»), раскрываем по списку групп из
расписания. Незнакомый знак или группа не в списке — в журнал и мимо: график
не может валить расписание.
"""

from __future__ import annotations

import csv
import datetime as dt
import io
import logging
import re
from dataclasses import dataclass

log = logging.getLogger(__name__)

SESSION = "session"
HOLIDAYS = "holidays"
PRACTICE = "practice"

# Что пишет колледж → состояние дня. Пустая клетка — обычное обучение.
MARKS: dict[str, str] = {"::": SESSION, "=": HOLIDAYS, "УП": PRACTICE, "ПП": PRACTICE}

# Не состояние дня: зачёт на последнем занятии и пометки колледжа. Пишем в журнал
# один раз за знак — чтобы новый знак однажды заметили, а не гадали, почему пусто.
NOT_A_STATE = {"Дфз", "Х", "Δ", "III", "*"}

_MONTHS = {
    "январ": 1,
    "феврал": 2,
    "март": 3,
    "апрел": 4,
    "май": 5,
    "июн": 6,
    "июл": 7,
    "август": 8,
    "сентябр": 9,
    "октябр": 10,
    "ноябр": 11,
    "декабр": 12,
}

# «БП-926/1-4» — группы с 1-й по 4-ю одного курса.
_RANGE = re.compile(r"^(?P<base>.+)-(?P<course>\d+)/(?P<first>\d+)-(?P<last>\d+)$")
_DIGITS = re.compile(r"\d")

# Сколько знаков запомнить как «уже сказали»: лист небольшой, но живёт месяцами.
_seen_marks: set[str] = set()


@dataclass(frozen=True)
class Week:
    """Столбец листа: неделя с `start` по `end` включительно (первая бывает неполной)."""

    number: int
    start: dt.date
    end: dt.date


def _month_of(cell: str) -> int | None:
    text = cell.strip().lower()
    if not text:
        return None
    for name, number in _MONTHS.items():
        if text.startswith(name):
            return number
    return None


def _date(month: int, day: int, academic_start: int) -> dt.date:
    """День месяца в учебном году: сентябрь–декабрь — его начало, январь–август — следующий год."""
    year = academic_start if month >= 9 else academic_start + 1
    return dt.date(year, month, day)


def _academic_start(today: dt.date | None) -> int:
    day = today or dt.date.today()
    # Учебный год начинается в сентябре; в июле–августе лист ещё прошлогодний.
    return day.year if day.month >= 8 else day.year - 1


def week_columns(rows: list[list[str]], today: dt.date | None = None) -> list[tuple[int, Week]]:
    """Столбцы листа с неделями: номер столбца и неделя. Ищем по подписям, а не по номерам строк."""
    numbers: list[tuple[int, int]] = []
    dates: list[tuple[int, str]] = []
    months: list[tuple[int, int]] = []
    for row in rows[:8]:
        for column, cell in enumerate(row):
            text = cell.strip()
            if not text:
                continue
            if text.lower().startswith("месяц"):
                continue
            month = _month_of(text)
            if month is not None:
                months.append((column, month))
                continue
            if re.fullmatch(r"\d{1,2}", text) and column > 0:
                numbers.append((column, int(text)))
                continue
            if re.fullmatch(r"\d{1,2}\s*-\s*\d{1,2}", text):
                dates.append((column, text.replace(" ", "")))
    if not dates:
        return []

    academic = _academic_start(today)
    # Месяц столбца — ближайший заголовок месяца слева от него.
    month_at: dict[int, int] = {}
    current: int | None = None
    for column in range(max(column for column, _ in dates) + 1):
        for at, month in months:
            if at == column:
                current = month
        if current is not None:
            month_at[column] = current

    by_column = dict(numbers)
    out: list[tuple[int, Week]] = []
    for column, text in dates:
        first, last = (int(part) for part in text.split("-"))
        month = month_at.get(column)
        if month is None:
            continue
        # Месяц в заголовке — тот, в котором неделя кончается: «31-6» под «июнь».
        end = _date(month, last, academic)
        if first <= last:
            start = end.replace(day=first)
        else:
            # Неделя началась в прошлом месяце: «28-3» под «январь».
            before = _date(month, 1, academic) - dt.timedelta(days=1)
            start = before - dt.timedelta(days=max(0, before.day - first))
        out.append((column, Week(number=by_column.get(column, 0), start=start, end=end)))
    return out


def weeks(rows: list[list[str]], today: dt.date | None = None) -> list[Week]:
    """Только недели, без номеров столбцов — для проверок."""
    return [week for _, week in week_columns(rows, today)]


def expand(designation: str, known: set[str]) -> list[str]:
    """«БП-926/1-4» → БП-926/1 … БП-926/4, но только те, что есть в расписании."""
    name = designation.strip()
    if not name or not _DIGITS.search(name):
        return []
    if name in known:
        return [name]
    match = _RANGE.match(name)
    if match is None:
        return []
    base, course = match.group("base"), match.group("course")
    first, last = int(match.group("first")), int(match.group("last"))
    if first > last or last - first > 20:
        return []
    return [
        group
        for number in range(first, last + 1)
        if (group := f"{base}-{course}/{number}") in known
    ]


def _rows(csv_text: str) -> list[list[str]]:
    return [row for row in csv.reader(io.StringIO(csv_text))]


def parse(
    csv_text: str,
    known: set[str],
    today: dt.date | None = None,
) -> dict[str, dict[str, str]]:
    """Группа → день (ISO) → состояние. Незнакомые знаки — в журнал, день не помечен."""
    rows = _rows(csv_text)
    columns = week_columns(rows, today)
    if not columns:
        log.warning("график: в листе нет недель — пропускаю")
        return {}
    out: dict[str, dict[str, str]] = {}
    unknown_groups = 0
    for row in rows:
        if not row:
            continue
        designation = row[0].strip()
        groups = expand(designation, known)
        if not groups:
            if designation and not _looks_like_header(designation):
                unknown_groups += 1
            continue
        for column, week in columns:
            cell = row[column].strip() if column < len(row) else ""
            if not cell:
                continue
            state = MARKS.get(cell)
            if state is None:
                _note_once(cell)
                continue
            day = week.start
            while day <= week.end:
                for group in groups:
                    out.setdefault(group, {})[day.isoformat()] = state
                day += dt.timedelta(days=1)
    if unknown_groups:
        log.info("график: %d строк не нашлось в расписании", unknown_groups)
    return out


def _looks_like_header(text: str) -> bool:
    lower = text.lower()
    return "курс" in lower or lower in {"месяц", "№ недели", "дата"}


def _note_once(mark: str) -> None:
    # Длинная фраза — пояснение колледжа («зачёты на последних занятиях»), а не знак.
    if mark in NOT_A_STATE or len(mark) > 8:
        return
    if mark in _seen_marks:
        return
    _seen_marks.add(mark)
    log.warning("график: незнакомый знак %r — день не помечен", mark)
