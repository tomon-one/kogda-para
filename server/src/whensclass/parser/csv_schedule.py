"""Обход листа расписания: строки таблицы -> Snapshot.

Принимает уже разобранные строки, а не текст CSV. Разбор построен на форме,
которую давал gviz: шапка одной строкой «Дисциплина Преподаватель <группа>».
С 14 сентября 2026 лист берётся сырым экспортом, где шапка — три строки
столбиком; `collapse_export` приводит её к прежней форме, и обход остаётся
тем же.
"""

from __future__ import annotations

import csv
import io
import logging
import re
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, timedelta

from ..domain.models import Lesson, SheetPlace, Snapshot, SourceFormatChanged
from .cells import parse_lesson
from .groups import _HEADER_PREFIX, MIN_GROUPS, build_column_map, find_header_rows

log = logging.getLogger(__name__)

# Дату пишут руками, поэтому берём и «2.09.2026 среда», и «02.09.2026, среда»,
# и «07/09/2026», и «понедельник 07.09.2026», и запись без дня недели. День
# недели всё равно только сверяем, доверяя числу.
_DATE_RE = re.compile(r"(\d{1,2})[./](\d{1,2})[./](\d{2,4})")

# Ячейка с двумя числами через точку, дробь или дефис — это дата, которую
# не смогли прочитать, а не «нет даты». Молчать про такое нельзя: раньше
# день молча приклеивался к предыдущему.
_LOOKS_LIKE_DATE = re.compile(r"\d{1,2}\s*[.,/-]\s*\d{1,2}")
_LESSON_NO_RE = re.compile(r"^([1-9])$")
# Строка под парой несёт время звонка в колонке номера: «9-00-10.30».
_TIME_RE = re.compile(r"^\d{1,2}[.:-]\d{2}\s*[-–]\s*\d{1,2}[.:-]\d{2}$")
# Дальше этого лист смотреть не должен: опечатка «2027» в одной дате иначе
# делала бы лист покрывающим год вперёд.
MAX_DAYS_AHEAD = 60
# Сдвиг блока строк, под которым нет повторного заголовка, не видит ни одна
# сверка колонок: каждая группа молча получает пары соседа. Видно его по
# содержимому, и признак — именно «пары соседа», а не «незнакомые пары».
# В строке пары идём по группам в порядке колонок: группа «голосует» за сдвиг
# на k колонок, если её пару знает история соседа через k колонок, а её
# собственная — нет; группа, чью пару знает только своя история, серию рвёт.
# Отказ — если серия набрала SHIFT_RUN_REJECT голосов за один и тот же k.
#
# До 23 сентября 2026 отказ давала доля групп с незнакомыми парами, и она
# ошибалась в обе стороны (второй аудит, К1, В3, В5): сдвиг хвоста листа на
# 40–65 групп проходил, потому что соседи — часто подгруппы с общими
# лекциями, а у 74 групп из 189 история была «короткой» и не сравнивалась;
# зато неделя практики у курса, классный час у 70 групп, День здоровья или
# ещё не проставленные преподаватели отвергали лист как сдвиг. Признак соседа
# на живом листе 23.09.2026: сдвиги из находок — серии 17–63 голоса; законные
# правки — 0–1; все 194 честные версии листа за 13–23.09 — не больше 4
# (подгруппы ГД-926/1–4 с общими парами на второй день листа). Порог — вдвое
# выше честного максимума и вдвое ниже самой короткой атаки.
SHIFT_RUN_REJECT = 8
SHIFT_OFFSETS = (-2, -1, 1, 2)
# Прежняя доля незнакомых — теперь только запись в журнал: законные правки
# задевают её так же, как сдвиг.
SHIFT_WARN_SHARE = 0.25
SHIFT_ROW_WARN_SHARE = 0.35
SHIFT_MIN_GROUPS = 20
SHIFT_MIN_LESSONS_TODAY = 3
SHIFT_MIN_HISTORY = 10

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
    # Разрыв между соседними днями одного листа. Опечатка в месяце у одной
    # даты (14.10 вместо 14.09) даёт разрыв в месяц и растянула бы лист; а
    # зимние каникулы внутри листа (26.12 → 11.01) — шестнадцать дней, и с
    # прежним порогом в две недели лист отвергался целиком (второй аудит, М6).
    max_gap_days: int = 25


