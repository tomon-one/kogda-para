"""Дни под повторным заголовком с другой раскладкой: запасной разбор, подгруппы."""

import pytest

from test_parser import _columnar_header
from whensclass.domain.models import a1_column
from whensclass.parser.csv_schedule import FIXTURE, parse_sheet
from whensclass.parser.dates import _parse_date
from whensclass.parser.export import collapse_export, read_csv
from whensclass.parser.groups import group_id, split_group_names


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
    from whensclass.parser import sections

    monkeypatch.setattr(sections, "SETTLE_MIN_LESSONS", 8)
    honest, rows, below = _week_from_another_template(fixture_csv, days=2)
    snap = parse_sheet(rows, "фикстура", FIXTURE)

    assert snap.unread == {"bp-926-1": {below[0]: False, below[1]: False}}
    assert "БП-926/1" in snap.unread_why[0] and "группы нет в повторном" in snap.unread_why[0]
    for group in honest.groups:
        want = dict(honest.schedule[group.id])
        if group.id == "bp-926-1":
            want.pop(below[0], None), want.pop(below[1], None)
        assert snap.schedule[group.id] == want, group.name


def test_days_read_by_the_repeated_header_remember_their_columns(fixture_csv, monkeypatch):
    """Ссылка «открыть таблицу» в днях, прочитанных по повторному заголовку,
    ведёт в колонку группы там, а не в колонку главного заголовка."""
    import datetime as dt

    from whensclass.api.payloads import schedule_payload
    from whensclass.parser import sections

    monkeypatch.setattr(sections, "SETTLE_MIN_LESSONS", 8)
    honest, rows, below = _week_from_another_template(fixture_csv, days=2)
    snap = parse_sheet(rows, "фикстура", FIXTURE)
    moved = {gid for gid, days in snap.day_columns.items() if set(days) == set(below[:2])}
    assert moved
    gid = sorted(moved)[0]
    group = next(g for g in snap.groups if g.id == gid)
    assert snap.day_columns[gid][below[0]] != group.column
    body = schedule_payload(snap, gid, below[0], 1, dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc))
    cols = {x.get("col") for x in body["days"][0]["l"]}
    assert cols == {a1_column(snap.day_columns[gid][below[0]])}
    # У преподавателя пары этого дня ведут туда же.
    from whensclass.domain.teachers import build_index

    index = build_index(snap)
    there = [e for days in map(index.days, index.names) for e in days.get(below[0], [])
             if group.name in e.group_name.split(", ")]
    assert there and {e.column for e in there} == {snap.day_columns[gid][below[0]]}


def test_block_erased_under_a_shifted_header_is_unread_not_empty(fixture_csv, monkeypatch):
    """Под сдвинутым заголовком стёрли блок вместе с именем: группа, чья это
    колонка по главному, не прочитана — пустая неделя была бы молчаливой ложью."""
    from whensclass.parser import sections

    monkeypatch.setattr(sections, "SETTLE_MIN_LESSONS", 8)
    _, rows, below = _week_from_another_template(fixture_csv, days=2)
    header = _columnar_header(rows)[0]
    end = next(i for i, r in enumerate(rows) if _parse_date(r[0]) == below[2])
    whose = split_group_names(rows[header + 2][6])
    for row in rows[header + 2:end]:
        row[6:10] = [""] * 4
    snap = parse_sheet(rows, "фикстура", FIXTURE)

    assert len(whose) == 1
    days = {below[0]: False, below[1]: False}
    assert snap.unread == {"bp-926-1": days, group_id(whose[0]): days}


def test_thin_history_above_the_header_is_helped_by_the_previous_snapshot(fixture_csv, monkeypatch):
    """Новый лист: над сдвинутым заголовком почти нет своих пар, судить по
    ним не о чем. Предметы групп из прежнего снимка решают, а без него дни
    остаются непрочитанными у всех."""
    from whensclass.parser import sections

    monkeypatch.setattr(sections, "SETTLE_MIN_LESSONS", 8)
    honest, rows, below = _week_from_another_template(fixture_csv, days=99)
    header = _columnar_header(rows)[0]
    for i in range(header):
        if _parse_date(rows[i][0]) is not None:
            for step in range(12):
                rows[i + step][2:] = [""] * (len(rows[i + step]) - 2)
    blind = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)
    assert len(blind.unread) > 1
    snap = parse_sheet(rows, "фикстура", FIXTURE, previous=honest)
    assert set(snap.unread) == {"bp-926-1"}
    for group in honest.groups:
        if group.id != "bp-926-1":
            assert {d: snap.schedule[group.id].get(d) for d in below} == \
                {d: honest.schedule[group.id].get(d) for d in below}, group.name


