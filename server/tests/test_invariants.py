"""Построчные инварианты: что колледж может набрать иначе, а мы примем молча.

Каждая мутация здесь до 14 сентября 2026 разбиралась без единой жалобы — и
давала день под чужой датой, пару у всех групп не на месте или «отмену»
в названии аудитории. Теперь — отказ с точной ячейкой в тексте, а канал
тревоги (service/alerts.py) доносит его до человека.
"""

import datetime as dt

import pytest

from whensclass.domain.models import SourceFormatChanged
from whensclass.parser.cells import parse_lesson
from whensclass.parser.csv_schedule import FIXTURE, collapse_export, parse_sheet, read_csv


def rows_of(fixture_csv):
    return collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)


def date_rows(rows):
    return [i for i, r in enumerate(rows) if r and r[0].strip()[:2].isdigit()]


def test_fixture_itself_passes(fixture_csv):
    parse_sheet(rows_of(fixture_csv), "фикстура", FIXTURE, around=dt.date(2026, 9, 8))


def test_duplicated_date_block_is_rejected(fixture_csv):
    """Скопировали блок дня: та же дата второй раз — номера начинаются заново."""
    rows = rows_of(fixture_csv)
    first, second = date_rows(rows)[:2]
    rows[second][0] = rows[first][0]
    with pytest.raises(SourceFormatChanged, match="номера пар"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_date_in_another_format_is_still_a_date(fixture_csv):
    """«07/09/2026» и «понедельник 07.09.2026» — даты, а не текст рядом с датой."""
    rows = rows_of(fixture_csv)
    first, second = date_rows(rows)[:2]
    rows[first][0] = "понедельник 02.09.2026"
    rows[second][0] = "03/09/2026"
    snapshot = parse_sheet(rows, "фикстура", FIXTURE)
    assert dt.date(2026, 9, 3) in snapshot.dates


def test_date_that_cannot_be_read_stops_the_parse(fixture_csv):
    rows = rows_of(fixture_csv)
    rows[date_rows(rows)[1]][0] = "03.13.2026 четверг"
    with pytest.raises(SourceFormatChanged, match="не разобралась"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_word_without_digits_in_date_column_is_ignored(fixture_csv):
    rows = rows_of(fixture_csv)
    rows[date_rows(rows)[1] - 1][0] = "четверг"
    parse_sheet(rows, "фикстура", FIXTURE)


def test_lesson_number_written_differently_is_rejected(fixture_csv):
    """«3 пара», «1-2», «кл. час» в колонке номеров — решает человек, не пропуск."""
    rows = rows_of(fixture_csv)
    row = next(i for i, r in enumerate(rows) if len(r) > 1 and r[1].strip() == "3")
    rows[row][1] = "3 пара"
    with pytest.raises(SourceFormatChanged, match="колонке номеров"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_missing_lesson_number_is_rejected(fixture_csv):
    """Пропущенная строка номера — пара исчезала у всех групп без жалоб."""
    rows = rows_of(fixture_csv)
    row = next(i for i, r in enumerate(rows) if len(r) > 1 and r[1].strip() == "3")
    rows[row][1] = ""
    with pytest.raises(SourceFormatChanged, match="ждал 3"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_bell_times_in_number_column_are_fine(fixture_csv):
    """Под парой в колонке номера стоит «9-00-10.30» — это не номер и не беда."""
    rows = rows_of(fixture_csv)
    assert any(len(r) > 1 and r[1].strip() == "9-00-10.30" for r in rows)
    parse_sheet(rows, "фикстура", FIXTURE)


def test_date_far_ahead_is_rejected_only_when_today_is_known(fixture_csv):
    """Опечатка «2027» растягивала бы лист на год; без «сегодня» проверки нет."""
    rows = rows_of(fixture_csv)
    last = date_rows(rows)[-1]
    rows[last][0] = rows[last][0].replace("2026", "2027")
    with pytest.raises(SourceFormatChanged, match="дальше 60 дней"):
        parse_sheet(rows, "фикстура", FIXTURE, around=dt.date(2026, 9, 8))
    with pytest.raises(SourceFormatChanged, match="больше 14 дней"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_gap_of_a_month_between_days_is_rejected(fixture_csv):
    rows = rows_of(fixture_csv)
    last = date_rows(rows)[-1]
    rows[last][0] = rows[last][0].replace(".09.", ".10.")
    with pytest.raises(SourceFormatChanged, match="больше 14 дней"):
        parse_sheet(rows, "фикстура", FIXTURE)


@pytest.mark.parametrize("cell", ["Иностранный язык (Пр) Отменена", "Отменён Иностранный язык",
                                  "Иностранный язык отм.", "Иностранный язык ОТМ"])
def test_cancellation_spelled_differently(cell):
    assert parse_lesson(1, cell, "272", "").cancelled is True


def test_otmetka_is_not_a_cancellation():
    lesson = parse_lesson(1, "Отметка о практике", "272", "")
    assert lesson.cancelled is False and lesson.subject == "Отметка о практике"


def test_losing_a_third_of_groups_at_once_is_rejected(fixture_csv):
    """Заголовок сломан наполовину: сто групп проходят порог, восемьдесят — в 404."""
    from whensclass.domain.models import Snapshot
    from whensclass.service.refresher import _check_group_drop

    before = parse_sheet(rows_of(fixture_csv), "фикстура", FIXTURE)
    after = Snapshot(sheet_title=before.sheet_title, groups=before.groups[:2],
                     dates=list(before.dates), schedule=dict(before.schedule))
    with pytest.raises(SourceFormatChanged, match="пропало"):
        _check_group_drop(before, after)
    # Новый лист с другими датами — новый состав, сравнивать не с чем.
    after.dates = [d + dt.timedelta(days=30) for d in before.dates]
    _check_group_drop(before, after)
    # И одна пропавшая группа — не толпа.
    one_less = Snapshot(sheet_title=before.sheet_title, groups=before.groups[1:],
                        dates=list(before.dates), schedule=dict(before.schedule))
    _check_group_drop(before, one_less)


def synthetic_sheet(groups=25, days=10, per_day=4):
    """Лист из воздуха: у каждой группы свой набор предметов, повторяющийся по дням.

    Фикстура для этого мала: на шести группах и двух неделях честная суббота
    выглядит как сдвиг. Порог считался по живому листу в 189 групп.
    """
    from whensclass.domain.models import GroupRef, Lesson, Snapshot

    snapshot = Snapshot(sheet_title="синтетика")
    snapshot.dates = [dt.date(2026, 9, 1) + dt.timedelta(days=i) for i in range(days)]
    for g in range(groups):
        ref = GroupRef(name=f"Г-{g}", id=f"g-{g}", column=2 + 4 * g)
        snapshot.groups.append(ref)
        snapshot.schedule[ref.id] = {
            day: [
                Lesson(number=n + 1, subject=f"Предмет {g}-{(n + i) % 12}",
                       teachers=(f"Преподаватель {g}",))
                for n in range(per_day)
            ]
            for i, day in enumerate(snapshot.dates)
        }
    return snapshot


def test_shift_below_last_repeated_header_is_caught_by_content():
    """Блок строк без повторного заголовка сдвинут на группу — по содержимому видно."""
    import copy

    from whensclass.parser.csv_schedule import _check_shift

    honest = synthetic_sheet()
    _check_shift(honest)

    shifted = copy.deepcopy(honest)
    ids = [g.id for g in honest.groups]
    cutoff = honest.dates[7]
    for i, gid in enumerate(ids):
        neighbour = ids[(i + 1) % len(ids)]
        for day in shifted.schedule[gid]:
            if day >= cutoff:
                shifted.schedule[gid][day] = list(honest.schedule[neighbour][day])
    with pytest.raises(SourceFormatChanged, match="похож на сдвиг"):
        _check_shift(shifted)

    # Один курс ушёл на практику — не сдвиг: чужих меньше четверти.
    practice = copy.deepcopy(honest)
    for gid in ids[:5]:
        for day in practice.schedule[gid]:
            if day >= cutoff:
                practice.schedule[gid][day] = [
                    dataclasses_replace(x, subject="Практика", teachers=())
                    for x in practice.schedule[gid][day]
                ]
    _check_shift(practice)


def dataclasses_replace(lesson, **changes):
    import dataclasses

    return dataclasses.replace(lesson, **changes)


def test_history_keeps_every_version_once_and_marks_rejected(tmp_path):
    from whensclass.storage.history import archive

    now = dt.datetime(2026, 9, 14, 1, 30)
    first = archive(tmp_path, "656498718", "a,b\n", "abcdef0123", now=now)
    assert first is not None and first.name == "2026-09-14-0130-abcdef01.csv.gz"
    assert archive(tmp_path, "656498718", "a,b\n", "abcdef0123", now=now) is None, "та же версия"
    bad = archive(tmp_path, "656498718", "a,b\n", "abcdef0123", rejected="сдвиг", now=now)
    assert bad is not None and bad.name.endswith("-rejected.csv.gz")
    assert (tmp_path / "history" / "656498718" / "2026-09-14-0130-abcdef01-rejected.txt").read_text("utf-8") == "сдвиг\n"


def test_skipped_block_is_a_gap_not_a_shift(fixture_csv):
    """Блок без имени группы — шаг 8. Пропуск, не сдвиг: соседи на месте."""
    from whensclass.parser.groups import build_column_map

    rows = rows_of(fixture_csv)
    header = rows[0]
    second = [c for c, v in enumerate(header) if v.startswith("Дисциплина Преподаватель")][1]
    header[second] = ""
    groups = build_column_map(rows, FIXTURE.min_groups)
    assert second not in {g.column for g in groups}
    assert len(groups) >= FIXTURE.min_groups


def test_inserted_column_is_still_a_shift(fixture_csv):
    """А шаг 5 — вставленная колонка: карта поехала, отказ."""
    from whensclass.parser.groups import build_column_map

    rows = rows_of(fixture_csv)
    for r in rows:
        if len(r) > 6:
            r.insert(6, "")
    with pytest.raises(SourceFormatChanged, match="шагом"):
        build_column_map(rows, FIXTURE.min_groups)


def test_room_mark_without_dot_is_tolerated(fixture_csv):
    from whensclass.parser.groups import build_column_map

    rows = rows_of(fixture_csv)
    rows[0][5] = "ауд"
    build_column_map(rows, FIXTURE.min_groups)
    rows[0][5] = "Каб."
    with pytest.raises(SourceFormatChanged, match="ожидалась"):
        build_column_map(rows, FIXTURE.min_groups)