FULL_SHEET = Limits()
# Для фикстур из нескольких групп: структура та же, объём другой.
FIXTURE = Limits(min_groups=3, min_dates=2, min_lessons=5)


def read_csv(text: str) -> list[list[str]]:
    """Разбирает CSV. Кавычки и переводы строк внутри ячеек — забота модуля csv."""
    return list(csv.reader(io.StringIO(text.lstrip("﻿"))))


def _parse_date(cell: str) -> date | None:
    """'02.09.2026 среда' -> date. День недели служит только сверкой."""
    text = (cell or "").replace("\xa0", " ").strip()
    m = _DATE_RE.search(text)
    if not m:
        return None
    day, month, year = m.groups()
    # День недели — любое слово рядом с датой, до или после неё.
    words = [w.strip(" ,.;") for w in (text[: m.start()] + " " + text[m.end():]).split()]
    weekday = next((w for w in words if w.lower() in _WEEKDAYS), None)
    number = int(year)
    if number < 100:
        # «02.09.26» тоже встречается: век дописываем сами.
        number += 2000
    try:
        value = date(number, int(month), int(day))
    except ValueError:
        # Числа есть, а даты не выходит — «32.13.2026». Не наше дело гадать,
        # что имелось в виду: пусть обход решает, что формат поехал.
        return None
    if weekday is None:
        # День недели пишут не всегда. Он всё равно только сверка.
        return value
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
    rows: Iterable[list[str]],
    sheet_title: str,
    limits: Limits = FULL_SHEET,
    around: date | None = None,
    sheet_rows: list[int] | None = None,
) -> Snapshot:
    """`around` — сегодняшний день: дальше MAX_DAYS_AHEAD от него дат не ждём.

    `sheet_rows[i]` — номер строки листа, как его видит человек в Sheets, для
    строки `i` после схлопывания шапки. С ним сообщения разбора ведут в ту
    строку, что открывается в таблице: 20 сентября 2026 журнал писал «строка
    202», а в Sheets это была 214-я (раздел 7 docs/hardening-2026-09-14.md).
    """
    rows = [list(r) for r in rows]

    def where(i: int) -> str:
        if sheet_rows is not None and i < len(sheet_rows):
            return f"строке {sheet_rows[i]} листа"
        return f"строке {i}"

    groups = build_column_map(rows, limits.min_groups)
    skip = find_header_rows(rows, groups, limits.min_groups, where=where)

    snapshot = Snapshot(sheet_title=sheet_title, groups=groups)
    for group in groups:
        snapshot.schedule.setdefault(group.id, {})

    current: date | None = None
    seen_dates: list[date] = []
    # Порядок дат, как они встретились, без дедупликации: она и прятала
    # нарушение порядка. Дата, встреченная второй раз, в seen_dates не
    # попадала, и проверка «даты идут по возрастанию» её не видела.
    date_order: list[date] = []
    # Где в листе стоит каждая дата из date_order — чтобы отказ называл
    # строку, а не пересказывал весь столбец дат.
    date_where: list[str] = []

    # Номера пар каждого дня: обязаны идти 1, 2, 3… без пропусков и
    # повторов. Повтор даты (скопированный блок) и номер не по шаблону
    # («3 пара», пропущенная строка) ловятся здесь же.
    numbers_by_date: dict[date, list[int]] = {}

    for i, row in enumerate(rows):
        if i in skip:
            continue

        cell = _cell(row, 0)
        found = _parse_date(cell)
        if found is None and cell.strip():
            if _LOOKS_LIKE_DATE.search(cell):
                # Раньше такая ячейка просто не узнавалась: current оставался
                # на прошлом дне, и весь новый день дописывался к предыдущему —
                # с повторяющимися номерами пар и без единой жалобы.
                raise SourceFormatChanged(f"дата в {where(i)} не разобралась: {cell!r}")
            # Слово без чисел — «понедельник» над датой, «неделя 3»: не дата.
            log.info("в колонке дат в %s не дата: %r — пропускаю", where(i), cell.strip())
        if found is not None:
            if around is not None and found > around + timedelta(days=MAX_DAYS_AHEAD):
                raise SourceFormatChanged(
                    f"дата {found} в {where(i)} дальше {MAX_DAYS_AHEAD} дней от {around}"
                )
            if found != current:
                date_order.append(found)
                date_where.append(where(i))
            current = found
            if current not in seen_dates:
                seen_dates.append(current)

        number_cell = _cell(row, 1).strip()
        m = _LESSON_NO_RE.match(number_cell)
        if not m:
            if number_cell and not _TIME_RE.match(number_cell):
                # В колонке номеров бывают только номера и время звонка.
                # «1-2», «кл. час», «3 пара» — новый способ записи, и что
                # с ним делать, должен решить человек, а не пропуск.
                raise SourceFormatChanged(
                    f"в колонке номеров пар в {where(i)} стоит {number_cell!r}"
                )
            continue
        if current is None:
            raise SourceFormatChanged(f"пара в {where(i)} раньше первой даты")
        number = int(m.group(1))
        if number == 1 and found is None and numbers_by_date.get(current):
            # С первой пары начинается новый день, а даты у него нет. Раньше
            # такой день молча дописывался к предыдущему, и отказ приходил
            # окольно — через номера пар соседнего дня: 20 сентября 2026 вместо
            # «21.09.2026 понедельник» стояла запятая, а err говорил про
            # субботу (раздел 7 docs/hardening-2026-09-14.md, задача 10).
            raise SourceFormatChanged(
                f"в {where(i)} начинается новый день (1-я пара), а даты нет: "
                f"{cell.strip()!r} — после {current}"
            )
        expected = len(numbers_by_date.setdefault(current, [])) + 1
        if number != expected:
            raise SourceFormatChanged(
                f"номера пар {current} идут {numbers_by_date[current] + [number]}: "
                f"ждал {expected}"
            )
        numbers_by_date[current].append(number)

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
    _validate(snapshot, date_order, limits, date_where)
    return snapshot


