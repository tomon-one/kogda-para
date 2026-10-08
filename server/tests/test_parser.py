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
    """Сломанная дата — отказ: иначе пары нового дня уедут во вчера, с
    повторяющимися номерами и без единой жалобы."""
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


def test_repeated_header_with_shifted_columns_leaves_the_days_below_unread(fixture_csv):
    """Сдвиг колонок после повторного заголовка — худшее, что может случиться.

    Без сверки каждая группа молча получила бы расписание соседа: ошибки нет,
    статус «ok», заметить нельзя. Дни ниже такого заголовка у задетых групп не
    прочитаны; выше него и у остальных групп лист читается.
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
    honest = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)
    for left, right in zip(ordered, ordered[1:]):
        names_row[right] = by_column[left]

    snap = parse_sheet(rows, "фикстура", FIXTURE)
    _assert_unread_below(snap, honest, rows, header[0], set(ordered))
    assert "повторный заголовок" in snap.unread_why[0]


def _assert_unread_below(snap, honest, rows, header_row, columns):
    """Группы колонок `columns` ниже заголовка не прочитаны, выше — как были;
    остальные группы — как были целиком."""
    below = {
        d for d in (_parse_date(r[0]) for r in rows[header_row:]) if d is not None
    }
    assert below and below < set(honest.dates)
    moved = {g.id for g in honest.groups if g.column in columns}
    assert set(snap.unread) == moved
    for group in honest.groups:
        if group.id not in moved:
            assert snap.schedule[group.id] == honest.schedule[group.id]
            continue
        assert set(snap.unread[group.id]) == below
        assert not below & set(snap.schedule[group.id])
        kept = {d: l for d, l in honest.schedule[group.id].items() if d not in below}
        assert snap.schedule[group.id] == kept


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
    как безымянный, а весь лист из-за одной ячейки не отвергается. Имя, которое
    главный заголовок знает в другой колонке, — дело другое: см. сдвиг выше."""
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



@pytest.mark.parametrize(
    "broken",
    [
        "Дисциплина Преподаватели ГД-1125",
        "Дисциплина Преподаватель",
        "Дисциплина Преподаватель —",
        # Опечатка в первом слове — та же шапка.
        "Дисциплна Преподаватель ГД-1125",
        "Дисциплинa Преподаватель ГД-1125",  # латинская «a»
        "дисциплина Преподаватель ГД-1125",
        "Дисциплины Преподаватель ГД-1125",
        "Дисциплна Преподаватель",
    ],
)
def test_broken_name_in_main_header_keeps_the_group(fixture_csv, broken):
    """Опечатка, прочерк или стёртое имя в одной ячейке главного заголовка не
    убирают группу из снимка молча. Опечатка в слове шапки — та же шапка, а
    имя берётся из повторного заголовка."""
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
    """Граница — числом: одно чужое имя — опечатка, два — сдвиг. Переставлены
    ровно две колонки: перестановка во всех пяти держала бы порог только до пяти."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    groups = build_column_map(rows, min_groups=FIXTURE.min_groups)
    by_column = {g.column: g.name for g in groups}
    _, columns, names_row = _columnar_header(rows)
    ordered = sorted(c for c in columns if c in by_column and c < len(names_row))
    names_row[ordered[1]] = by_column[ordered[0]]
    names_row[ordered[2]] = by_column[ordered[1]]
    honest = parse_sheet([list(r) for r in collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)],
                         "фикстура", FIXTURE)
    snap = parse_sheet(rows, "фикстура", FIXTURE)
    # Не прочитаны и колонки с чужим именем, и та, откуда имя ушло.
    _assert_unread_below(snap, honest, rows, _columnar_header(rows)[0], set(ordered[:3]))
    assert "таких колонок 2" in snap.unread_why[0]


def test_neighbour_name_in_the_main_header_is_resolved_by_the_repeated_one(fixture_csv):
    """«ИСП-924/1» над ИСП-924/2 в главном заголовке — не отказ у всех: обе
    колонки безымянны в главном, а имена им даёт повторный заголовок."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    honest = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)
    rows[0][6] = "Дисциплина Преподаватель БП-1126"
    snap = parse_sheet(rows, "фикстура", FIXTURE)
    placed = {(g.name, g.column) for g in snap.groups}
    assert placed == {(g.name, g.column) for g in honest.groups}
    assert snap.schedule == honest.schedule


