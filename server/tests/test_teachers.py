"""Расписание преподавателя — перевёрнутое расписание групп."""

import datetime as dt

import pytest

from whensclass.api.payloads import teacher_payload, teachers_payload
from whensclass.domain.teachers import build_index, teacher_id
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.parser.export import parse_csv

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
    """Отменили пару у одной группы — у другой она идёт: склейка по времени не
    раздаёт обеим состояние одной."""
    from whensclass.domain.models import Lesson

    kept = Lesson(number=2, subject="Физкультура", teachers=("Уэллс Д. Р.",), room="Спортзал")
    cancelled = Lesson(number=2, subject="Физкультура", teachers=("Уэллс Д. Р.",),
                       room="Спортзал", cancelled=True)
    snapshot, day = _two_groups(kept, cancelled)
    entries = build_index(snapshot).days(teacher_id("Уэллс Д. Р."))[day]
    assert sorted((e.group_name, e.lesson.cancelled) for e in entries) == [("А-1", False), ("А-2", True)]
    assert {e.column for e in entries} == {2, 6}


def test_same_lesson_for_two_groups_is_still_one_entry():
    from whensclass.domain.models import Lesson

    one = Lesson(number=2, subject="Физкультура", teachers=("Уэллс Д. Р.",), room="Спортзал")
    longer = Lesson(number=2, subject="Физкультура / Адаптивная физкультура",
                    teachers=("Уэллс Д. Р.",), room="Спортзал")
    snapshot, day = _two_groups(one, longer)
    [entry] = build_index(snapshot).days(teacher_id("Уэллс Д. Р."))[day]
    assert entry.group_name == "А-1, А-2" and entry.lesson.subject.startswith("Физкультура / ")


def test_column_follows_the_sheet_of_the_day():
    """У склеенного снимка двух листов колонка группы — из того листа, на
    который ведёт ссылка, а не из первого."""
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


def test_spelling_twin_is_the_same_surname_and_initials():
    """Опечатка в имени, исправленная колледжем, —
    тот же человек. Однофамилец с другими инициалами — нет."""
    from whensclass.domain.teachers import TeacherIndex, spelling_twin

    index = TeacherIndex(
        names={
            "truhachev-daniil": "Трухачев Даниил Дмитриевич",
            "misyurova": "Мисюрова Евгения Сергеевна",
            "kovalev-a": "Ковалев Алексей Петрович",
        },
        schedule={"truhachev-daniil": {1: [1]}, "misyurova": {1: [1]}, "kovalev-a": {1: [1]}},
    )
    assert spelling_twin(index, "Трухачев Данил Дмитриевич") == "truhachev-daniil"
    assert spelling_twin(index, "Мисюрова Е.С.") == "misyurova"
    assert spelling_twin(index, "Ковалев Иван Петрович") is None
    assert spelling_twin(index, "Трухачев Даниил Дмитриевич") is None, "сам себе не двойник"
    index.schedule.pop("truhachev-daniil")
    assert spelling_twin(index, "Трухачев Данил Дмитриевич") is None, "без пар — не замена"


def test_group_missing_from_the_next_sheet_gets_no_foreign_column_and_no_free_days():
    """Группы нет в следующем листе (переименовали
    или убрали): дни второго листа — за краем её `cov`, а не «пар нет», и
    колонки у ссылки на второй лист нет — там на этом месте другая группа."""
    from whensclass.api.payloads import schedule_payload
    from whensclass.domain.models import GroupRef, Lesson, SheetPlace, Snapshot

    saturday, monday = dt.date(2026, 9, 26), dt.date(2026, 9, 28)
    first = Snapshot(sheet_title="первый", groups=[GroupRef(name="А-1", id="a-1", column=2)],
                     dates=[saturday])
    first.schedule = {"a-1": {saturday: [Lesson(number=1, subject="Х")]}}
    first.places = {saturday: SheetPlace(gid="1", row=5)}
    first.sheet_columns = {"1": {"a-1": 2}}
    second = Snapshot(sheet_title="второй", groups=[GroupRef(name="Б-2", id="b-2", column=2)],
                      dates=[monday])
    second.schedule = {"b-2": {monday: [Lesson(number=1, subject="У")]}}
    second.places = {monday: SheetPlace(gid="2", row=5)}
    second.sheet_columns = {"2": {"b-2": 2}}
    merged = first.merged_with(second)
    generated = dt.datetime(2026, 9, 26, tzinfo=dt.timezone.utc)

    body = schedule_payload(merged, "a-1", monday, 1, generated, today=saturday)
    assert body["days"] == [] and "col" not in body
    assert body["cov"] == ["2026-09-26", "2026-09-26"]
    own = schedule_payload(merged, "a-1", saturday, 1, generated, today=saturday)
    assert own["col"] == "C"


def test_names_without_surname_do_not_become_teachers():
    """«Елена Сергеевна» и «СПТ» в /v1/teachers не попадают."""
    from whensclass.domain.models import GroupRef, Lesson, Snapshot

    day = dt.date(2026, 9, 17)
    group = GroupRef(name="А-1", id="a-1", column=2)
    snap = Snapshot(sheet_title="л", groups=[group], dates=[day])
    snap.schedule = {"a-1": {day: [Lesson(
        number=4, subject="Кураторский час",
        teachers=("Щетинкин Артем Сергеевич", "Анастасия Дмитриевна", "СПТ"),
    )]}}
    assert list(build_index(snap).names.values()) == ["Щетинкин Артем Сергеевич"]


def test_initials_are_merged_into_the_single_full_name():
    """«Мисюрова Е.С.» и «Мисюрова Евгения
    Сергеевна» — один человек, а не пары, поделённые между двумя id. Краткая
    запись сводится к полной, а её id отвечает полной (новый g перепишет выбор)."""
    from whensclass.domain.models import GroupRef, Lesson, Snapshot

    d1, d2 = dt.date(2026, 9, 7), dt.date(2026, 9, 14)
    snap = Snapshot(sheet_title="л", groups=[GroupRef(name="А-1", id="a-1", column=2)],
                    dates=[d1, d2])
    snap.schedule = {"a-1": {
        d1: [Lesson(number=1, subject="Х", teachers=("Мисюрова Е.С.",))],
        d2: [Lesson(number=1, subject="Х", teachers=("Мисюрова Евгения Сергеевна",)),
             Lesson(number=2, subject="У", teachers=("Мисин А. Б.",))],
    }}
    index = build_index(snap)
    full = teacher_id("Мисюрова Евгения Сергеевна")
    assert set(index.names) == {full, teacher_id("Мисин А. Б.")}
    assert set(index.days(full)) == {d1, d2}
    assert index.aliases == {teacher_id("Мисюрова Е.С."): full}