def _validate(
    snapshot: Snapshot,
    seen_order: list[date],
    limits: Limits,
    where: list[str] | None = None,
) -> None:
    """Проверяет, что разобранное похоже на расписание, а не на обломки."""
    if len(snapshot.dates) < limits.min_dates:
        raise SourceFormatChanged(
            f"нашёл всего {len(snapshot.dates)} дней, ожидал не меньше {limits.min_dates}"
        )
    for k, (a, b) in enumerate(zip(seen_order, seen_order[1:])):
        if b <= a:
            # Первое нарушение, со строкой листа. Раньше в тревогу уходил
            # весь столбец дат списком из тридцати datetime.date(…), и
            # искать в нём сломанное место приходилось глазами (24 сентября
            # 2026: колледж собирал новую неделю из скопированных блоков, и
            # под 05.10 осталась дата 26.09 от копии).
            place = f" в {where[k + 1]}" if where and k + 1 < len(where) else ""
            raise SourceFormatChanged(
                f"даты в листе идут не по возрастанию: {b:%d.%m.%Y}{place} "
                f"стоит после {a:%d.%m.%Y}"
            )
    gaps = [
        (a, b) for a, b in zip(seen_order, seen_order[1:]) if (b - a).days > limits.max_gap_days
    ]
    if gaps:
        # Опечатка в месяце у одной даты: остальные проверки её пропустят,
        # а `covering` растянет лист на месяц.
        raise SourceFormatChanged(
            f"между {gaps[0][0]} и {gaps[0][1]} больше {limits.max_gap_days} дней"
        )
    total = snapshot.total_lessons()
    if total < limits.min_lessons:
        raise SourceFormatChanged(
            f"нашёл всего {total} пар, ожидал не меньше {limits.min_lessons}"
        )
    _check_shift(snapshot)


def _trace(lesson: Lesson) -> tuple[str, tuple[str, ...]]:
    return " ".join(lesson.subject.lower().split()), lesson.teachers


Seed = dict[str, set[tuple[str, tuple[str, ...]]]]


def shift_seed(previous: Snapshot | None, current: Snapshot) -> Seed:
    """История из прошлого снимка — для первой недели листа, где своей ещё нет.

    Каждый новый лист начинается без истории, и первые дни сдвиг был бы
    невидим — а это как раз дни, которые только что набрали руками. Прошлый
    снимок годится в историю, когда это тот же лист (даты пересекаются) или
    следующий за ним через выходные. После каникул — нет: у половины групп
    сменились предметы, и первый день семестра выглядел бы как сдвиг.
    """
    if previous is None or not previous.dates or not current.dates:
        return {}
    same = set(previous.dates) & set(current.dates)
    gap = (min(current.dates) - max(previous.dates)).days
    if not same and not 0 <= gap <= 3:
        return {}
    return {
        gid: {_trace(x) for lessons in by_date.values() for x in lessons}
        for gid, by_date in previous.schedule.items()
    }