def test_too_little_to_judge_leaves_the_days_unread(fixture_csv):
    """На горстке пар содержимое ничего не доказывает: дни не прочитаны."""
    _, rows, below = _week_from_another_template(fixture_csv, days=2)
    snap = parse_sheet(rows, "фикстура", FIXTURE)
    assert set(snap.unread) >= {"bp-926-1", "gd-1125", "dp-923", "isp-924-2"}
    assert all(set(days) == set(below) for days in snap.unread.values())
    assert "повторный заголовок" in snap.unread_why[0]


def test_group_split_into_subgroups_under_the_header_is_read_by_their_names(fixture_csv, monkeypatch):
    """Под повторным заголовком колледж разделил группу на подгруппы («С-926» на
    «С-926/1» и «С-926/2»), и всё правее съехало на блок. Пары подгрупп в днях
    под заголовком — под их именами, а в остальных — пары и пометки группы:
    там лист пишет её целиком. Сама группа под заголовком не прочитана, а не
    получает молча пары первой подгруппы."""
    from whensclass.parser import sections

    monkeypatch.setattr(sections, "SETTLE_MIN_LESSONS", 8)
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    header = _columnar_header(rows)[0]
    # Дни под заголовком повторяют дни над ним — как в листе, где недель много.
    above = [i for i, r in enumerate(rows) if i < header and _parse_date(r[0]) is not None]
    starts = [i for i, r in enumerate(rows) if i > header and _parse_date(r[0]) is not None]
    for k, start in enumerate(starts):
        for step in range(12):
            rows[start + step][2:] = list(rows[above[k % len(above)] + step][2:])
    honest = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)
    below = [_parse_date(rows[i][0]) for i in starts]
    # ГД-1125 — колонка 10: две подгруппы вместо неё, у второй те же пары.
    for row in rows[header:]:
        row += [""] * (26 - len(row))
        row[10:14] = row[10:14] * 2
    rows[header + 2][10], rows[header + 2][14] = "ГД-1125/1", "ГД-1125/2"
    snap = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)

    # Дни, которые запасной способ прочитал по повторному заголовку (день с
    # малым числом пар он не решает — тот не прочитан у всех съехавших).
    read = [day for day in below if day not in snap.unread.get("dp-923", {})]
    assert len(read) >= 4
    gd = honest.schedule["gd-1125"]
    for k in (1, 2):
        gid = f"gd-1125-{k}"
        assert any(g.id == gid and g.name == f"ГД-1125/{k}" for g in snap.groups)
        whole = {d: x for d, x in snap.schedule["gd-1125"].items() if d not in read}
        assert whole and snap.schedule[gid] == {**whole, **{day: gd[day] for day in read if gd.get(day)}}
        assert set(snap.unread.get(gid, {})) == set(below) - set(read)
    ids = [g.id for g in snap.groups]
    assert ids[ids.index("gd-1125") + 1:ids.index("gd-1125") + 3] == ["gd-1125-1", "gd-1125-2"]
    assert set(snap.unread["gd-1125"]) == set(below)
    assert snap.schedule["gd-1125"] == {d: x for d, x in gd.items() if d not in below}
    for group in honest.groups:
        if group.id != "gd-1125":
            for day in read:
                assert snap.schedule[group.id].get(day) == honest.schedule[group.id].get(day), group.name

    # Следующая версия не решила ни одного дня — подгруппы прежнего снимка не
    # пропадают: их дни под заголовком не прочитаны, как у группы.
    monkeypatch.setattr(sections, "SETTLE_MIN_LESSONS", 10**6)
    again = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE, previous=snap)
    ids = [g.id for g in again.groups]
    assert ids[ids.index("gd-1125") + 1:ids.index("gd-1125") + 3] == ["gd-1125-1", "gd-1125-2"]
    assert set(again.unread["gd-1125-1"]) == set(below)



