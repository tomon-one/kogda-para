"""Обход листа расписания: строки таблицы -> Snapshot.

Принимает уже разобранные строки, а не текст CSV. Разбор построен на форме,
которую давал gviz: шапка одной строкой «Дисциплина Преподаватель <группа>».
С 14 сентября 2026 лист берётся сырым экспортом, где шапка — три строки
столбиком; `collapse_export` приводит её к прежней форме, и обход остаётся
тем же.
"""

from __future__ import annotations

import logging
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, timedelta

from ..domain.models import SheetTooSmall, Snapshot, SourceFormatChanged
from .dates import _parse_date
from .groups import _warn_once, build_column_map, find_header_rows
from .rows import (
    _LESSON_NO_RE,
    _LOOKS_LIKE_DATE,
    _TIME_RE,
    _blank_day,
    _cell,
    _lessons_at,
    _mark_unread,
    _spill,
    _teacher_row,
)
from .sections import _Doubt, _section_columns, _settle_moved
from .settle import _adopt_names, _draft_tail, _retyped_order, _settle_replacements

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Limits:
    """Границы правдоподобия листа. Не сошлось — значит таблицу переделали."""

    min_groups: int = 100
    min_dates: int = 5
    min_lessons: int = 500
    # Разрыв между соседними днями одного листа. Опечатка в месяце у одной
    # даты (14.10 вместо 14.09) даёт разрыв в месяц и растянула бы лист; а
    # зимние каникулы внутри листа (26.12 → 11.01) — шестнадцать дней, поэтому
    # порог больше двух недель.
    max_gap_days: int = 25
    # Дальше этого от сегодня дата с парами — опечатка («2027» растянула бы
    # лист на год), а пустые даты — каркас на будущее, их просто отрезаем.
    # 24 сентября 2026 колледж вписал каркас на 39 дней вперёд, так что запас
    # нужен с избытком.
    max_days_ahead: int = 120


FULL_SHEET = Limits()


FULL_SHEET = Limits()
# Для фикстур из нескольких групп: структура та же, объём другой.
FIXTURE = Limits(min_groups=3, min_dates=2, min_lessons=5)