def _check_shift(snapshot: Snapshot, seed: Seed | None = None) -> None:
    """День против истории групп — в том же листе и в `seed` (см. SHIFT_*).

    История копится по дням, а не собирается заново для каждого дня из всех
    прошлых: так было квадратично по числу дат, а колледж дописывает недели в
    один лист (второй аудит, М36). Порядок колонок — этого снимка, поэтому
    склеенный снимок двух листов сюда не годится: проверять каждый лист
    отдельно (М9).
    """
    seed = seed or {}
    first: dict[int, str] = {}
    for group in snapshot.groups:
        first.setdefault(group.column, group.id)
    order = [first[c] for c in sorted(first)]
    names = {g.id: g.name for g in snapshot.groups}
    history = {gid: set(seed.get(gid, ())) for gid in snapshot.schedule}
    for day in snapshot.dates:
        _judge_neighbours(snapshot, day, history, order, names)
        _warn_strangers(snapshot, day, history)
        for gid, by_date in snapshot.schedule.items():
            history[gid].update(_trace(x) for x in by_date.get(day, []))


def _judge_neighbours(
    snapshot: Snapshot, day: date, history: dict[str, set], order: list[str], names: dict[str, str]
) -> None:
    """Отказ, если в строке пары подряд идущие группы получили пары соседа."""
    lessons = {
        gid: {x.number: x for x in snapshot.schedule.get(gid, {}).get(day, [])} for gid in order
    }
    for number in sorted({n for by_number in lessons.values() for n in by_number}):
        for k in SHIFT_OFFSETS:
            votes, start = 0, None
            for i, gid in enumerate(order):
                lesson = lessons[gid].get(number)
                if lesson is None:
                    continue
                trace = _trace(lesson)
                own = trace in history[gid]
                j = i + k
                theirs = 0 <= j < len(order) and trace in history[order[j]]
                if theirs and not own:
                    votes += 1
                    start = start or gid
                    if votes >= SHIFT_RUN_REJECT:
                        side = "правее" if k > 0 else "левее"
                        raise SourceFormatChanged(
                            f"{number}-я пара {day} похожа на сдвиг колонок: у {votes} групп "
                            f"подряд, начиная с {names.get(start, start)}, пары соседа на "
                            f"{abs(k)} {'колонку' if abs(k) == 1 else 'колонки'} {side}"
                        )
                elif own and not theirs:
                    votes, start = 0, None


def _warn_strangers(snapshot: Snapshot, day: date, history: dict[str, set]) -> None:
    """Много незнакомых пар — не отказ, а запись в журнал: так выглядит и
    неделя практики, и классный час, и сдвиг, которого признак соседа не
    увидел (например, в первые дни листа без истории)."""
    compared = 0
    strangers: list[str] = []
    rows: dict[int, list[int]] = {}
    for gid, by_date in snapshot.schedule.items():
        today = by_date.get(day, [])
        known = history[gid]
        if len(known) < SHIFT_MIN_HISTORY:
            continue
        for lesson in today:
            cell = rows.setdefault(lesson.number, [0, 0])
            cell[0] += 1
            if _trace(lesson) not in known:
                cell[1] += 1
        if len(today) < SHIFT_MIN_LESSONS_TODAY:
            continue
        compared += 1
        if not ({_trace(x) for x in today} & known):
            strangers.append(gid)
    for number, (seen, unknown) in sorted(rows.items()):
        if seen >= SHIFT_MIN_GROUPS and unknown / seen > SHIFT_ROW_WARN_SHARE:
            log.warning(
                "%d-я пара %s: у %d групп из %d незнакомый предмет — не сдвиг по соседям, "
                "но присмотреться", number, day, unknown, seen,
            )
    if compared >= SHIFT_MIN_GROUPS and len(strangers) / compared > SHIFT_WARN_SHARE:
        log.warning(
            "день %s: у %d групп из %d ни одного знакомого предмета (%s) — "
            "не сдвиг по соседям, но присмотреться", day, len(strangers), compared,
            ", ".join(strangers[:3]),
        )


def collapse_export(rows: list[list[str]], min_groups: int = MIN_GROUPS) -> list[list[str]]:
    """Сырой экспорт -> форма, на которой построен разбор. См. `collapse_with_rows`."""
    return collapse_with_rows(rows, min_groups)[0]