def test_subgroup_in_nameless_block_of_the_main_header_gets_group_lessons(fixture_csv, monkeypatch):
    """Главный заголовок держит пустой блок без имени (колонка под будущую
    группу), а повторный ставит туда вторую подгруппу. Это раздел группы, а не
    стёртое имя: над заголовком подгруппа получает пары группы, как обе её
    сестры, а не «пар нет»."""
    from whensclass.parser import sections

    monkeypatch.setattr(sections, "SETTLE_MIN_LESSONS", 8)
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    header = _columnar_header(rows)[0]
    # Дни под заголовком повторяют дни над ним — как в листе, где недель много.
    above = [i for i, r in enumerate(rows) if i < header and _parse_date(r[0]) is not None]
    starts = [i for i, r in enumerate(rows) if i > header and _parse_date(r[0]) is not None]
    for k, start in enumerate(starts):
        for step in range(12):
            rows[start + step][2:] = list(rows[above[k % len(above)] + step][2:])
    for r in rows:
        r += [""] * 4
    rows[0][22:26] = ["Дисциплина Преподаватель", "", "", "Ауд."]
    for k, word in enumerate(("Дисциплина", "Преподаватель")):
        rows[header + k][22] = word
    # Под заголовком ещё и две группы правее поменялись местами: без этого
    # повторный заголовок с главным не спорит и дни под ним не решаются.
    names = rows[header + 2]
    names[10], names[22] = "ГД-1125/1", "ГД-1125/2"
    names[14:22] = names[18:22] + names[14:18]
    for row in rows[header + 3:]:
        row[14:26] = row[18:22] + row[14:18] + row[10:14]
    snap = parse_sheet(rows, "фикстура", FIXTURE)

    below = {_parse_date(rows[i][0]) for i in starts}
    above = [d for d in snap.dates if d not in below]
    assert not set(snap.unread.get("gd-1125-2", {})) - below
    whole = snap.schedule["gd-1125"]
    assert above and all(whole.get(d) for d in above[:3])
    for k in (1, 2):
        gid = f"gd-1125-{k}"
        assert {d: snap.schedule[gid].get(d) for d in above} == {d: whole.get(d) for d in above}, gid

def test_unknown_name_that_is_not_a_subgroup_is_not_a_new_group(fixture_csv, monkeypatch):
    """Под сдвинутым заголовком незнакомое имя без «/N» его группы — не новая
    группа: старое имя из чужого шаблона дало бы двойника."""
    from whensclass.parser import sections

    monkeypatch.setattr(sections, "SETTLE_MIN_LESSONS", 8)
    _, rows, below = _week_from_another_template(fixture_csv, days=2)
    header = _columnar_header(rows)[0]
    end = next(i for i, r in enumerate(rows) if _parse_date(r[0]) == below[2])
    for row in rows[header:end]:
        block = row[6:10]
        row[6:10] = block * 3
    for k, name in enumerate(("ГД(а)-1125", "ГД-1125/2", "ДП-777/1"), 1):
        rows[header + 2][2 + 4 * k] = name
    for row in rows:
        row += [""] * (30 - len(row))
    snap = parse_sheet(rows, "фикстура", FIXTURE)

    names = {g.name for g in snap.groups}
    assert "ГД(а)-1125" not in names and "ДП-777/1" not in names
    assert "ГД-1125/2" in names


def test_name_with_other_numbers_is_not_the_old_name_of_a_neighbour(fixture_csv, monkeypatch):
    """Колонку вырезали во всём листе: в старом шаблоне ушла другая группа, а
    на её месте осталось имя группы, которой уже нет в главном заголовке.
    Шаг соседей тот же, но номера в именах другие — это не прежнее имя: группа
    главного заголовка в этих днях не прочитана, а не получает чужие пары."""
    from whensclass.parser import sections

    monkeypatch.setattr(sections, "SETTLE_MIN_LESSONS", 8)
    _, rows, below = _week_from_another_template(fixture_csv, days=2)
    header = _columnar_header(rows)[0]
    end = next(i for i, r in enumerate(rows) if _parse_date(r[0]) == below[2])
    # Колонка 10 под заголовком — ДП-923 и ДП-1124 по шагу соседей. Пусть в
    # ней стоит ушедшая группа со своими парами. Предметы у неё те же, что у
    # ДП (на сотне групп одна чужая колонка долю своих предметов не роняет):
    # пары ДП из других дней.
    rows[header + 2][10] = "ПР-926/2"
    above = [i for i, r in enumerate(rows) if i < header and _parse_date(r[0]) is not None]
    starts = [i for i, r in enumerate(rows) if header < i < end and _parse_date(r[0]) is not None]
    for k, start in enumerate(starts):
        for step in range(12):
            rows[start + step][10:14] = rows[above[(k + 2) % len(above)] + step][14:18]
    snap = parse_sheet(rows, "фикстура", FIXTURE)

    for gid in ("dp-923", "dp-1124"):
        assert set(snap.unread.get(gid, {})) >= {below[0], below[1]}
        assert not any(snap.schedule[gid].get(day) for day in below[:2])


