"""Карта колонок таблицы и поиск строк-заголовков.

Лист устроен блоками по четыре колонки на группу:
    col   — предмет (в строке пары) и ФИО преподавателя (в строке под ней)
    col+1, col+2 — пустые
    col+3 — аудитория, ссылка на вебинар или пометка отмены

Заголовок встречается не только в первой строке: внутри листа попадается
повторный, набранный «столбиком» (Дисциплина / Преподаватель / имя группы).
"""

from __future__ import annotations

import re

from ..domain.ids import group_id
from ..domain.models import GroupRef, SourceFormatChanged

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
            skip.update({i, i + 1, i + 2})

    return skip