def collapse_with_rows(
    rows: list[list[str]], min_groups: int = MIN_GROUPS
) -> tuple[list[list[str]], list[int]]:
    """Сырой экспорт -> (форма, на которой построен разбор; номера строк листа).

    В сыром листе шапка стоит столбиком: строка «Дисциплина», под ней
    «Преподаватель», под ней имена групп; выше — пустые строки с рамками.
    gviz склеивал эти строки в одну — «Дисциплина Преподаватель БП-1126»,
    «Ауд.», «№» — и выбрасывал пустые строки посреди листа. Делаем то же
    сами, и только это: остальное разбор читает как читал. Повторные
    заголовки внутри листа не трогаем — они и раньше приходили столбиком.

    Лист, уже собранный (форма gviz, фикстуры), возвращается как есть:
    признак — строка с «Дисциплина Преподаватель …» раньше первой
    столбиковой шапки.

    Второе значение — номер строки листа (как в Sheets, с единицы) для каждой
    строки результата: по нему сообщения разбора ведут в настоящую строку.
    """
    for i, row in enumerate(rows):
        if sum(1 for c in row if c.strip().startswith(_HEADER_PREFIX)) >= min_groups:
            return rows, list(range(1, len(rows) + 1))
        below = rows[i + 1] if i + 1 < len(rows) else []
        if (
            sum(1 for c in row if c.strip() == "Дисциплина") >= min_groups
            and sum(1 for c in below if c.strip() == "Преподаватель") >= min_groups
        ):
            head = rows[: i + 3]
            width = max(len(r) for r in head)
            merged = [
                " ".join(
                    part
                    for part in ((r[col] if col < len(r) else "").strip() for r in head)
                    if part
                )
                for col in range(width)
            ]
            # Пустые строки — только шум: строка под парой отдана
            # преподавателям, и пустая между ними отняла бы их у всех групп.
            kept = [
                (n, r) for n, r in enumerate(rows[i + 3:], start=i + 4) if any(c.strip() for c in r)
            ]
            return [merged] + [r for _, r in kept], [i + 3] + [n for n, _ in kept]
    return rows, list(range(1, len(rows) + 1))


def parse_csv(
    text: str, sheet_title: str, limits: Limits = FULL_SHEET, around: date | None = None
) -> Snapshot:
    rows, numbers = collapse_with_rows(read_csv(text), limits.min_groups)
    return parse_sheet(rows, sheet_title, limits, around=around, sheet_rows=numbers)


def parse_export(
    text: str,
    sheet_title: str,
    gid: str | None,
    limits: Limits = FULL_SHEET,
    around: date | None = None,
) -> Snapshot:
    """То же, что `parse_csv`, но помнит, в каких строках листа стоят дни.

    Номера строк — до схлопывания и выбрасывания пустых: в сыром экспорте
    они совпадают с тем, что видит человек в Sheets (проверено по Sheets API
    14 сентября 2026: 6, 18, 30, 43, 64, …, 139).
    """
    rows = read_csv(text)
    places = {
        day: SheetPlace(gid=gid, row=row)
        for day, row in date_rows([r[0] if r else "" for r in rows]).items()
    }
    collapsed, numbers = collapse_with_rows(rows, limits.min_groups)
    snapshot = parse_sheet(collapsed, sheet_title, limits, around=around, sheet_rows=numbers)
    snapshot.places = places
    if gid:
        snapshot.sheet_columns = {gid: {g.id: g.column for g in snapshot.groups}}
    return snapshot


def date_rows(first_column: list[str]) -> dict[date, int]:
    """Номера строк дней по колонке A сырого листа, как их видит человек в Sheets.

    Из CSV от gviz номера строк было не достать: он схлопывал шапку в одну
    строку и выбрасывал пустые строки посреди листа — на 13.09.2026 сырых
    строк 210, в CSV 199, и сдвиг рос вниз по листу с четырёх до одиннадцати.
    В сыром экспорте строки настоящие. Нужны они ссылке «открыть таблицу»:
    `range=EQ139` подводит к ячейке, а не к верху листа.
    """
    rows: dict[date, int] = {}
    for index, cell in enumerate(first_column):
        found = _parse_date(cell)
        if found is not None and found not in rows:
            rows[found] = index + 1
    return rows