def test_old_name_with_the_same_numbers_is_read_by_neighbours(fixture_csv, monkeypatch):
    """Прежнее имя («ДП(а)-923 и ДП(а)-1124») с теми же номерами — та же группа:
    её пары под повторным заголовком читаются, а не теряются."""
    from whensclass.parser import sections

    monkeypatch.setattr(sections, "SETTLE_MIN_LESSONS", 8)
    honest, rows, below = _week_from_another_template(fixture_csv, days=2)
    header = _columnar_header(rows)[0]
    rows[header + 2][10] = "ДП(а)-923 и ДП(а)-1124"
    snap = parse_sheet(rows, "фикстура", FIXTURE)

    for gid in ("dp-923", "dp-1124"):
        assert not set(snap.unread.get(gid, {})) & {below[0], below[1]}
        assert {d: snap.schedule[gid].get(d) for d in below[:2]} == \
            {d: honest.schedule[gid].get(d) for d in below[:2]}


@pytest.mark.parametrize("shape", ["Дисциплина повторена", "без «Дисциплина»"])
def test_repeated_header_with_an_extra_or_missing_head_row_is_still_a_header(fixture_csv, shape):
    """Колледж то повторяет в шапке недели строку «Дисциплина» (Дисциплина /
    Преподаватель / Дисциплина / имена), то теряет её (Преподаватель / имена):
    строка имён — первая без слов шапки, а «№» в ней — не номер пары."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    header = _columnar_header(rows)[0]
    rows[header + 2][1] = "№"
    honest = parse_sheet([list(r) for r in rows], "фикстура", FIXTURE)
    if shape == "Дисциплина повторена":
        rows.insert(header + 2, list(rows[header]))
    else:
        del rows[header]
    snap = parse_sheet(rows, "фикстура", FIXTURE)
    assert snap.schedule == honest.schedule
    assert snap.unread == honest.unread


def test_day_empty_by_both_headers_is_empty_not_unread():
    """Спорный день без единой пары: чьи пары — вопроса нет, день не «не прочитан»."""
    import datetime as dt
    from whensclass.domain.models import GroupRef, Lesson, Snapshot
    from whensclass.parser import sections as cs

    empty, busy = dt.date(2026, 11, 4), dt.date(2026, 11, 5)
    groups = [GroupRef(name="А-1", id="a-1", column=4)]
    pair = Lesson(number=1, subject="Физика")
    snapshot = Snapshot("лист", groups=groups, dates=[empty, busy],
                        schedule={"a-1": {empty: [pair], busy: [pair]}})
    doubt = {
        empty: cs._Doubt(columns={"a-1": 4}, why="сдвиг", by_main={"a-1": []}, by_section={"a-1": []}),
        busy: cs._Doubt(columns={"a-1": 4}, why="сдвиг", by_main={"a-1": [pair]}, by_section={"a-1": [pair]}),
    }
    cs._settle_moved(snapshot, doubt)
    assert empty not in snapshot.schedule["a-1"]
    assert empty not in snapshot.unread.get("a-1", {})
    assert busy in snapshot.unread["a-1"]


def test_day_empty_only_for_doubtful_groups_stays_unread():
    """Пары есть у групп вне спорных: пустота спорной колонки — не выходной."""
    import datetime as dt
    from whensclass.domain.models import GroupRef, Lesson, Snapshot
    from whensclass.parser import sections as cs

    day = dt.date(2026, 10, 14)
    groups = [GroupRef(name="А-1", id="a-1", column=4), GroupRef(name="Б-1", id="b-1", column=8)]
    pair = Lesson(number=1, subject="Физика")
    snapshot = Snapshot("лист", groups=groups, dates=[day], schedule={"a-1": {day: [pair]}, "b-1": {day: [pair]}})
    cs._settle_moved(snapshot, {day: cs._Doubt(columns={"a-1": 4}, why="сдвиг", by_main={"a-1": []}, by_section={"a-1": []})})
    assert day in snapshot.unread["a-1"]


def test_subgroup_of_next_sheet_gets_group_lessons_in_current_sheet():
    """Подгруппа названа только в следующем листе: в днях текущего она — группа."""
    import datetime as dt
    from whensclass.domain.models import GroupRef, Lesson, Snapshot
    from whensclass.parser.sections import fill_subgroup

    a, b = dt.date(2026, 11, 2), dt.date(2026, 11, 9)
    base = GroupRef(name="С-926", id="s-926", column=4)
    sub = GroupRef(name="С-926/1", id="s-926-1", column=5)
    pair = Lesson(number=1, subject="Физика")
    first = Snapshot("1", groups=[base], dates=[a], schedule={"s-926": {a: [pair]}}, unread={"s-926": {a: True}})
    second = Snapshot("2", groups=[base, sub], dates=[b], schedule={"s-926": {b: [pair]}, "s-926-1": {b: [pair]}})
    merged = first.merged_with(second)
    fill_subgroup(merged, sub, set(second.dates))
    assert merged.schedule["s-926-1"][a] == [pair]
    assert merged.unread["s-926-1"] == {a: True}
    assert merged.day_columns["s-926-1"][a] == 4
