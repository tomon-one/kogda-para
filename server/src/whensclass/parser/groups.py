"""Карта колонок таблицы и поиск строк-заголовков.

Лист устроен блоками по четыре колонки на группу:
    col   — предмет (в строке пары) и ФИО преподавателя (в строке под ней)
    col+1, col+2 — пустые
    col+3 — аудитория, ссылка на вебинар или пометка отмены

Заголовок встречается не только в первой строке: внутри листа попадается
повторный, набранный «столбиком» (Дисциплина / Преподаватель / имя группы).
"""

from __future__ import annotations

import logging
import re

from ..domain.ids import group_id
from ..domain.models import GroupRef, SourceFormatChanged

log = logging.getLogger(__name__)

_HEADER_PREFIX = "Дисциплина Преподаватель "
_ROOM_MARK = "Ауд."
_BLOCK_WIDTH = 4
_SPLIT_RE = re.compile(r"\s+и\s+")

MIN_GROUPS = 100


def split_group_names(tail: str) -> list[str]:
    """'ДП-923 и ДП-1124' -> обе группы: одна колонка обслуживает две."""
    parts = [p.strip() for p in _SPLIT_RE.split(tail.strip())]
    return [p for p in parts if p]


def _row_columns(row: list[str]) -> dict[int, list[str]]:
    """Колонка -> имена групп, объявленные в этой строке."""
    found: dict[int, list[str]] = {}
    for col, cell in enumerate(row):
        text = (cell or "").replace("\xa0", " ").strip()
        if text.startswith(_HEADER_PREFIX):
            names = split_group_names(text[len(_HEADER_PREFIX):])
            if names:
                found[col] = names
    return found


def build_column_map(rows: list[list[str]], min_groups: int = MIN_GROUPS) -> list[GroupRef]:
    """Собирает список групп из главного заголовка листа.

    Бросает SourceFormatChanged, если блоки перестали идти с шагом 4 или
    исчезла колонка аудитории: гадать о том, чьи это пары, нельзя.
    """
    for row in rows:
        columns = _row_columns(row)
        if len(columns) >= min_groups:
            break
    else:
        raise SourceFormatChanged(
            f"не нашёл строку заголовка минимум с {min_groups} группами"
        )

    starts = sorted(columns)
    steps = {b - a for a, b in zip(starts, starts[1:])}
    if steps != {_BLOCK_WIDTH}:
        raise SourceFormatChanged(
            f"блоки групп идут с шагом {sorted(steps)}, ожидался {_BLOCK_WIDTH}"
        )

    for col in starts:
        mark = (row[col + 3] if col + 3 < len(row) else "").strip()
        if not mark.startswith(_ROOM_MARK):
            raise SourceFormatChanged(
                f"в колонке {col + 3} ожидалась «{_ROOM_MARK}», а там {mark!r}"
            )

    groups: list[GroupRef] = []
    seen: set[str] = set()
    for col in starts:
        for name in columns[col]:
            gid = group_id(name)
            if gid in seen:
                raise SourceFormatChanged(f"группа {name!r} объявлена дважды")
            seen.add(gid)
            groups.append(GroupRef(name=name, id=gid, column=col))
    return groups


def find_header_rows(
    rows: list[list[str]], groups: list[GroupRef], min_groups: int = MIN_GROUPS
) -> set[int]:
    """Индексы строк, которые надо пропустить при обходе.

    Однострочные заголовки узнаём по префиксу «Дисциплина Преподаватель»,
    трёхстрочные — по ячейке ровно «Дисциплина» с «Преподаватель» под ней.
    Если повторный заголовок объявляет другую раскладку колонок, считаем
    формат изменившимся: показать чужое расписание хуже, чем упасть.
    """
    expected = {g.column for g in groups}
    skip: set[int] = set()

    for i, row in enumerate(rows):
        columns = _row_columns(row)
        if columns:
            skip.add(i)
            if len(columns) >= min_groups and set(columns) != expected:
                raise SourceFormatChanged(
                    f"повторный заголовок в строке {i} задаёт другие колонки"
                )
            continue

        cells = {c for c, v in enumerate(row) if (v or "").strip() == "Дисциплина"}
        if not cells:
            continue
        below = rows[i + 1] if i + 1 < len(rows) else []
        if any(
            (below[c] or "").strip() == "Преподаватель"
            for c in cells
            if c < len(below)
        ):
            # Заголовок «столбиком»: Дисциплина / Преподаватель / имя группы.
            _check_columnar(i, cells, rows[i + 2] if i + 2 < len(rows) else [], groups)
            skip.update({i, i + 1, i + 2})

    return skip


def _check_columnar(
    row_index: int, cells: set[int], names_row: list[str], groups: list[GroupRef]
) -> None:
    """Сверяет колонки повторного заголовка с главным.

    Раньше три строки такого заголовка пропускались вслепую. Если ниже него
    добавить хоть одну группу, весь хвост листа съезжает на блок — и разбор
    проходит без единой ошибки, просто каждая группа получает расписание
    соседа. Это худшее, что может случиться: приложение уверенно показывает
    чужие пары, статус остаётся «ok», и заметить это можно только глазами.

    Поэтому имена групп из третьей строки заголовка сверяются с картой колонок.
    Сдвиг узнаётся по имени, которое по главному заголовку живёт в другой
    колонке, — или по колонке, которой в главном заголовке нет вовсе. Тогда
    считаем формат изменившимся: упасть и остаться на прежнем снимке лучше,
    чем отправить человека не в ту аудиторию.

    А имя, которого нет ни в одной колонке главного заголовка, — не сдвиг, а
    переименование. 11 сентября 2026 колледж поправил имя одной группы в
    главном заголовке и не тронул его в двух повторных — и из-за одной ячейки
    187 групп два дня сидели без расписания. Колонка та же, пары под ней те
    же: про такое пишем в журнал и идём дальше, веря главному заголовку.
    """
    by_column: dict[int, set[str]] = {}
    column_of: dict[str, int] = {}
    for group in groups:
        by_column.setdefault(group.column, set()).add(group.id)
        column_of[group.id] = group.column

    for col in sorted(cells):
        declared = split_group_names((names_row[col] if col < len(names_row) else "") or "")
        if not declared:
            # Имя не написали — сверять нечего, это не повод падать.
            continue
        known = by_column.get(col)
        if known is None:
            raise SourceFormatChanged(
                f"повторный заголовок в строке {row_index} объявляет группу "
                f"{declared} в колонке {col}, которой нет в главном заголовке"
            )
        ids = {group_id(name) for name in declared}
        if ids == known:
            continue
        strangers = sorted(gid for gid in ids if gid in column_of and column_of[gid] != col)
        if strangers:
            raise SourceFormatChanged(
                f"повторный заголовок в строке {row_index}: в колонке {col} "
                f"стоит {declared}, а по главному заголовку {strangers[0]!r} "
                f"живёт в колонке {column_of[strangers[0]]}, здесь же {sorted(known)}"
            )
        log.warning(
            "повторный заголовок в строке %d: в колонке %d стоит %s, а по главному "
            "заголовку там %s — такого имени нет больше нигде, считаю "
            "переименованием и верю главному заголовку",
            row_index, col, declared, sorted(known),
        )