def test_template_placeholder_in_one_cell_is_an_empty_slot(fixture_csv):
    """«Дисциплина» / «Преподаватель» в клетке одной группы посреди дня —
    заготовка незаполненной клетки: пары там нет, а строки остальных групп
    не выкидываются."""
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
    """Строка с припиской между строкой пары и строкой времени — не строка
    преподавателей: иначе они пропадут у всей строки пары."""
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
    месте — это блок без шапки, а не пустое место: иначе группа молча уйдёт
    в 404 при ok."""
    from whensclass.parser.groups import header_blocks

    row = ["", "", "Дисциплина Преподаватель А-1", "", "", "Ауд.",
           "Дисц.", "", "", "Ауд.",
           "Дисциплина Преподаватель А-3", "", "", "Ауд.",
           "", "", "", "Ауд."]
    assert header_blocks([row], min_groups=2) == {2, 6, 10, 14}
    # Колонка «Ауд.» внутри блока — не блок.
    assert 3 not in header_blocks([row], min_groups=2)



def _week_from_another_template(fixture_csv, days: int):
    """Лист, где под повторным заголовком первые `days` дней вставлены из шаблона
    без БП-926/1: заголовок и эти дни — на блок левее, дальше всё на местах.

    Дни под заголовком повторяют дни над ним: предметы у групп те же, что уже
    были, — как в настоящем листе, где недель много. Возвращает честный разбор
    того же листа без сдвига, сдвинутые строки и даты под заголовком.
    """
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    header = _columnar_header(rows)[0]
    above = [i for i, r in enumerate(rows) if i < header and _parse_date(r[0]) is not None]
    starts = [i for i, r in enumerate(rows) if i > header and _parse_date(r[0]) is not None]
    for k, start in enumerate(starts):
        source = above[k % len(above)]
        for step in range(12):
            rows[start + step][2:] = list(rows[source + step][2:])
    honest = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)
    end = starts[days] if days < len(starts) else len(rows)
    for row in rows[header:end]:
        row += [""] * (22 - len(row))
        del row[6:10]
        row += [""] * 4
    return honest, rows, [_parse_date(rows[i][0]) for i in starts]


def test_week_pasted_without_one_group_is_read_by_its_own_header(fixture_csv, monkeypatch):
    """Запасной способ: главный заголовок и повторный спорят — день читается тем,
    при котором группы получают свои предметы. Сдвинутые дни — по повторному,
    дни после них — по главному; группа, которой в повторном нет, не прочитана."""
    from whensclass.parser import csv_schedule

    monkeypatch.setattr(csv_schedule, "SETTLE_MIN_LESSONS", 8)
    honest, rows, below = _week_from_another_template(fixture_csv, days=2)
    snap = parse_sheet(rows, "фикстура", FIXTURE)

    assert snap.unread == {"bp-926-1": {below[0]: False, below[1]: False}}
    assert "БП-926/1" in snap.unread_why[0] and "группы нет в повторном" in snap.unread_why[0]
    for group in honest.groups:
        want = dict(honest.schedule[group.id])
        if group.id == "bp-926-1":
            want.pop(below[0], None), want.pop(below[1], None)
        assert snap.schedule[group.id] == want, group.name


def test_too_little_to_judge_leaves_the_days_unread(fixture_csv):
    """На горстке пар содержимое ничего не доказывает: дни не прочитаны."""
    _, rows, below = _week_from_another_template(fixture_csv, days=2)
    snap = parse_sheet(rows, "фикстура", FIXTURE)
    assert set(snap.unread) >= {"bp-926-1", "gd-1125", "dp-923", "isp-924-2"}
    assert all(set(days) == set(below) for days in snap.unread.values())
    assert "повторный заголовок" in snap.unread_why[0]
