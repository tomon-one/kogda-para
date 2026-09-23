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


def _two_groups(first, second):
    from whensclass.domain.models import GroupRef, Snapshot

    day = dt.date(2026, 9, 8)
    snapshot = Snapshot(sheet_title="лист", dates=[day])
    for name, column, lesson in (("А-1", 2, first), ("А-2", 6, second)):
        ref = GroupRef(name=name, id=name.lower(), column=column)
        snapshot.groups.append(ref)
        snapshot.schedule[ref.id] = {day: [lesson]}
    return snapshot, day


def test_same_slot_in_different_states_stays_apart():
    """Отменили пару у одной группы — у другой она идёт. Раньше склейка по
    времени раздавала обеим состояние одной (второй аудит, В1)."""
    from whensclass.domain.models import Lesson

    kept = Lesson(number=2, subject="Физкультура", teachers=("Иванов И. И.",), room="Спортзал")
    cancelled = Lesson(number=2, subject="Физкультура", teachers=("Иванов И. И.",),
                       room="Спортзал", cancelled=True)
    snapshot, day = _two_groups(kept, cancelled)
    entries = build_index(snapshot).days(teacher_id("Иванов И. И."))[day]
    assert sorted((e.group_name, e.lesson.cancelled) for e in entries) == [("А-1", False), ("А-2", True)]
    assert {e.column for e in entries} == {2, 6}


def test_same_lesson_for_two_groups_is_still_one_entry():
    from whensclass.domain.models import Lesson

    one = Lesson(number=2, subject="Физкультура", teachers=("Иванов И. И.",), room="Спортзал")
    longer = Lesson(number=2, subject="Физкультура / Адаптивная физкультура",
                    teachers=("Иванов И. И.",), room="Спортзал")
    snapshot, day = _two_groups(one, longer)
    [entry] = build_index(snapshot).days(teacher_id("Иванов И. И."))[day]
    assert entry.group_name == "А-1, А-2" and entry.lesson.subject.startswith("Физкультура / ")


def test_column_follows_the_sheet_of_the_day():
    """У склеенного снимка двух листов колонка группы — из того листа, на
    который ведёт ссылка, а не из первого (второй аудит, М28)."""
    from whensclass.api.payloads import schedule_payload
    from whensclass.domain.models import GroupRef, Lesson, SheetPlace, Snapshot

    ref = GroupRef(name="А-1", id="a-1", column=2)
    first = Snapshot(sheet_title="первый", groups=[ref], dates=[dt.date(2026, 9, 12)])
    first.schedule = {"a-1": {dt.date(2026, 9, 12): [Lesson(number=1, subject="Х")]}}
    first.places = {dt.date(2026, 9, 12): SheetPlace(gid="1", row=5)}
    first.sheet_columns = {"1": {"a-1": 2}}
    second = Snapshot(sheet_title="второй", groups=[GroupRef(name="А-1", id="a-1", column=10)],
                      dates=[dt.date(2026, 9, 14)])
    second.schedule = {"a-1": {dt.date(2026, 9, 14): [Lesson(number=1, subject="У")]}}
    second.places = {dt.date(2026, 9, 14): SheetPlace(gid="2", row=5)}
    second.sheet_columns = {"2": {"a-1": 10}}
    merged = first.merged_with(second)
    generated = dt.datetime(2026, 9, 12, tzinfo=dt.timezone.utc)
    assert schedule_payload(merged, "a-1", dt.date(2026, 9, 12), 1, generated)["col"] == "C"
    body = schedule_payload(merged, "a-1", dt.date(2026, 9, 14), 1, generated)
    assert body["col"] == "K" and body["src_url"].endswith("#gid=2")
