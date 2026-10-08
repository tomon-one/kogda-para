"""Обход листа расписания: строки таблицы -> Snapshot.

Принимает уже разобранные строки, а не текст CSV. Разбор построен на форме,
которую давал gviz: шапка одной строкой «Дисциплина Преподаватель <группа>».
С 14 сентября 2026 лист берётся сырым экспортом, где шапка — три строки
столбиком; `collapse_export` приводит её к прежней форме, и обход остаётся
тем же.
"""

from __future__ import annotations

import dataclasses
import logging
import re
from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import date, timedelta

from ..domain.ids import group_id
from ..domain.models import (
    GroupRef,
    Lesson,
    SheetTooSmall,
    Snapshot,
    SourceFormatChanged,
    a1_column,
)
from .cells import _HAS_LETTER, _ROOM_RE, parse_lesson, replaced_of
from .dates import _parse_date
from .groups import (
    _warn_once,
    build_column_map,
    find_header_rows,
    header_blocks,
    split_group_names,
)
from .subgroups import split_subgroups

log = logging.getLogger(__name__)

# Ячейка с двумя числами через точку, дробь или дефис — это дата, которую
# не смогли прочитать, а не «нет даты»: иначе день молча приклеится к
# предыдущему.
_LOOKS_LIKE_DATE = re.compile(r"\d{1,2}\s*[.,/-]\s*\d{1,2}")
_LESSON_NO_RE = re.compile(r"^([1-9])$")
# Строка под парой несёт время звонка в колонке номера: «9-00-10.30».
_TIME_RE = re.compile(r"^(\d{1,2})[.:-](\d{2})\s*[-–]\s*(\d{1,2})[.:-](\d{2})$")
# Сдвиг не на целый блок («вставить 1–3 ячейки»): колонки +1 и +2 блока,
# всегда пустые, получают текст. Сдвиг строки задевает каждый блок правее
# вставки — у сдвига хоть на один день таких ячеек от 70. Текст в одном-двух
# блоках — запись колледжа, которую разбор не знает: день этих групп не
# прочитан, остальным лист принимается.
SPILL_BLOCKS = 3
# Одна буква в пустой колонке — опечатка, а не сдвиг: за 350 версий листа
# такая была одна.
_NOISE = re.compile(r"^\W*\w?\W*$")


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
# Для фикстур из нескольких групп: структура та же, объём другой.
FIXTURE = Limits(min_groups=3, min_dates=2, min_lessons=5)


def _cell(row: list[str], col: int) -> str:
    return row[col] if col < len(row) else ""


