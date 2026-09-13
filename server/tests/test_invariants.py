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

