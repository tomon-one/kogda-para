"""Обход листа целиком — на урезанной фикстуре живой таблицы."""

import datetime as dt

import pytest

from whensclass.domain.models import SourceFormatChanged
from whensclass.parser.csv_schedule import FIXTURE, parse_sheet
from whensclass.parser.dates import _parse_date
from whensclass.parser.export import collapse_export, parse_csv, read_csv
from whensclass.parser.groups import build_column_map, find_header_rows, split_group_names


@pytest.fixture(scope="module")
def snapshot(fixture_csv):
    return parse_csv(fixture_csv, "фикстура 02.09", FIXTURE)


def test_groups_found(snapshot):
    names = {g.name for g in snapshot.groups}
    assert {"ИСП-924/2", "БП-1126", "ДП-923", "ДП-1124"} <= names


def test_compound_column_serves_two_groups(snapshot):
    """«ДП-923 и ДП-1124» — одна колонка на две группы, расписания совпадают."""
    by_name = {g.name: g for g in snapshot.groups}
    assert by_name["ДП-923"].column == by_name["ДП-1124"].column
    assert snapshot.schedule[by_name["ДП-923"].id] == snapshot.schedule[by_name["ДП-1124"].id]


def test_split_group_names():
    assert split_group_names("ДП-923 и ДП-1124") == ["ДП-923", "ДП-1124"]
    assert split_group_names("ИСП-924/2") == ["ИСП-924/2"]


def test_dates_are_the_two_weeks_of_the_sheet(snapshot):
    assert snapshot.dates[0] == dt.date(2026, 9, 2)
    assert snapshot.dates[-1] == dt.date(2026, 9, 12)
    # воскресений в расписании нет
    assert all(d.weekday() != 6 for d in snapshot.dates)
    assert snapshot.dates == sorted(snapshot.dates)


def test_date_only_on_first_row_of_the_day(snapshot):
    """Дата стоит в одной строке дня, но пары набираются за весь день."""
    isp = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    day = snapshot.schedule[isp.id][dt.date(2026, 9, 10)]
    assert [lesson.number for lesson in day] == [3, 4, 5, 6]


def test_lessons_are_sorted_by_number(snapshot):
    for by_date in snapshot.schedule.values():
        for lessons in by_date.values():
            assert [x.number for x in lessons] == sorted(x.number for x in lessons)


def test_repeated_header_inside_the_sheet_is_skipped(fixture_csv):
    """Внутри листа встречается второй заголовок, набранный столбиком.

    Если его не пропустить, «Дисциплина» попадёт в расписание как предмет.
    """
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    groups = build_column_map(rows, min_groups=FIXTURE.min_groups)
    skip = find_header_rows(rows, groups, min_groups=FIXTURE.min_groups)
    assert 0 in skip
    assert len(skip) > 1, "повторный заголовок не найден"

    snapshot = parse_csv(fixture_csv, "фикстура", FIXTURE)
    subjects = {
        lesson.subject
        for by_date in snapshot.schedule.values()
        for lessons in by_date.values()
        for lesson in lessons
    }
    assert "Дисциплина" not in subjects
    assert "Преподаватель" not in subjects


def test_webinar_link_lands_in_url(snapshot):
    urls = [
        lesson
        for by_date in snapshot.schedule.values()
        for lessons in by_date.values()
        for lesson in lessons
        if lesson.url
    ]
    assert urls, "в фикстуре должны быть пары с вебинаром"
    assert all(x.room is None for x in urls)
    assert all(x.url.startswith("https://") for x in urls)


def test_cancellation_written_inside_subject(snapshot):
    cancelled = [
        lesson
        for by_date in snapshot.schedule.values()
        for lessons in by_date.values()
        for lesson in lessons
        if lesson.cancelled
    ]
    assert cancelled, "в фикстуре должна быть отменённая пара"
    assert any(x.note for x in cancelled), "причина отмены должна сохраняться"
    assert all("тмена" not in x.subject for x in cancelled)


def test_broken_sheet_raises_rather_than_guesses():
    """Лучше отказаться разбирать, чем показать чужие пары."""
    with pytest.raises(SourceFormatChanged):
        parse_csv("а,б,в\n1,2,3\n", "мусор", FIXTURE)


def test_column_step_must_be_four():
    header = ["", "№", "Дисциплина Преподаватель А-1", "", "Ауд. ",
              "Дисциплина Преподаватель Б-2", "", "", "Ауд. ",
              "Дисциплина Преподаватель В-3", "", "", "Ауд. "]
    with pytest.raises(SourceFormatChanged):
        build_column_map([header], min_groups=3)