def parse_sheet(
    rows: Iterable[list[str]],
    sheet_title: str,
    limits: Limits = FULL_SHEET,
    around: date | None = None,
    sheet_rows: list[int] | None = None,
) -> Snapshot:
    """`around` — сегодняшний день: дальше `limits.max_days_ahead` от него пар не ждём.

    `sheet_rows[i]` — номер строки листа, как его видит человек в Sheets, для
    строки `i` после схлопывания шапки. С ним сообщения разбора ведут в ту
    строку, что открывается в таблице: без него номер расходится с Sheets.
    """
    rows = [list(r) for r in rows]

    def where(i: int) -> str:
        if sheet_rows is not None and i < len(sheet_rows):
            return f"строке {sheet_rows[i]} листа"
        return f"строке {i}"

    groups = build_column_map(rows, limits.min_groups)
    adopt: dict[int, list[str]] = {}
    distrust: dict[int, tuple[set[int], str]] = {}
    skip = find_header_rows(
        rows, groups, limits.min_groups, where=where, adopt=adopt, distrust=distrust
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
    section: tuple[dict[str, int], str] = ({}, "")

    for i, row in enumerate(rows):
        if i in distrust:
            moved, why = distrust[i]
            section = (_section_columns(rows, i, groups) if moved else {}, why)
        if i in skip:
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
                day = doubt.setdefault(current, _Doubt(section[0], section[1]))
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
        for col in unnamed:
            if _cell(row, col).strip():
                unnamed[col] += 1

    if dateless is not None:
        raise _no_date(*dateless, None)
    _mark_unread(snapshot, spill, row_date, where)
    _settle_moved(snapshot, doubt)
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


def _lessons_at(row: list[str], teacher_row: list[str], col: int, number: int) -> list[Lesson]:
    """Пары блока, который начинается в колонке `col`."""
    out: list[Lesson] = []
    # Половинки — две пары с одним номером: каждая со своей аудиторией.
    halved = _halves(row, col, teacher_row)
    parts = [(col, col + 1), (col + 2, col + 3)] if halved else [(col, col + 3)]
    for subject_col, room_col in parts:
        cells = (
            _cell(row, subject_col),
            _cell(row, room_col),
            _cell(teacher_row, subject_col) if teacher_row else "",
        )
        for subject_raw, room_raw, teacher_raw in split_subgroups(*cells) or [cells]:
            lesson = parse_lesson(
                number=number, subject_raw=subject_raw, room_raw=room_raw,
                teacher_raw=teacher_raw,
            )
            if lesson is not None:
                out.append(lesson)
    return out


@dataclass
class _Doubt:
    """День под повторным заголовком, который расставил группы иначе, чем главный."""

    # Группа -> её колонка по повторному заголовку; группы там нет — нет и здесь.
    columns: dict[str, int]
    why: str
    by_main: dict[str, list[Lesson]] = field(default_factory=dict)
    by_section: dict[str, list[Lesson]] = field(default_factory=dict)


def _section_columns(rows: list[list[str]], i: int, groups: list[GroupRef]) -> dict[str, int]:
    """Где какая группа по повторному заголовку в строке `i` (имена — в `i + 2`).

    Имя, которого главный заголовок не знает («СИС(а)-926/1» у «СИС-926/1»), —
    прежнее имя группы: её место видно по соседям. Если слева и справа группы
    стоят с одним и тем же шагом от своих колонок, с тем же шагом стоит и она.
    """
    names_row = rows[i + 2] if i + 2 < len(rows) else []
    main_col = {g.id: g.column for g in groups}
    at_main: dict[int, list[str]] = {}
    for g in groups:
        at_main.setdefault(g.column, []).append(g.id)
    heads = sorted(c for c, v in enumerate(rows[i]) if (v or "").strip() == "Дисциплина")
    placed: dict[str, int] = {}
    step: dict[int, int] = {}
    unknown: list[int] = []
    for col in heads:
        ids = [group_id(name) for name in split_group_names(_cell(names_row, col))]
        known = [gid for gid in ids if gid in main_col]
        if known:
            for gid in known:
                placed.setdefault(gid, col)
            step[col] = col - main_col[known[0]]
        elif ids:
            unknown.append(col)
    for col in unknown:
        left = next((step[c] for c in reversed(heads[: heads.index(col)]) if c in step), None)
        right = next((step[c] for c in heads[heads.index(col) + 1:] if c in step), None)
        if left is None or left != right:
            continue
        for gid in at_main.get(col - left, []):
            placed.setdefault(gid, col)
    return placed


# Запасной способ верит разбору дня, только если предметы почти все знакомы
# группам (по дням выше заголовка), а у другого разбора — заметно реже.
SETTLE_MIN_LESSONS = 40
SETTLE_KNOWN = 0.9
SETTLE_GAP = 0.2


def _settle_moved(snapshot: Snapshot, doubt: dict[date, _Doubt]) -> None:
    """Дни под повторным заголовком с другой раскладкой: чьи в них пары.

    Основной способ — главный заголовок — здесь не годится: повторный с ним
    спорит. Запасной: день разобран обоими способами, и верен тот, где группы
    получили свои предметы — те, что у них были в днях выше заголовка. Сдвиг
    блока даёт группе предметы соседа, и на сотне групп разница видна сразу.
    Неделя, вставленная со сдвигом, кончается без заголовка: дальше дни снова
    стоят по главному. Поэтому решение — по каждому дню, а после первого дня
    по главному заголовку повторному больше не верим. Не решилось — день не
    прочитан, как и у группы, которой в повторном заголовке нет.
    """
    if not doubt:
        return
    known: dict[str, set[str]] = {}
    for gid, by_date in snapshot.schedule.items():
        known[gid] = {_topic(x.subject) for lessons in by_date.values() for x in lessons}

    def share(parsed: dict[str, list[Lesson]]) -> tuple[int, float]:
        total = sum(len(lessons) for lessons in parsed.values())
        hits = sum(
            1 for gid, lessons in parsed.items() for x in lessons
            if _topic(x.subject) in known.get(gid, ())
        )
        return total, (hits / total if total else 0.0)

    back_to_main = False
    read: dict[str, list[date]] = {"section": [], "main": [], "": []}
    whys: dict[str, None] = {}
    absent: dict[tuple[str, str], list[date]] = {}
    names = {g.id: g.name for g in snapshot.groups}
    for day in sorted(doubt):
        d = doubt[day]
        n_main, main = share(d.by_main)
        n_sect, sect = share(d.by_section)
        verdict = ""
        if n_main >= SETTLE_MIN_LESSONS and main >= SETTLE_KNOWN and main - sect >= SETTLE_GAP:
            verdict = "main"
        elif (
            n_sect >= SETTLE_MIN_LESSONS and sect >= SETTLE_KNOWN and sect - main >= SETTLE_GAP
            and not back_to_main
        ):
            verdict = "section"
        back_to_main = back_to_main or verdict == "main"
        read[verdict].append(day)
        parsed = {"main": d.by_main, "section": d.by_section}.get(verdict, {})
        for gid in d.by_main:
            snapshot.schedule[gid].pop(day, None)
            if verdict == "main" or (verdict == "section" and gid in d.columns):
                if parsed.get(gid):
                    snapshot.schedule[gid][day] = list(parsed[gid])
            else:
                snapshot.unread.setdefault(gid, {})[day] = False
                if verdict == "section":
                    absent.setdefault((gid, d.why), []).append(day)
                else:
                    whys[d.why] = None
    for (gid, why), days in absent.items():
        snapshot.unread_why.append(
            f"{names.get(gid, gid)} {days[0]:%d.%m}–{days[-1]:%d.%m}: группы нет в повторном "
            f"заголовке, по которому прочитаны эти дни ({why})"
        )
    snapshot.unread_why.extend(whys)

    def span(days: list[date]) -> str:
        return f"{days[0]:%d.%m}–{days[-1]:%d.%m} ({len(days)})" if days else "нет"

    _warn_once(
        ("запасной", tuple(sorted(doubt)), tuple(read["section"]), tuple(read["main"])),
        "повторный заголовок спорит с главным: по повторному прочитаны дни %s, по главному — "
        "%s, не прочитаны — %s", span(read["section"]), span(read["main"]), span(read[""]),
        logger=log,
    )


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


def _halves(row: list[str], col: int, teacher_row: list[str]) -> bool:
    """Блок поделён пополам: две пары рядом, у каждой своё название.

    Так колледж пишет подгруппы с разными языками: «немецкий» и «55/1» слева,
    «английский» и «467» справа, преподаватели — под каждым названием. От
    вставки ячеек отличает то, что в +2 название, а не номер аудитории соседа,
    и в +3 есть аудитория: вставка на две ячейки оставляет там пусто. Аудитории
    справа ещё может не быть — тогда половинки узнаются по названию слева и
    ФИО под правым: у вставки +0 пуст.

    Половинка бывает и одна: правая (слева пусто) или левая — название в +0,
    аудитория в +1, справа пусто и в строке преподавателей тоже. У вставки на
    одну ячейку наоборот: в +0 аудитория соседа, название — в +1.
    """
    left = _cell(row, col).strip()
    right = _cell(row, col + 2).strip()
    if _ROOM_RE.match(left):
        return False
    if not right:
        return bool(
            _HAS_LETTER.search(left)
            and _ROOM_RE.match(_cell(row, col + 1).strip())
            and not _cell(row, col + 3).strip()
            and not _cell(teacher_row, col + 2).strip()
        )
    if not _HAS_LETTER.search(right) or _ROOM_RE.match(right):
        return False
    if _cell(row, col + 3).strip():
        return True
    return bool(_HAS_LETTER.search(left) and _HAS_LETTER.search(_cell(teacher_row, col + 2)))


def _spill(
    rows: list[list[str]], groups: list[GroupRef], skip: set[int], where
) -> list[tuple[int, int, int]]:
    """Текст в пустых колонках блоков (+1, +2): (строка, колонка блока, ячейка).

    Так выглядит «вставить ячейки» не на ширину блока: предмет уезжает в +1
    или +2, а аудитория соседа — в колонку предмета. Без проверки это прошло
    бы при ok: на две ячейки — «пар нет» у всех групп, на одну — номера
    аудиторий вместо предметов. Задеты SPILL_BLOCKS блоков и больше — отказ
    листа. Блоки, поделённые пополам ([_halves]), и их строки преподавателей
    не в счёт.
    """
    columns = sorted({g.column for g in groups})
    halved: set[tuple[int, int]] = set()
    for i, row in enumerate(rows):
        if i in skip or not _LESSON_NO_RE.match(_cell(row, 1).strip()):
            continue
        teacher_row = _teacher_row(rows, i, skip, where)
        cols = [col for col in columns if _halves(row, col, teacher_row)]
        if not cols:
            continue
        teacher_i = next((j for j in range(i + 1, len(rows)) if rows[j] is teacher_row), None)
        for col in cols:
            halved.add((i, col))
            if teacher_i is not None:
                halved.add((teacher_i, col))
    found: list[tuple[int, int, int]] = []
    for i, row in enumerate(rows):
        if i in skip:
            continue
        for col in columns:
            if (i, col) in halved:
                continue
            for extra in (1, 2):
                text = _cell(row, col + extra).strip()
                if text and not _NOISE.match(text):
                    found.append((i, col, col + extra))
    if len({col for _, col, _ in found}) >= SPILL_BLOCKS:
        i, _, col = found[0]
        raise SourceFormatChanged(
            f"в {len(found)} ячейках пустых колонок блоков есть текст, первая — "
            f"{where(i)}, колонка {a1_column(col)}: похоже на вставку ячеек не на ширину блока"
        )
    return found


def _mark_unread(
    snapshot: Snapshot, spill: list[tuple[int, int, int]], row_date: dict[int, date], where
) -> None:
    """День группы с лишним текстом в блоке — не прочитан: его пары прочь.

    Что в них, разбор не знает: половинка без аудитории, сдвиг у края строки.
    Служба отдаст по этому дню прежнее и скажет владельцу.
    """
    first: dict[tuple[int, date], tuple[int, int]] = {}
    for i, block, col in spill:
        day = row_date.get(i)
        if day is None:
            raise SourceFormatChanged(
                f"текст в пустой колонке блока раньше первой даты: {where(i)}, "
                f"колонка {a1_column(col)}"
            )
        first.setdefault((block, day), (i, col))
    for (block, day), (i, col) in sorted(first.items(), key=lambda item: item[1]):
        names = [g for g in snapshot.groups if g.column == block]
        for group in names:
            snapshot.schedule.get(group.id, {}).pop(day, None)
            snapshot.unread.setdefault(group.id, {})[day] = False
        snapshot.unread_why.append(
            f"{', '.join(g.name for g in names)} {day:%d.%m}: текст в пустой колонке "
            f"блока, {where(i)}, колонка {a1_column(col)}"
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


def _topic(subject: str) -> str:
    """Первые два слова названия: «Иностранный язык .Английский ячзык» и
    «Иностранный язык, Английский» — один предмет."""
    return " ".join(re.findall(r"[а-яёa-z]+", subject.casefold())[:2])


def _settle_replacements(snapshot: Snapshot) -> None:
    """Одно ФИО у замены — чьё: заменённой пары или новой?

    Два ФИО колледж пишет как «прежний, новый», а одно бывает и тем, и другим.
    Судим по остальным ячейкам листа: если преподаватель ведёт заменённый
    предмет и не ведёт новый, это прежний — у новой пары его нет. Иначе в его
    расписании окажется чужая пара: тому, кто ведёт «Коммуникативный тренинг»,
    — «Кураторский час» вместо него.
    """
    teaches: dict[str, set[str]] = {}
    pending: list[tuple[list[Lesson], int, str]] = []
    for by_date in snapshot.schedule.values():
        for lessons in by_date.values():
            for i, lesson in enumerate(lessons):
                old = replaced_of(lesson)
                if old is None:
                    for name in lesson.teachers:
                        teaches.setdefault(name, set()).add(_topic(lesson.subject))
                elif len(lesson.teachers) == 1:
                    pending.append((lessons, i, old))
    for lessons, i, old in pending:
        lesson = lessons[i]
        known = teaches.get(lesson.teachers[0], set())
        if _topic(old) in known and _topic(lesson.subject) not in known:
            lessons[i] = dataclasses.replace(lesson, teachers=())


def _adopt_names(
    rows: list[list[str]], groups: list[GroupRef], adopt: dict[int, list[str]], min_groups: int
) -> tuple[list[GroupRef], dict[int, int]]:
    """Имена безымянным блокам главного заголовка — из повторного.

    Возвращает группы (с подобранными) и оставшиеся безымянные блоки со
    счётчиком пар, который набирает обход.
    """
    taken = {g.column for g in groups}
    known = {g.id for g in groups}
    blocks = header_blocks(rows, min_groups) - taken
    groups = list(groups)
    for col in sorted(adopt):
        if col not in blocks:
            # Главный заголовок про этот блок не знает вовсе — не с чего
            # подбирать: его ставят и законно, под будущую группу.
            _warn_once(
                ("gap", col, tuple(adopt[col])),
                "повторный заголовок: в колонке %s стоит %s, а в главном заголовке этой "
                "колонки нет и такого имени нет нигде — считаю блок безымянным",
                a1_column(col), adopt[col], logger=log,
            )
            continue
        for name in adopt[col]:
            gid = group_id(name)
            if gid in known:
                continue
            _warn_once(
                ("adopt", col, name),
                "в главном заголовке у колонки %s нет имени, а повторный называет её %r — "
                "беру имя оттуда", a1_column(col), name, logger=log,
            )
            known.add(gid)
            groups.append(GroupRef(name=name, id=gid, column=col))
        blocks.discard(col)
    groups.sort(key=lambda g: g.column)
    return groups, dict.fromkeys(blocks, 0)


def _teacher_row(rows: list[list[str]], i: int, skip: set[int], where) -> list[str]:
    """Строка преподавателей пары в строке `i`.

    Это строка под парой со временем звонка в колонке номеров, а не просто
    следующая: между ними бывает вставлена строка с припиской («перенос с
    23.09» у одной группы), и она отняла бы преподавателей у всей строки пары.
    Строки без номера и времени между ними пропускаются. Времени нет вовсе —
    берём следующую.
    """
    between: list[int] = []
    j = i + 1
    while j < len(rows) and j not in skip:
        marker = _cell(rows[j], 1).strip()
        if _LESSON_NO_RE.match(marker):
            break
        if _TIME_RE.match(marker):
            if between:
                _warn_once(
                    ("между", where(between[0])),
                    "между строкой пары и строкой времени в %s лишняя строка — пропускаю",
                    where(between[0]), logger=log,
                )
            return rows[j]
        between.append(j)
        j += 1
    return rows[between[0]] if between else []


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
    # Сдвиг по содержимому здесь не проверяется: его зовут явно служба (с
    # историей прежнего снимка) и канарейка — так видно, какая проверка
    # отвергла лист.