def parse_sheet(
    rows: Iterable[list[str]],
    sheet_title: str,
    limits: Limits = FULL_SHEET,
    around: date | None = None,
    sheet_rows: list[int] | None = None,
    previous: Snapshot | None = None,
) -> Snapshot:
    """`around` — сегодняшний день: дальше `limits.max_days_ahead` от него пар не ждём.

    `previous` — прежний принятый снимок: предметы групп в нём помогают
    запасному разбору дней под сдвинутым заголовком (`_settle_moved`), когда
    в самом листе над заголовком всего неделя.

    `sheet_rows[i]` — номер строки листа, как его видит человек в Sheets, для
    строки `i` после схлопывания шапки. С ним сообщения разбора ведут в ту
    строку, что открывается в таблице: без него номер расходится с Sheets.
    """
    rows = [list(r) for r in rows]
    draft = _draft_tail(rows)
    if draft is not None:
        at = sheet_rows[draft] if sheet_rows is not None and draft < len(sheet_rows) else draft
        order = _retyped_order(rows, draft, limits.min_groups)
        if order is not None:
            _warn_once(
                ("заново", at), "со строки %s до конца листа — те же дни набраны заново "
                "и не беднее прежних: читаю их вместо прежних", at, logger=log,
            )
            rows = [rows[i] for i in order]
            if sheet_rows is not None:
                sheet_rows = [sheet_rows[i] for i in order]
        else:
            _warn_once(
                ("черновик", at), "со строки %s до конца листа — повтор уже выложенных дат, "
                "черновик недели: не читаю", at, logger=log,
            )
            rows = rows[:draft]

    def where(i: int) -> str:
        if sheet_rows is not None and i < len(sheet_rows):
            return f"строке {sheet_rows[i]} листа"
        return f"строке {i}"

    groups = build_column_map(rows, limits.min_groups)
    adopt: dict[int, list[str]] = {}
    distrust: dict[int, tuple[set[int], str]] = {}
    names_at: dict[int, int] = {}
    skip = find_header_rows(
        rows, groups, limits.min_groups, where=where, adopt=adopt, distrust=distrust,
        names_at=names_at,
    )
    groups, unnamed = _adopt_names(rows, groups, adopt, limits.min_groups)
    spill = _spill(rows, groups, skip, where)

    snapshot = Snapshot(sheet_title=sheet_title, groups=groups)
    for group in groups:
        snapshot.schedule.setdefault(group.id, {})

    current: date | None = None
    seen_dates: list[date] = []
    # Порядок дат, как они встретились, без дедупликации: она прячет
    # нарушение порядка от проверки «даты идут по возрастанию».
    date_order: list[date] = []
    # Где в листе стоит каждая дата из date_order — чтобы отказ называл
    # строку, а не пересказывал весь столбец дат.
    date_where: list[str] = []

    # Номера пар каждого дня: обязаны идти 1, 2, 3… без пропусков и
    # повторов. Повтор даты (скопированный блок) и номер не по шаблону
    # («3 пара», пропущенная строка) ловятся здесь же.
    numbers_by_date: dict[date, list[int]] = {}
    # Даты дальше горизонта и где они в листе: с парами — отказ, без — отрезаем.
    far: dict[date, str] = {}
    # Где в листе дата встретилась впервые — для отказа при повторе.
    first_row: dict[date, str] = {}
    # День каждой строки: лишний текст в блоке помечает этот день группы.
    row_date: dict[int, date] = {}
    # День без даты: (какой день по соседям, где начался, что в ячейке даты).
    dateless: tuple[date, str, str] | None = None
    unread_days: list[tuple[date, str, str]] = []
    # Время звонков, как оно стоит в листе: день -> номер пары -> ячейки.
    times: dict[date, dict[int, list[str]]] = {}

    # Колонки, которым ниже повторного заголовка верить нельзя: он расставил
    # группы иначе, чем главный, и чьи пары под ним, неизвестно. Дни таких
    # групп не прочитаны, остальные читаются.
    moved: set[int] = set()
    # Такие дни читаются запасным способом: день разбирается дважды — по
    # главному заголовку и по повторному, — а какой из разборов верен, решает
    # содержимое (`_settle_moved`).
    doubt: dict[date, _Doubt] = {}
    section: tuple[dict[str, int], str, dict[str, tuple[str, int]]] = ({}, "", {})

    blank_sunday: set[int] = set()
    for i, row in enumerate(rows):
        if i in distrust:
            moved, why = distrust[i]
            placed, split = _section_columns(rows, i, names_at[i], groups) if moved else ({}, {})
            section = (placed, why, split)
        if i in skip or i in blank_sunday:
            continue

        cell = _cell(row, 0)
        try:
            found = _parse_date(cell)
        except SourceFormatChanged as exc:
            raise SourceFormatChanged(f"{exc} в {where(i)}") from exc
        if found is None and cell.strip():
            if _LOOKS_LIKE_DATE.search(cell):
                raise SourceFormatChanged(f"дата в {where(i)} не разобралась: {cell!r}")
            # Слово без чисел — «понедельник» над датой, «неделя 3»: не дата.
            log.info("в колонке дат в %s не дата: %r — пропускаю", where(i), cell.strip())
        if found is not None:
            if found.weekday() == 6:
                # Пустой каркас на воскресенье (колледж вписывает даты заранее
                # подряд) — не опечатка, а лишний день: пропустить, а не
                # отвергать из-за него весь лист.
                block = _blank_day(rows, i)
                if block is not None:
                    blank_sunday.update(block)
                    log.info("в %s пустое воскресенье %s — пропускаю", where(i), f"{found:%d.%m}")
                    continue
                # Воскресений в листах колледжа не бывает: это опечатка в числе.
                raise SourceFormatChanged(f"дата {found:%d.%m.%Y} в {where(i)} — воскресенье")
            if around is not None and found > around + timedelta(days=limits.max_days_ahead):
                far.setdefault(found, where(i))
            if dateless is not None:
                # День без даты признаём, только если следующая дата встала
                # ровно за ним: иначе его место в листе не угадать.
                if found != _school_day_after(dateless[0]):
                    raise _no_date(*dateless, current)
                unread_days.append(dateless)
                dateless = None
            if found != current:
                if found in first_row:
                    # Копия блока вместе с датой: отказ называет обе строки.
                    raise SourceFormatChanged(
                        f"дата {found:%d.%m.%Y} в {where(i)} уже была в {first_row[found]}"
                    )
                first_row[found] = where(i)
                date_order.append(found)
                date_where.append(where(i))
            current = found
            if current not in seen_dates:
                seen_dates.append(current)
        if current is not None:
            row_date[i] = current

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
            if number_cell and current is not None and numbers_by_date.get(current):
                times.setdefault(current, {}).setdefault(
                    numbers_by_date[current][-1], []
                ).append(number_cell)
            continue
        if current is None:
            raise SourceFormatChanged(f"пара в {where(i)} раньше первой даты")
        number = int(m.group(1))
        if number == 1 and found is not None and numbers_by_date.get(current):
            raise SourceFormatChanged(
                f"дата {found:%d.%m.%Y} в {where(i)} повторена: этот день уже начат в "
                f"{first_row[found]}"
            )
        if number == 1 and found is None and numbers_by_date.get(current):
            # С первой пары начинается новый день, а даты у него нет: иначе
            # день допишется к предыдущему. 20 сентября 2026 вместо
            # «21.09.2026 понедельник» в листе стояла запятая. Это следующий
            # учебный день, если следующая дата это подтвердит; его пары не
            # читаются — день не прочитан у всех групп.
            if dateless is not None:
                raise _no_date(*dateless, current)
            guess = _school_day_after(current)
            dateless = (guess, where(i), cell.strip())
            current = guess
            first_row[guess] = where(i)
            date_order.append(guess)
            date_where.append(where(i))
            seen_dates.append(guess)
            row_date[i] = guess
        expected = len(numbers_by_date.setdefault(current, [])) + 1
        if number != expected:
            raise SourceFormatChanged(
                f"номера пар {current} идут {numbers_by_date[current] + [number]}: "
                f"ждал {expected}"
            )
        numbers_by_date[current].append(number)

        teacher_row = _teacher_row(rows, i, skip, where)

        for group in groups:
            col = group.column
            if col in moved:
                day = doubt.setdefault(current, _Doubt(section[0], section[1], section[2]))
                day.by_main.setdefault(group.id, []).extend(
                    _lessons_at(row, teacher_row, col, number)
                )
                if group.id in day.columns:
                    day.by_section.setdefault(group.id, []).extend(
                        _lessons_at(row, teacher_row, day.columns[group.id], number)
                    )
                continue
            found_here = _lessons_at(row, teacher_row, col, number)
            if found_here:
                snapshot.schedule[group.id].setdefault(current, []).extend(found_here)
        if current in doubt:
            day = doubt[current]
            for gid, (_, col) in day.new.items():
                day.by_new.setdefault(gid, []).extend(_lessons_at(row, teacher_row, col, number))
        for col in unnamed:
            if _cell(row, col).strip():
                unnamed[col] += 1

    if dateless is not None:
        raise _no_date(*dateless, None)
    _mark_unread(snapshot, spill, row_date, where)
    _settle_moved(snapshot, doubt, previous)
    for day, place, cell in unread_days:
        for group in snapshot.groups:
            snapshot.schedule.get(group.id, {}).pop(day, None)
            snapshot.unread.setdefault(group.id, {})[day] = False
        snapshot.unread_why.append(
            f"все группы {day:%d.%m}: в {place} начинается день без даты ({cell!r}), "
            "дата — по соседним дням"
        )
    for by_date in snapshot.schedule.values():
        for day, lessons in by_date.items():
            lessons.sort(key=lambda x: x.number)
    _settle_replacements(snapshot)
    _cut_far_skeleton(snapshot, far, seen_dates, limits, around)

    snapshot.dates = sorted(seen_dates)
    for day in snapshot.dates:
        if grid := _day_bells(day, numbers_by_date.get(day, []), times.get(day, {})):
            snapshot.bells[day] = grid
    snapshot.unnamed = {col: n for col, n in unnamed.items() if n}
    _validate(snapshot, date_order, limits, date_where)
    return snapshot


