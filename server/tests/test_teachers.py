"""Расписание преподавателя — перевёрнутое расписание групп."""

import datetime as dt

import pytest

from whensclass.api.payloads import teacher_payload, teachers_payload
from whensclass.domain.teachers import build_index, teacher_id
from whensclass.parser.csv_schedule import FIXTURE, parse_csv

GENERATED = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.timezone.utc)


@pytest.fixture(scope="module")
def snapshot(fixture_csv):
    return parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)


@pytest.fixture(scope="module")
def index(snapshot):
    return build_index(snapshot)


def test_teachers_found(index):
    assert len(index.names) > 5
    assert all(" " in name for name in index.names.values()), "ожидались ФИО целиком"


def test_teacher_keeps_own_lessons(snapshot, index):
    tid = teacher_id("Трухачев Даниил Дмитриевич")
    days = index.days(tid)
    assert days, "у преподавателя должны быть пары"

    # Каждая его пара обязана найтись в расписании какой-нибудь группы.
    for day, entries in days.items():
        for entry in entries:
            found = any(
                lesson.number == entry.lesson.number and lesson.subject == entry.lesson.subject
                for by_date in snapshot.schedule.values()
                for lesson in by_date.get(day, [])
            )
            assert found, f"{entry.lesson.subject} в {day} потерялась"


def test_one_lesson_for_several_groups_is_merged(snapshot, index):
    """Лекция сразу нескольким подгруппам — одна запись, а не три."""
    for tid in index.names:
        for entries in index.days(tid).values():
            numbers = [e.lesson.number for e in entries]
            assert len(numbers) == len(set(numbers)), (
                f"{index.names[tid]} стоит в одно время дважды: {numbers}"
            )


def test_payload_names_groups_and_drops_teacher(snapshot, index):
    tid = teacher_id("Трухачев Даниил Дмитриевич")
    body = teacher_payload(snapshot, index, tid, dt.date(2026, 9, 7), 3, GENERATED)
    assert body is not None
    assert body["kind"] == "teacher"
    for day in body["days"]:
        for lesson in day["l"]:
            assert lesson["gr"], "должна быть указана группа"
            assert "t" not in lesson, "имя преподавателя в его же расписании лишнее"


def test_unknown_teacher(snapshot, index):
    assert teacher_payload(snapshot, index, "нет-такого", dt.date(2026, 9, 7), 3, GENERATED) is None


def test_teachers_list_sorted_by_name(index):
    body = teachers_payload(index, GENERATED)
    names = [t["name"] for t in body["teachers"]]
    assert names == sorted(names)
