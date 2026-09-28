"""Набор листов на окно дней: сколько их нужно и когда лезть за ними в сеть.

`resolve_window` решает две вещи разом — правильность (попадут ли в ответ дни
следующей недели) и нагрузку (deep=True это выгрузка книги, без ключа Sheets API
на два десятка мегабайт). До 9 сентября её тело не исполнялось ни одним тестом
ни разу: проверки били по `SheetIndex` и по `resolve_for` по отдельности, а
сборку окна не трогали вообще.

Первый же написанный тест показал ошибку: соседний лист из памяти не брался
никогда. Спрашивали `covering(covered_to + 1)` — лист, покрывающий день сразу
после текущего, — а это всегда воскресенье, которого нет ни в одном листе.
"""

import datetime as dt

import pytest

from whensclass.sources import sheet_index as si

# Лист колледжа живёт с понедельника по субботу через неделю; воскресенье
# между листами не покрыто ничем, и это норма, а не поломка.
CURRENT = ("расписание групп 01.-05.09", "2026-09-02", "2026-09-12")
NEXT = ("расписание групп 14.-19.09", "2026-09-14", "2026-09-26")

MONDAY = dt.date(2026, 9, 7)


@pytest.fixture
def index(tmp_path):
    def build(*ranges):
        memory = si.SheetIndex(tmp_path)
        for title, first, last in ranges:
            memory.remember(
                title, None, dt.date.fromisoformat(first), dt.date.fromisoformat(last)
            )
        return memory

    return build


@pytest.fixture
def resolves(monkeypatch):
    """Подменяет поиск листа: тут проверяется сборка окна, а не сам поиск."""

    def use(mapping, fallback=None):
        # `fallback` — то, что настоящий resolve_for отдаёт для непокрытого дня:
        # он не падает, а берёт ближайший известный лист, предпочитая будущий.
        # Между листами всегда воскресенье, так что этот путь — не редкость,
        # а обычный способ найти следующий лист.
        def fake(day, state_dir, deep=False):
            for title, first, last in mapping:
                if dt.date.fromisoformat(first) <= day <= dt.date.fromisoformat(last):
                    return (title, None)
            if fallback is None:
                raise si.SheetNotFound(f"нет листа на {day}")
            return (fallback, None)

        monkeypatch.setattr(si, "resolve_for", fake)

    return use


def titles(window):
    return [title for title, _ in window]


def test_window_inside_one_sheet_takes_one(tmp_path, index, resolves):
    """Неделя целиком внутри листа — второй не нужен, в сеть не идём."""
    index(("длинный", "2026-09-02", "2026-09-30"))
    resolves([("длинный", "2026-09-02", "2026-09-30")])

    assert titles(si.resolve_window(MONDAY, 8, tmp_path)) == ["длинный"]


def test_window_across_the_edge_takes_the_next_from_memory(tmp_path, index, resolves):
    """Окно перешагивает границу, следующий лист известен — берём оба.

    Без сети: `deep=False`. Ровно этот случай и не работал — между листами
    воскресенье, и память отвечала «не знаю».
    """
    index(CURRENT, NEXT)
    resolves([CURRENT])

    window = si.resolve_window(MONDAY, 8, tmp_path)

    assert titles(window) == [CURRENT[0], NEXT[0]]


def test_unknown_next_sheet_is_not_searched_without_deep(tmp_path, index, resolves):
    """Следующего в памяти нет — в сеть без просьбы не лезем.

    Поиск соседнего листа стоит выгрузки книги; раз в сутки не жалко, каждые
    двадцать минут — расточительство.
    """
    index(CURRENT)
    resolves([CURRENT], fallback=CURRENT[0])

    assert titles(si.resolve_window(MONDAY, 8, tmp_path)) == [CURRENT[0]]


def test_deep_search_finds_the_next_sheet(tmp_path, index, resolves):
    """А с `deep=True` — ищем и находим."""
    index(CURRENT)
    # Спрашивают про воскресенье 13-го — его нет ни в одном листе, и поиск
    # возвращает ближайший будущий. Это не обход правил, это как он и устроен.
    resolves([CURRENT, NEXT], fallback=NEXT[0])

    assert titles(si.resolve_window(MONDAY, 8, tmp_path, deep=True)) == [
        CURRENT[0],
        NEXT[0],
    ]


def test_deep_search_that_finds_nothing_keeps_what_it_has(tmp_path, index, resolves):
    """Следующий лист ещё не опубликован — отдаём что есть, а не падаем.

    Между листами приложение должно показывать текущую неделю, а не пустоту.
    """
    index(CURRENT)
    resolves([CURRENT])

    assert titles(si.resolve_window(MONDAY, 8, tmp_path, deep=True)) == [CURRENT[0]]


def test_the_same_sheet_is_not_added_twice(tmp_path, index, resolves):
    """Поиск вернул тот же лист — в наборе он остаётся один.

    Иначе снимок склеивался бы сам с собой.
    """
    index(CURRENT)
    resolves([CURRENT], fallback=CURRENT[0])

    assert titles(si.resolve_window(MONDAY, 8, tmp_path, deep=True)) == [CURRENT[0]]


def test_manual_override_stops_any_search(tmp_path, index, resolves, monkeypatch):
    """Аварийная настройка перебивает всё: ни памяти, ни сети.

    Её ставят руками, когда поиск сломался, а расписание нужно сегодня, —
    и лезть после этого в книгу было бы прямым непослушанием.
    """
    monkeypatch.setattr(si.settings, "sheet_gid", "656498718")
    index(CURRENT, NEXT)
    resolves([CURRENT])

    assert titles(si.resolve_window(MONDAY, 8, tmp_path, deep=True)) == [CURRENT[0]]


def test_sunday_between_sheets_is_not_a_gap_in_the_window(tmp_path, index, resolves):
    """Воскресенье между листами не мешает собрать окно из двух.

    Отдельным тестом, потому что именно это и было сломано: день после конца
    текущего листа — всегда воскресенье, и спрашивать про него бесполезно.
    """
    memory = index(CURRENT, NEXT)
    sunday = dt.date(2026, 9, 13)

    assert memory.covering(sunday) is None, "воскресенья нет ни в одном листе"
    assert memory.following(dt.date.fromisoformat(CURRENT[2])) == (NEXT[0], None)
