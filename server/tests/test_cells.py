"""Разбор ячейки: каждый случай взят из живой таблицы, не выдуман."""

import pytest

from whensclass.parser.cells import parse_lesson

CASES = [
    # (предмет, аудитория, преподаватель) -> ожидаемые поля
    (
        ("Информатика (Лек)", "369", "Литвиненко Наталья Николаевна"),
        dict(subject="Информатика", kind="Лек", room="369", cancelled=False),
    ),
    (
        ("Информатика (лек)", "369", ""),
        dict(kind="Лек"),  # регистр в таблице гуляет
    ),
    (
        ("Иностранный язык (ПР)", "", ""),
        dict(kind="Пр"),
    ),
    (
        ("Безопасность жизнедеятельности (Лек) Отмена", "369", ""),
        dict(subject="Безопасность жизнедеятельности", kind="Лек", cancelled=True,
             note=None),
    ),
    (
        ("Иностранный язык (Пр) Отмена Преподаватель заболел", "253", ""),
        dict(cancelled=True, note="Преподаватель заболел", room="253"),
    ),
    (
        ("Введение в проектную деятельность\nОТМЕНА", "454\nОТМЕНА", ""),
        dict(subject="Введение в проектную деятельность", room="454", cancelled=True),
    ),
    (
        ("Физическая культура (Пр)", "ОТМЕНА 279", "Кузнецов Андрей Игоревич"),
        # хвост в колонке аудитории — это аудитория, а не причина
        dict(room="279", cancelled=True, note=None),
    ),
    (
        ("Иностранный язык (Пр)", "https://my.mts-link.ru/j/100000001/20000000018", ""),
        dict(url="https://my.mts-link.ru/j/100000001/20000000018", room=None),
    ),
    (
        ("Физкультура (Пр)", "Спортзал Б.Хмельницкого 2 (Б.Хмельницкого 2)", ""),
        # скобка в конце аудитории — это адрес, а не тип занятия
        dict(room="Спортзал Б.Хмельницкого 2 (Б.Хмельницкого 2)", kind="Пр"),
    ),
    (
        ('Технология выполнения работ по профессии "Графический дизайнер" (Пр)',
         "273", "Бартенева Диана Унановна"),
        dict(subject='Технология выполнения работ по профессии "Графический дизайнер"'),
    ),
    (
        ("Проектирование ИС (Курс.р.)", "", ""),
        dict(kind="Курс.р."),
    ),
]


@pytest.mark.parametrize("cell,expected", CASES)
def test_parse_lesson(cell, expected):
    lesson = parse_lesson(1, *cell)
    assert lesson is not None
    for field, value in expected.items():
        assert getattr(lesson, field) == value, field


def test_empty_cell_is_not_a_lesson():
    assert parse_lesson(1, "", "", "") is None
    assert parse_lesson(1, None, None, None) is None


def test_two_teachers_in_one_cell():
    lesson = parse_lesson(1, "Физкультура (Пр)", "", "Иванов И. И.\nПетров П. П.")
    assert lesson.teachers == ("Иванов И. И.", "Петров П. П.")


def test_unknown_kind_survives():
    """Незнакомый тип не должен ронять разбор всего дня."""
    lesson = parse_lesson(1, "Практика (Вебинар)", "", "")
    assert lesson.kind == "Вебинар"