def test_merged_snapshot_keeps_both_weeks(fixture_csv):
    """Соседние листы склеиваются: неделя вперёд часто их перешагивает."""
    first = parse_csv(fixture_csv, "лист А", FIXTURE)
    second = parse_csv(fixture_csv, "лист Б", FIXTURE)

    # Подменяем даты второго листа на следующие две недели.
    shift = dt.timedelta(days=14)
    second.dates = [d + shift for d in second.dates]
    second.schedule = {
        gid: {day + shift: lessons for day, lessons in by_date.items()}
        for gid, by_date in second.schedule.items()
    }

    merged = first.merged_with(second)

    assert merged.dates == sorted(set(first.dates) | set(second.dates))
    assert merged.coverage == (first.dates[0], second.dates[-1])
    assert {g.id for g in merged.groups} == {g.id for g in first.groups}
    assert merged.total_lessons() == first.total_lessons() + second.total_lessons()


def test_merge_prefers_the_current_sheet(fixture_csv):
    """Если день есть в обоих листах, верим текущему: его правят свежее."""
    current = parse_csv(fixture_csv, "текущий", FIXTURE)
    other = parse_csv(fixture_csv, "соседний", FIXTURE)
    group = next(g for g in current.groups if g.name == "ИСП-924/2")
    day = current.dates[0]
    other.schedule[group.id][day] = []

    merged = current.merged_with(other)

    assert merged.schedule[group.id][day] == current.schedule[group.id][day]


# --- Два молчаливых искажения ----------------------------------------------


def test_broken_date_stops_the_parse(fixture_csv):
    """Сломанная дата раньше приклеивала новый день к предыдущему.

    Ячейка не узнавалась, текущий день не менялся, и пары нового дня уезжали
    во вчера — с повторяющимися номерами и без единой жалобы.
    """
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    for row in rows:
        if row and row[0].strip().startswith("03.09.2026"):
            row[0] = "32.13.2026 вторник"
            break
    else:
        pytest.fail("в фикстуре не нашлось строки с датой 03.09.2026")

    with pytest.raises(SourceFormatChanged, match="не разобралась"):
        parse_sheet(rows, "фикстура", FIXTURE)


@pytest.mark.parametrize("written", [
    "02.09.2026 среда",
    "2.09.2026 среда",
    "02.09.2026, среда",
    "02.09.2026",
])
def test_date_written_by_hand_is_still_understood(written):
    """Дату пишут руками: запятая, пропущенный ноль, забытый день недели."""
    assert _parse_date(written) == dt.date(2026, 9, 2)


def _columnar_header(rows):
    """(индекс строки «Дисциплина», её колонки, строка с именами групп) или None."""
    for i, row in enumerate(rows):
        columns = [c for c, v in enumerate(row) if (v or "").strip() == "Дисциплина"]
        below = rows[i + 1] if i + 1 < len(rows) else []
        if columns and any(
            (below[c] or "").strip() == "Преподаватель" for c in columns if c < len(below)
        ):
            return i, columns, rows[i + 2] if i + 2 < len(rows) else []
    return None


