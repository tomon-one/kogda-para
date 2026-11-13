"""Сырой экспорт листа: шапка столбиком, текст, который gviz терял, строки листа."""

from whensclass.parser.csv_schedule import FIXTURE
from whensclass.parser.export import collapse_export, parse_csv, read_csv


def test_export_header_is_collapsed_like_gviz_did(fixture_csv):
    """Сырой экспорт: шапка столбиком в три строки, выше — пустые строки с рамками.

    Разбор построен на форме gviz — «Дисциплина Преподаватель БП-1126» одной
    строкой. Адаптер собирает её сам и выбрасывает пустые строки; повторные
    заголовки внутри листа не трогает: разбор читает их столбиком.
    """
    raw = read_csv(fixture_csv)
    assert raw[2][2] == "Дисциплина" and raw[3][2] == "Преподаватель"
    rows = collapse_export(raw, FIXTURE.min_groups)
    assert rows[0][2] == "Дисциплина Преподаватель БП-1126"
    assert rows[0][1] == "№" and rows[0][5] == "Ауд."
    assert rows[1][0].startswith("02.09.2026"), "первая строка тела — первый день"
    assert all(any(c.strip() for c in r) for r in rows), "пустых строк не осталось"
    # Повторный заголовок столбиком остался на месте.
    assert any(sum(1 for c in r if c.strip() == "Дисциплина") >= FIXTURE.min_groups for r in rows[1:])


def test_already_collapsed_sheet_is_left_alone(fixture_csv):
    """Лист в форме gviz (старые фикстуры) адаптер не трогает."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    again = collapse_export([list(r) for r in rows], FIXTURE.min_groups)
    assert again == rows


def test_export_keeps_the_text_gviz_dropped(fixture_csv):
    """Ссылки и «55/2» в «числовой» колонке gviz выбрасывает, сырой экспорт — нет."""
    snapshot = parse_csv(fixture_csv, "фикстура", FIXTURE)
    group = next(g for g in snapshot.groups if g.name == "БП-926/1")
    rooms = {x.room for lessons in snapshot.schedule[group.id].values() for x in lessons}
    urls = {x.url for lessons in snapshot.schedule[group.id].values() for x in lessons if x.url}
    assert "55/2" in rooms and "Восход 208" in rooms
    assert urls, "у БП-926/1 в листе стоят ссылки на вебинар"


def test_export_remembers_sheet_rows_of_days(fixture_csv):
    """Номера строк дней — как в Sheets: 6, 18, 30 — для ссылки к ячейке."""
    from whensclass.parser.export import parse_export

    snapshot = parse_export(fixture_csv, "фикстура", gid="656498718", limits=FIXTURE)
    rows = [snapshot.places[d].row for d in sorted(snapshot.places)]
    assert rows[:3] == [6, 18, 30]
    assert all(p.gid == "656498718" for p in snapshot.places.values())


def test_link_leads_to_the_retyped_copy_of_a_week(fixture_csv):
    """Неделя набрана заново внизу листа и читается оттуда — ссылка «открыть
    таблицу» ведёт туда же, а не к прежней копии сверху."""
    import csv
    import io

    from whensclass.parser.export import parse_export

    raw = read_csv(fixture_csv)
    starts = [i for i, r in enumerate(raw) if r and r[0].strip()[:2].isdigit()]
    copy = [list(r) for r in raw[starts[0]:starts[4]]]
    out = io.StringIO()
    csv.writer(out, lineterminator="\n").writerows(raw + copy)
    snapshot = parse_export(out.getvalue(), "фикстура", gid="656498718", limits=FIXTURE)
    first = min(snapshot.places)
    assert snapshot.places[first].row == len(raw) + 1
    later = sorted(snapshot.places)[4]
    assert snapshot.places[later].row == starts[4] + 1
