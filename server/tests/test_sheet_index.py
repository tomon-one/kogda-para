"""Поиск листа: день, не покрытый ни одним листом.

Воскресений в листах колледжа нет, поэтому день между двумя листами не покрыт
ничем. Раньше это роняло поиск, служба уходила в stale на сутки и отдавала
пустую неделю — при том что новый лист уже был опубликован и найден.
"""

import datetime as dt

from whensclass.sources.sheet_index import SheetIndex


def index_with(tmp_path, *ranges):
    index = SheetIndex(tmp_path)
    for title, first, last in ranges:
        index.remember(title, None, dt.date.fromisoformat(first), dt.date.fromisoformat(last))
    return index


def test_covering_finds_exact_sheet(tmp_path):
    index = index_with(tmp_path, ("первый", "2026-09-02", "2026-09-12"))
    assert index.covering(dt.date(2026, 9, 8)) == ("первый", None)
    assert index.covering(dt.date(2026, 9, 13)) is None


def test_sunday_between_sheets_takes_the_upcoming_one(tmp_path):
    """13 сентября — воскресенье между листами. Смотреть надо вперёд."""
    index = index_with(
        tmp_path,
        ("прошлый", "2026-09-02", "2026-09-12"),
        ("следующий", "2026-09-14", "2026-09-19"),
    )
    assert index.nearest(dt.date(2026, 9, 13)) == ("следующий", None)


def test_without_next_sheet_falls_back_to_the_last_known(tmp_path):
    """Следующий лист ещё не выложен: прежний лучше пустоты."""
    index = index_with(tmp_path, ("прошлый", "2026-09-02", "2026-09-12"))
    assert index.nearest(dt.date(2026, 9, 13)) == ("прошлый", None)


def test_nearest_returns_covering_sheet_when_there_is_one(tmp_path):
    index = index_with(
        tmp_path,
        ("прошлый", "2026-09-02", "2026-09-12"),
        ("следующий", "2026-09-14", "2026-09-19"),
    )
    assert index.nearest(dt.date(2026, 9, 15)) == ("следующий", None)


def test_empty_index_has_no_fallback(tmp_path):
    assert SheetIndex(tmp_path).nearest(dt.date(2026, 9, 13)) is None