def test_repeated_header_with_shifted_columns_stops_the_parse(fixture_csv):
    """Сдвиг колонок после повторного заголовка — худшее, что может случиться.

    Раньше три строки такого заголовка пропускались вслепую, и группа молча
    получала расписание соседа: ошибки нет, статус «ok», заметить нельзя.
    """
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    groups = build_column_map(rows, min_groups=FIXTURE.min_groups)
    by_column = {g.column: g.name for g in groups}

    header = _columnar_header(rows)
    if header is None:
        pytest.skip("в фикстуре нет повторного заголовка «столбиком»")
    _, columns, names_row = header
    # Сдвиг блока переставляет все имена правее вставки: в каждой колонке
    # оказывается имя соседа слева.
    ordered = sorted(c for c in columns if c in by_column and c < len(names_row))
    for left, right in zip(ordered, ordered[1:]):
        names_row[right] = by_column[left]

    with pytest.raises(SourceFormatChanged, match="повторный заголовок"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_single_neighbour_name_in_repeated_header_is_a_typo(fixture_csv, caplog):
    """Одно чужое имя — опечатка «ИСП-924/1» вместо «ИСП-924/2», а не сдвиг:
    сдвиг переставляет все имена."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    groups = build_column_map(rows, min_groups=FIXTURE.min_groups)
    by_column = {g.column: g.name for g in groups}
    header = _columnar_header(rows)
    if header is None:
        pytest.skip("в фикстуре нет повторного заголовка «столбиком»")
    _, columns, names_row = header
    target = next(c for c in columns if c in by_column and c < len(names_row))
    names_row[target] = next(name for c, name in by_column.items() if c != target)
    with caplog.at_level("WARNING"):
        parse_sheet(rows, "фикстура", FIXTURE)
    assert "опечаткой" in caplog.text


def test_repeated_header_with_renamed_group_is_tolerated(fixture_csv, caplog):
    """Имя, которого нет ни в одной колонке главного заголовка, — переименование.

    11 сентября 2026 колледж поправил имя группы в главном заголовке и не
    тронул его в повторных. Колонка та же, пары те же — падать не из-за чего,
    но в журнале об этом должно быть сказано.
    """
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    groups = build_column_map(rows, min_groups=FIXTURE.min_groups)
    by_column = {g.column: g.name for g in groups}
    untouched = parse_csv(fixture_csv, "фикстура", FIXTURE)

    header = _columnar_header(rows)
    if header is None:
        pytest.skip("в фикстуре нет повторного заголовка «столбиком»")
    _, columns, names_row = header
    target = next(c for c in columns if c in by_column and c < len(names_row))
    names_row[target] = by_column[target] + "-НСК"

    with caplog.at_level("WARNING", logger="whensclass.parser.groups"):
        snapshot = parse_sheet(rows, "фикстура", FIXTURE)

    assert snapshot.schedule == untouched.schedule, "пары должны остаться прежними"
    assert {g.id for g in snapshot.groups} == {g.id for g in untouched.groups}
    assert any("переименован" in r.message for r in caplog.records)


def test_repeated_header_with_unknown_column_is_a_broken_main_header(fixture_csv, caplog):
    """Колонка, которой нет в главном заголовке, с именем, которого там нет нигде, —
    опечатка в главном заголовке («Преподаватели», стёртое имя): блок пропущен
    как безымянный, а весь лист из-за одной ячейки не отвергается. Имя, которое главный заголовок знает в другой колонке, — дело
    другое: см. сдвиг выше."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    groups = build_column_map(rows, min_groups=FIXTURE.min_groups)
    known = {g.column for g in groups}

    header = _columnar_header(rows)
    if header is None:
        pytest.skip("в фикстуре нет повторного заголовка «столбиком»")
    i, columns, names_row = header
    # Приписываем блок справа от последней группы: заголовок его объявляет,
    # а главный заголовок про него не знает.
    col = max(known) + 4
    for r in (rows[i], rows[i + 1], names_row):
        r.extend([""] * (col + 1 - len(r)))
    rows[i][col] = "Дисциплина"
    rows[i + 1][col] = "Преподаватель"
    names_row[col] = "НОВАЯ-999"

    with caplog.at_level("WARNING"):
        parse_sheet(rows, "фикстура", FIXTURE)
    assert "считаю блок безымянным" in caplog.text


def test_export_header_is_collapsed_like_gviz_did(fixture_csv):
    """Сырой экспорт: шапка столбиком в три строки, выше — пустые строки с рамками.

    Разбор построен на форме gviz — «Дисциплина Преподаватель БП-1126» одной
    строкой. Адаптер собирает её сам и выбрасывает пустые строки; повторные
    заголовки внутри листа не трогает — они и раньше шли столбиком.
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
    """Ради этого и переход: ссылки и «55/2» в «числовой» колонке gviz выбрасывал."""
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



@pytest.mark.parametrize(
    "broken",
    [
        "Дисциплина Преподаватели ГД-1125",
        "Дисциплина Преподаватель",
        "Дисциплина Преподаватель —",
        # Опечатка в первом слове — блок не становился ни группой, ни
        # безымянным, и группа молча уходила в 404.
        "Дисциплна Преподаватель ГД-1125",
        "Дисциплинa Преподаватель ГД-1125",  # латинская «a»
        "дисциплина Преподаватель ГД-1125",
        "Дисциплины Преподаватель ГД-1125",
        "Дисциплна Преподаватель",
    ],
)
def test_broken_name_in_main_header_keeps_the_group(fixture_csv, broken):
    """Опечатка, прочерк или стёртое имя в одной
    ячейке главного заголовка молча убирали группу из снимка при ok. Опечатка
    во втором слове — та же шапка, а имя берётся из повторного заголовка."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    rows[0][10] = broken
    snap = parse_sheet(rows, "фикстура", FIXTURE)
    group = next(g for g in snap.groups if g.name == "ГД-1125")
    assert group.column == 10
    assert snap.schedule[group.id], "пары группы на месте"
    assert [g.column for g in snap.groups] == sorted(g.column for g in snap.groups)
    assert not snap.unnamed


def test_nameless_block_with_lessons_is_reported(fixture_csv):
    """Имени нет нигде — ни в главном, ни в повторном: блок безымянный, но
    пары под ним считаются, и служба по ним узнаёт пропавшую группу."""
    from whensclass.service.gates import _check_lost_names

    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    honest = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)
    honest.sheet_columns = {"1": {g.id: g.column for g in honest.groups}}
    i, _, names_row = _columnar_header(rows)
    rows[0][10] = "Дисциплина Преподаватель"
    names_row[10] = ""
    broken = parse_sheet(rows, "фикстура", FIXTURE)
    assert "ГД-1125" not in {g.name for g in broken.groups}
    assert broken.unnamed.get(10, 0) > 0
    with pytest.raises(SourceFormatChanged, match="пропало имя группы ГД-1125"):
        _check_lost_names(honest, broken, "1")

    # Пустой блок без имени — колонка под будущую группу, не пропажа.
    for r in rows[1:]:
        if len(r) > 10 and r is not names_row:
            r[10] = ""
    empty = parse_sheet(rows, "фикстура", FIXTURE)
    assert not empty.unnamed
    _check_lost_names(honest, empty, "1")


