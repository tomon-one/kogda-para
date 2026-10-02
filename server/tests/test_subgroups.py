"""Две подгруппы в одной ячейке «X (Пр)/Y (Пр)» — две пары одного номера."""

import pytest

from whensclass.parser.cells import parse_lesson
from whensclass.parser.subgroups import split_subgroups


def lessons(subject, room, teacher):
    return [parse_lesson(1, *cells) for cells in split_subgroups(subject, room, teacher) or [(subject, room, teacher)]]


def test_cancellation_of_one_subgroup_stays_with_it():
    first, second = lessons(
        "Иностранный язык, Английский (Пр) отмена, преподаватель заболел/Иностранный язык, немецкий (Пр)",
        "467/55/1", "Миллер Д. Х./Уэллс Д. Р.",
    )
    assert (first.subject, first.room, first.teachers, first.cancelled, first.note) == (
        "Иностранный язык, Английский", "467", ("Миллер Д. Х.",), True, "преподаватель заболел")
    assert (second.subject, second.room, second.teachers, second.cancelled) == (
        "Иностранный язык, немецкий", "55/1", ("Уэллс Д. Р.",), False)


@pytest.mark.parametrize("room, rooms", [
    ("281/269", ("281", "269")),
    ("257/", ("257", None)),
    ("467/55/1", ("467", "55/1")),
    # Делится двояко — аудитория достаётся обеим как есть.
    ("12/34/56", ("12/34/56", "12/34/56")),
])
def test_rooms_split_only_when_unambiguous(room, rooms):
    got = lessons("Английский (Пр)/Немецкий (Пр)", room, "Миллер Д. Х./Уэллс Д. Р.")
    assert tuple(x.room for x in got) == rooms


@pytest.mark.parametrize("subject", [
    "Информатика/программирование (Лек)",
    "Английский (Пр)",
    "Английский (Пр) https://a.ru/x/y (Лек)",
])
def test_one_lesson_is_not_split(subject):
    assert split_subgroups(subject, "200", "Миллер Д. Х.") is None


def test_teachers_that_do_not_split_in_two_go_to_both():
    got = lessons("Английский (Пр)/Немецкий (Пр)", "1/2", "Миллер Д. Х.")
    assert [x.teachers for x in got] == [("Миллер Д. Х.",), ("Миллер Д. Х.",)]
