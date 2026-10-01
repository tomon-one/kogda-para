"""Сырой экспорт листа (CSV) -> строки, на которых построен обход.

В сыром экспорте шапка стоит столбиком, а обход построен на форме gviz, где она
одной строкой. `collapse_with_rows` приводит лист к этой форме и помнит
настоящие номера строк листа.
"""

from __future__ import annotations

import csv
import io
from datetime import date

from ..domain.models import SheetPlace, Snapshot
from .csv_schedule import FULL_SHEET, Limits, parse_sheet
from .dates import date_rows
from .groups import _HEADER_PREFIX, MIN_GROUPS


def read_csv(text: str) -> list[list[str]]:
    """Разбирает CSV. Кавычки и переводы строк внутри ячеек — забота модуля csv."""
    return list(csv.reader(io.StringIO(text.lstrip("﻿"))))


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
    сами, и только это. Повторные заголовки внутри листа не трогаем: разбор
    читает их столбиком.

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