def test_two_neighbour_names_in_repeated_header_are_a_shift(fixture_csv):
    """Граница — числом: одно чужое имя — опечатка, два — сдвиг. Прежний тест
    переставлял имена во всех пяти колонках и держал порог только до пяти."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    groups = build_column_map(rows, min_groups=FIXTURE.min_groups)
    by_column = {g.column: g.name for g in groups}
    _, columns, names_row = _columnar_header(rows)
    ordered = sorted(c for c in columns if c in by_column and c < len(names_row))
    names_row[ordered[1]] = by_column[ordered[0]]
    names_row[ordered[2]] = by_column[ordered[1]]
    with pytest.raises(SourceFormatChanged, match="таких колонок 2"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_neighbour_name_in_the_main_header_is_resolved_by_the_repeated_one(fixture_csv):
    """«ИСП-924/1» над ИСП-924/2 в главном
    заголовке давало «объявлена дважды» и отказ у всех. Теперь обе колонки
    безымянны в главном, а имена им даёт повторный заголовок."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    honest = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)
    rows[0][6] = "Дисциплина Преподаватель БП-1126"
    snap = parse_sheet(rows, "фикстура", FIXTURE)
    placed = {(g.name, g.column) for g in snap.groups}
    assert placed == {(g.name, g.column) for g in honest.groups}
    assert snap.schedule == honest.schedule


def test_template_placeholder_in_one_cell_is_an_empty_slot(fixture_csv):
    """«Дисциплина» / «Преподаватель» в клетке
    одной группы посреди дня выкидывали три строки у всех групп, и лист
    отвергался. Это заготовка незаполненной клетки — пары там нет."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    honest = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)
    group = next(g for g in honest.groups if g.name == "ИСП-924/2")
    i = next(i for i, r in enumerate(rows) if len(r) > 1 and r[1].strip() == "2" and i > 5)
    rows[i][group.column] = "Дисциплина"
    rows[i + 1][group.column] = "Преподаватель"
    rows[i][group.column + 3] = ""
    snap = parse_sheet(rows, "фикстура", FIXTURE)
    others = [g.id for g in honest.groups if g.column != group.column]
    assert all(snap.schedule[g] == honest.schedule[g] for g in others)
    lessons = [x for by in snap.schedule[group.id].values() for x in by]
    assert all(x.subject != "Дисциплина" for x in lessons)


def test_row_inserted_before_the_bell_row_does_not_steal_the_teachers(fixture_csv):
    """Строка с припиской между строкой пары и
    строкой времени становилась строкой преподавателей, и у всей строки пары
    они пропадали — а у преподавателей пропадала пара."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    honest = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)
    group = next(g for g in honest.groups if g.name == "ИСП-924/2")
    i = next(
        i for i, r in enumerate(rows)
        if len(r) > 1 and r[1].strip().isdigit() and i + 1 < len(rows)
        and len(rows[i + 1]) > 1 and rows[i + 1][1].strip()[:1].isdigit()
        and not rows[i + 1][1].strip().isdigit()
    )
    note = [""] * len(rows[i])
    note[group.column] = "перенос с 23.09"
    rows.insert(i + 1, note)
    snap = parse_sheet(rows, "фикстура", FIXTURE)
    assert snap.schedule == honest.schedule


def test_block_with_erased_head_is_still_a_block():
    """Ячейку «Дисциплина …» стёрли или испортили («Дисц.»), а «Ауд.» блока на
    месте — это блок без шапки, а не пустое место: иначе группа молча уходила
    в 404 при ok."""
    from whensclass.parser.groups import header_blocks

    row = ["", "", "Дисциплина Преподаватель А-1", "", "", "Ауд.",
           "Дисц.", "", "", "Ауд.",
           "Дисциплина Преподаватель А-3", "", "", "Ауд.",
           "", "", "", "Ауд."]
    assert header_blocks([row], min_groups=2) == {2, 6, 10, 14}
    # Колонка «Ауд.» внутри блока — не блок.
    assert 3 not in header_blocks([row], min_groups=2)

