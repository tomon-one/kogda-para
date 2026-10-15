"""Сверка с листом преподавателей: разбор листа и что считается расхождением."""

import csv
import datetime as dt
import io

from whensclass.domain.models import GroupRef, Lesson, Snapshot
from whensclass.parser.teacher_sheet import parse_teacher_sheet, sheet_week
from whensclass.service.crosscheck import compare

MON, TUE = dt.date(2026, 10, 19), dt.date(2026, 10, 20)


def _sheet(rows):
    out = io.StringIO()
    csv.writer(out).writerows(rows)
    return out.getvalue()


# Раскладка как у настоящего листа: у дня четыре колонки, пара — две строки.
SHEET = _sheet([
    ["Расписание занятий"],
    ["19.10.2026-24.10.2026"],
    ["Преподаватель", "Пара", "Время", "19.10.2026\n \n ПОНЕДЕЛЬНИК", "", "", "", "20.10.2026\n \n ВТОРНИК", "", "", ""],
    ["", "", "", "Дисциплина", "", "", "Ауд.", "Дисциплина", "", "", "Ауд."],
    ["Иванов Иван Иванович", "1", "", "", "", "", "", "", "", "", ""],
    ["", "", "", "", "", "", "", "", "", "", ""],
    ["", "2", "10.40-12.10", "Физика (Лек)", "", "", "251", "Физика (Пр)", "", "", "онлайн"],
    ["", "", "", "А-1", "", "", "", "А-1 и Б-1", "", "", ""],
    ["", "3", "12.30-14.00", "Химия (Пр)", "", "", "252", "", "", "", ""],
    ["", "", "", "А-1", "", "", "", "", "", "", ""],
])


def test_pairs_are_read_with_day_number_and_group():
    pairs = parse_teacher_sheet(SHEET)
    assert [(p.teacher, p.day, p.number, p.subject, p.room, p.group) for p in pairs] == [
        ("Иванов Иван Иванович", MON, 2, "Физика (Лек)", "251", "А-1"),
        ("Иванов Иван Иванович", TUE, 2, "Физика (Пр)", "онлайн", "А-1 и Б-1"),
        ("Иванов Иван Иванович", MON, 3, "Химия (Пр)", "252", "А-1"),
    ]
    assert sheet_week("расписание преподавателей 19.10-24.10", 2026) == (MON, dt.date(2026, 10, 24))
    assert sheet_week("расписание групп 01.-19.09", 2026) is None


def _snapshot(a1_mon):
    groups = [GroupRef(name="А-1", id="a-1", column=4), GroupRef(name="Б-1", id="b-1", column=8)]
    return Snapshot("лист", groups=groups, dates=[MON, TUE], schedule={
        "a-1": {MON: a1_mon, TUE: [Lesson(number=2, subject="Физика")]},
        "b-1": {TUE: [Lesson(number=2, subject="Физика")]},
    })


def test_matching_day_is_quiet():
    honest = [Lesson(number=2, subject="Физика"), Lesson(number=3, subject="Химия")]
    assert compare(_snapshot(honest), parse_teacher_sheet(SHEET), MON) == []


def test_neighbours_lessons_are_a_mismatch():
    """Колонку читаем не там: у группы чужие предметы."""
    shifted = [Lesson(number=2, subject="История"), Lesson(number=3, subject="Литература")]
    found = compare(_snapshot(shifted), parse_teacher_sheet(SHEET), MON)
    assert [(m.group, m.day, m.matched, m.total) for m in found] == [("А-1", MON, 0, 2)]
    # Прошедшие дни не сверяем.
    assert compare(_snapshot(shifted), parse_teacher_sheet(SHEET), TUE) == []


def test_one_late_edit_is_not_a_mismatch():
    """Одна несошедшаяся пара из двух — поздняя правка листа групп, не сдвиг."""
    edited = [Lesson(number=2, subject="Кураторский час"), Lesson(number=3, subject="Химия")]
    assert compare(_snapshot(edited), parse_teacher_sheet(SHEET), MON) == []
