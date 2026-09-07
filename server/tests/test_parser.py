"""Обход листа целиком — на урезанной фикстуре живой таблицы."""

import datetime as dt

import pytest

from whensclass.domain.models import SourceFormatChanged
from whensclass.parser.csv_schedule import FIXTURE, parse_csv, read_csv
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
    rows = read_csv(fixture_csv)
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