def _day_bells(
    day: date, numbers: list[int], cells: dict[int, list[str]]
) -> dict[str, list[str]] | None:
    """Звонки дня из листа: номер пары -> начало и конец. None — им верить нельзя.

    Верим только целой сетке: время есть у каждой пары дня, пары идут одна за
    другой и не накладываются. Иначе день остаётся на обычной сетке: половина
    дня по одной сетке и половина по другой — время, которого нет нигде.
    """
    grid: dict[str, list[str]] = {}
    last_end = -1
    for number in numbers:
        spans = {_span(cell) for cell in cells.get(number, [])}
        if len(spans) != 1 or None in spans:
            if cells:
                _warn_once(
                    ("звонки", day, number),
                    "время %d-й пары %s в листе не читается (%s) — у дня обычные звонки",
                    number, f"{day:%d.%m.%Y}", cells.get(number), logger=log,
                )
            return None
        start, end = spans.pop()
        if start < last_end:
            _warn_once(
                ("звонки", day, number),
                "%d-я пара %s в листе начинается раньше конца предыдущей — у дня "
                "обычные звонки", number, f"{day:%d.%m.%Y}", logger=log,
            )
            return None
        last_end = end
        grid[str(number)] = [f"{start // 60:02d}:{start % 60:02d}", f"{end // 60:02d}:{end % 60:02d}"]
    return grid or None


