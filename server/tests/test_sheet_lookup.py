"""Поиск листа: чем «колледж не выложил» отличается от «мы не посмотрели».

Снаружи эти два случая выглядят одинаково — расписания на сегодня нет, — но
говорить о них приложение должно по-разному. Раньше поиск в обоих случаях
молча брал ближайший известный лист, служба оставалась в `ok`, и человек читал
«расписание ещё не опубликовано» — утверждение о колледже, которого мы в тот
момент проверить не могли.

Разница решается одним признаком: добрались ли мы до содержимого кандидатов.
"""

import datetime as dt

import pytest

from whensclass.domain.models import SourceFormatChanged
from whensclass.sources import sheet_index as si
from whensclass.sources.gsheets import SheetInfo

DAY = dt.date(2026, 9, 14)


def visible(*titles):
    return [
        SheetInfo(title=title, gid=str(number), hidden=False)
        for number, title in enumerate(titles, 1)
    ]


def setup_lookup(monkeypatch, sheets, behaviour):
    """Подменяет книгу и чтение листов.

    `behaviour` — что делает каждый лист: пара дат (покрытие), класс исключения
    или None, если лист не отдаёт содержимого вовсе.
    """
    monkeypatch.setattr(si, "list_sheets", lambda: sheets)

    def fetch(gid=None, title=None, etag=None):
        outcome = behaviour[title]
        if outcome is None:
            return None, None
        if isinstance(outcome, type) and issubclass(outcome, Exception):
            raise outcome("подстроено тестом")
        return title, None

    def parse(text, title):
        outcome = behaviour[title]
        if isinstance(outcome, type) and issubclass(outcome, Exception):
            raise outcome("подстроено тестом")
        first, last = outcome
        return Snapshot(dt.date.fromisoformat(first), dt.date.fromisoformat(last))

    monkeypatch.setattr(si.gsheets, "fetch_sheet_csv", fetch)
    monkeypatch.setattr(si, "parse_csv", parse)


class Snapshot:
    """Ровно то, что от снимка нужно поиску: покрытие."""

    def __init__(self, first, last):
        self.coverage = (first, last)


def test_all_sheets_read_and_none_covers_the_day(tmp_path, monkeypatch):
    """Прочитали всё, ни один лист не покрывает день — колледж не выложил.

    Это не поломка: между листами есть воскресенье, а новую неделю выкладывают
    когда захотят. Берём ближайший известный и молчим.
    """
    sheets = visible("расписание групп 01.-05.09")
    setup_lookup(monkeypatch, sheets, {"расписание групп 01.-05.09": ("2026-09-02", "2026-09-12")})

    title, _ = si.resolve_for(DAY, tmp_path)

    assert title == "расписание групп 01.-05.09"


def test_unreachable_sheet_breaks_the_lookup(tmp_path, monkeypatch):
    """До листа не добрались — молчать нельзя, даже если есть чем прикрыться."""
    sheets = visible("расписание групп 01.-05.09", "расписание групп 14.-19.09")
    setup_lookup(
        monkeypatch,
        sheets,
        {
            "расписание групп 01.-05.09": ("2026-09-02", "2026-09-12"),
            "расписание групп 14.-19.09": ConnectionError,
        },
    )

    with pytest.raises(LookupError, match="добраться не вышло"):
        si.resolve_for(DAY, tmp_path)


def test_sheet_without_content_counts_as_unreachable(tmp_path, monkeypatch):
    """Пустой ответ — тоже «не посмотрели», а не «посмотрели и не подошло»."""
    sheets = visible("расписание групп 01.-05.09", "расписание групп 14.-19.09")
    setup_lookup(
        monkeypatch,
        sheets,
        {
            "расписание групп 01.-05.09": ("2026-09-02", "2026-09-12"),
            "расписание групп 14.-19.09": None,
        },
    )

    with pytest.raises(LookupError):
        si.resolve_for(DAY, tmp_path)


def test_group_sheet_that_stopped_parsing_is_alarming(tmp_path, monkeypatch):
    """Лист групп, переставший разбираться, — тревога, а не честный отказ.

    Либо колледж переделал формат, либо мы разучились его читать. И то и другое
    значит, что расписания у нас может не быть по нашей вине.
    """
    sheets = visible("расписание групп 01.-05.09", "расписание групп 14.-19.09")
    setup_lookup(
        monkeypatch,
        sheets,
        {
            "расписание групп 01.-05.09": ("2026-09-02", "2026-09-12"),
            "расписание групп 14.-19.09": SourceFormatChanged,
        },
    )

    with pytest.raises(LookupError):
        si.resolve_for(DAY, tmp_path)


def test_calendar_that_is_not_a_schedule_is_not_alarming(tmp_path, monkeypatch):
    """А календарный график отказом не тревожит: он и не должен разбираться.

    В книге сотня листов, и добрая часть кандидатов по имени — не расписание
    групп вовсе. Считать каждый такой отказ поломкой значит выть постоянно.
    """
    sheets = visible("Календарный график 2026-2027г.", "расписание групп 01.-05.09")
    setup_lookup(
        monkeypatch,
        sheets,
        {
            "Календарный график 2026-2027г.": SourceFormatChanged,
            "расписание групп 01.-05.09": ("2026-09-02", "2026-09-12"),
        },
    )

    title, _ = si.resolve_for(DAY, tmp_path)

    assert title == "расписание групп 01.-05.09"


def test_covering_sheet_wins_over_earlier_trouble(tmp_path, monkeypatch):
    """Нашли покрывающий лист — тревожиться не о чем, даже если по пути споткнулись.

    Расписание у человека будет правильное, а значит поломки для него нет.
    """
    sheets = visible("расписание групп 06.-12.09", "расписание групп 14.-19.09")
    setup_lookup(
        monkeypatch,
        sheets,
        {
            "расписание групп 06.-12.09": ConnectionError,
            "расписание групп 14.-19.09": ("2026-09-14", "2026-09-26"),
        },
    )

    title, _ = si.resolve_for(DAY, tmp_path)

    assert title == "расписание групп 14.-19.09"
