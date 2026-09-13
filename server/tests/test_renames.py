"""Переименования: старый id продолжает отвечать расписанием нового."""

import dataclasses
import datetime as dt
import json

import pytest

from whensclass.domain.ids import group_id
from whensclass.domain.models import GroupRef, Snapshot
from whensclass.domain.teachers import build_index, teacher_id
from whensclass.parser.csv_schedule import FIXTURE, parse_csv
from whensclass.service.renames import RenameBook, detect, group_traces, teacher_traces


@pytest.fixture(scope="module")
def snapshot(fixture_csv):
    return parse_csv(fixture_csv, "фикстура", FIXTURE)


def renamed_group(snapshot: Snapshot, old_name: str, new_name: str) -> Snapshot:
    """Тот же снимок, но группа названа иначе — как после правки заголовка."""
    old_id, new_id = group_id(old_name), group_id(new_name)
    out = Snapshot(sheet_title=snapshot.sheet_title, dates=list(snapshot.dates))
    out.groups = [
        GroupRef(name=new_name, id=new_id, column=g.column) if g.id == old_id else g
        for g in snapshot.groups
    ]
    out.schedule = {
        (new_id if gid == old_id else gid): by_date for gid, by_date in snapshot.schedule.items()
    }
    return out


def renamed_teacher(snapshot: Snapshot, old_name: str, new_name: str) -> Snapshot:
    out = Snapshot(sheet_title=snapshot.sheet_title, groups=list(snapshot.groups),
                   dates=list(snapshot.dates))
    out.schedule = {
        gid: {
            day: [
                dataclasses.replace(
                    x, teachers=tuple(new_name if t == old_name else t for t in x.teachers)
                )
                for x in lessons
            ]
            for day, lessons in by_date.items()
        }
        for gid, by_date in snapshot.schedule.items()
    }
    return out


def test_group_rename_is_recognised_by_its_lessons(snapshot):
    """Имя другое, колонка та же — значит, пары те же. Это и есть переименование."""
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2 (новая)")
    found = detect(group_traces(snapshot), group_traces(after))
    assert found == {"isp-924-2": "isp-924-2-novaya"}


def test_new_group_with_other_lessons_is_not_a_rename(snapshot):
    """Группа исчезла, другая появилась, а пары разные — это не одно и то же."""
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-1126")
    new_id = group_id("ИСП-1126")
    after.schedule[new_id] = {
        day: [dataclasses.replace(x, subject=f"Другой предмет {i}") for i, x in enumerate(lessons)]
        for day, lessons in after.schedule[new_id].items()
    }
    assert detect(group_traces(snapshot), group_traces(after)) == {}


def test_split_into_two_is_not_a_rename(snapshot):
    """КВД-926 стала КВД-926/1 и КВД-926/2 с теми же парами: гадать нельзя."""
    old_id = group_id("ИСП-924/2")
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2-1")
    twin = GroupRef(name="ИСП-924/2-2", id=group_id("ИСП-924/2-2"), column=9999)
    after.groups.append(twin)
    after.schedule[twin.id] = after.schedule[group_id("ИСП-924/2-1")]
    assert detect(group_traces(snapshot), group_traces(after)) == {}
    assert old_id not in after.schedule


def test_unchanged_snapshot_has_no_renames(snapshot):
    assert detect(group_traces(snapshot), group_traces(snapshot)) == {}


def test_teacher_typo_fix_is_a_rename(snapshot):
    index = build_index(snapshot)
    # Берём того, у кого пар хватает на уверенное совпадение: у одной-двух
    # пар совпадение случайное, и такого преподавателя книга не тронет.
    tid = max(index.schedule, key=lambda t: sum(len(v) for v in index.schedule[t].values()))
    name = index.names[tid]
    fixed = name + "а"
    after = build_index(renamed_teacher(snapshot, name, fixed))
    found = detect(teacher_traces(index), teacher_traces(after))
    assert found == {teacher_id(name): teacher_id(fixed)}


def test_book_answers_by_old_id_and_survives_restart(tmp_path, snapshot):
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2 (новая)")
    book = RenameBook(tmp_path)
    book.record(snapshot, after, build_index(snapshot), build_index(after))
    assert book.group("isp-924-2") == "isp-924-2-novaya"
    assert book.teacher("нет-такого") is None

    reloaded = RenameBook(tmp_path)
    assert reloaded.group("isp-924-2") == "isp-924-2-novaya"
    data = json.loads((tmp_path / "renames.json").read_text("utf-8"))
    assert data["groups"] == {"isp-924-2": "isp-924-2-novaya"}


def test_chain_is_flattened(tmp_path, snapshot):
    """A -> B, потом B -> C: по A отвечаем C сразу, а не через два шага."""
    second = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2 (новая)")
    third = renamed_group(second, "ИСП-924/2 (новая)", "ИСП-924/2 (новейшая)")
    book = RenameBook(tmp_path)
    book.record(snapshot, second, build_index(snapshot), build_index(second))
    book.record(second, third, build_index(second), build_index(third))
    assert book.group("isp-924-2") == "isp-924-2-noveyshaya"
    assert book.group("isp-924-2-novaya") == "isp-924-2-noveyshaya"


def test_old_name_coming_back_cancels_the_alias(tmp_path, snapshot):
    """Вернули прежнее имя — это снова настоящая группа, подменять её нельзя."""
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2 (новая)")
    book = RenameBook(tmp_path)
    book.record(snapshot, after, build_index(snapshot), build_index(after))
    assert book.group("isp-924-2") is not None
    book.record(after, snapshot, build_index(after), build_index(snapshot))
    assert book.group("isp-924-2") is None
    assert book.group("isp-924-2-novaya") == "isp-924-2"


def test_places_survive_the_disk(tmp_path, fixture_csv):
    """Строки дней и лист записываются в снимок и читаются обратно."""
    from whensclass.domain.models import SheetPlace
    from whensclass.storage.snapshot_store import SnapshotStore

    parsed = parse_csv(fixture_csv, "фикстура", FIXTURE)
    parsed.places = {day: SheetPlace(gid="656498718", row=6 + 12 * i)
                     for i, day in enumerate(parsed.dates)}
    store = SnapshotStore(tmp_path)
    store.put(parsed, dt.datetime(2026, 9, 13, tzinfo=dt.timezone.utc))
    again = SnapshotStore(tmp_path)
    assert again.load()
    assert again.snapshot.places == parsed.places


def test_date_rows_follow_the_sheet_not_the_csv():
    """Колонка A из API: пустые строки на месте, шапка не схлопнута."""
    from whensclass.parser.csv_schedule import date_rows

    column = ["", "", "Дисциплина", "Преподаватель", "№", "02.09.2026 среда", "", "",
              "", "", "03.09.2026 четверг", "", "02.09.2026 среда"]
    assert date_rows(column) == {dt.date(2026, 9, 2): 6, dt.date(2026, 9, 3): 11}