def _span(cell: str) -> tuple[int, int] | None:
    """«9-00-10.30» -> минуты начала и конца. None — не время суток или конец не позже начала."""
    m = _TIME_RE.match(cell)
    if not m:
        return None
    h1, m1, h2, m2 = (int(x) for x in m.groups())
    if h1 > 23 or h2 > 23 or m1 > 59 or m2 > 59:
        return None
    start, end = h1 * 60 + m1, h2 * 60 + m2
    return (start, end) if start < end else None


def _school_day_after(day: date) -> date:
    """Следующий учебный день: воскресений в листе нет."""
    after = day + timedelta(days=1)
    return after + timedelta(days=1) if after.weekday() == 6 else after


def _no_date(guess: date, place: str, cell: str, then: date | None) -> SourceFormatChanged:
    after = "лист кончился" if then is None else f"следующая дата — {then}"
    return SourceFormatChanged(
        f"в {place} начинается новый день (1-я пара), а даты нет: {cell!r} — "
        f"ждал {guess}, но {after}"
    )


def _cut_far_skeleton(
    snapshot: Snapshot, far: dict[date, str], seen: list[date], limits: Limits, around
) -> None:
    """Даты за горизонтом: с парами — опечатка, отказ; пустые — каркас, прочь."""
    for day, place in sorted(far.items()):
        if any(by_date.get(day) for by_date in snapshot.schedule.values()):
            raise SourceFormatChanged(
                f"дата {day} в {place} дальше {limits.max_days_ahead} дней от {around}, "
                "а в ней уже пары"
            )
    if far:
        log.info("пустые даты за горизонтом (%s…%s) — каркас, отрезаю", min(far), max(far))
        seen[:] = [d for d in seen if d not in far]


def _validate(
    snapshot: Snapshot,
    seen_order: list[date],
    limits: Limits,
    where: list[str] | None = None,
) -> None:
    """Проверяет, что разобранное похоже на расписание, а не на обломки."""
    if len(snapshot.dates) < limits.min_dates:
        raise SheetTooSmall(
            f"нашёл всего {len(snapshot.dates)} дней, ожидал не меньше {limits.min_dates}",
            starts=min(snapshot.dates, default=None),
        )
    for k, (a, b) in enumerate(zip(seen_order, seen_order[1:])):
        if b <= a:
            # Первое нарушение, со строкой листа, а не весь столбец дат (24
            # сентября 2026 колледж собирал новую неделю из скопированных
            # блоков, и под 05.10 осталась дата 26.09 от копии).
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
        raise SheetTooSmall(
            f"нашёл всего {total} пар, ожидал не меньше {limits.min_lessons}",
            starts=min(snapshot.dates, default=None),
        )
