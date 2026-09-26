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


def settle(book, before, after, times=None):
    """Записывает переход и держит новый состав столько обновлений, сколько надо книге."""
    from whensclass.service.renames import CONFIRMATIONS

    ib, ia = build_index(before), build_index(after)
    book.record(before, after, ib, ia)
    for _ in range((CONFIRMATIONS if times is None else times) - 1):
        book.record(after, after, ia, ia)


def test_group_rename_is_recognised_by_its_lessons(snapshot):
    """Имя другое, колонка та же — значит, пары те же. Это и есть переименование."""
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    found = detect(group_traces(snapshot), group_traces(after))
    assert found == {"isp-924-2": "isp-924-2a"}


def test_rename_survives_links_added_the_same_hour(snapshot):
    """Ссылки и аудитории меняются сами по себе; порог считается без них."""
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    new_id = group_id("ИСП-924/2а")
    after.schedule[new_id] = {
        day: [dataclasses.replace(x, url="https://my.mts-link.ru/j/1/2", online=True)
              for x in lessons]
        for day, lessons in after.schedule[new_id].items()
    }
    assert detect(group_traces(snapshot), group_traces(after)) == {"isp-924-2": "isp-924-2a"}


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


def test_split_with_one_subgroup_so_far_is_not_a_rename(snapshot):
    """Живой случай 13.09: /1 совпала с прежней колонкой целиком, /2 — новая.

    Половина людей теперь в /2, и ответить им расписанием /1 — чужое
    расписание при «ok». Одного хвоста достаточно: второй заведут завтра.
    """
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2/1")
    second = GroupRef(name="ИСП-924/2/2", id=group_id("ИСП-924/2/2"), column=9999)
    after.groups.append(second)
    first = after.schedule[group_id("ИСП-924/2/1")]
    only_day = max(day for day, lessons in first.items() if lessons)
    after.schedule[second.id] = {only_day: first[only_day]}
    assert detect(group_traces(snapshot), group_traces(after)) == {}


def test_leaders_by_subjects_and_by_rooms_must_agree(snapshot):
    """По предметам ближе один, по аудиториям другой — так выглядят подгруппы."""
    old_id = group_id("ИСП-924/2")
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    a = group_id("ИСП-924/2а")
    b = GroupRef(name="ИСП-924/2б", id=group_id("ИСП-924/2б"), column=9999)
    after.groups.append(b)
    # «а» — все предметы прежние, но все аудитории другие; «б» — на одну пару
    # меньше, зато остальные совпадают целиком.
    after.schedule[b.id] = {
        day: list(lessons) for day, lessons in snapshot.schedule[old_id].items()
    }
    first_day = sorted(after.schedule[b.id])[0]
    after.schedule[b.id][first_day] = after.schedule[b.id][first_day][1:]
    after.schedule[a] = {
        day: [dataclasses.replace(x, room="999") for x in lessons]
        for day, lessons in after.schedule[a].items()
    }
    assert detect(group_traces(snapshot), group_traces(after)) == {}


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


def test_book_waits_for_confirmations_then_answers_and_survives_restart(tmp_path, snapshot):
    from whensclass.service.renames import CONFIRMATIONS

    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    book = RenameBook(tmp_path)
    settle(book, snapshot, after, times=CONFIRMATIONS - 1)
    # Ещё рано: опечатку в заголовке колледж чинит через двадцать минут, а
    # телефон, получив новый id, перепишет выбор навсегда.
    assert book.group("isp-924-2") is None
    book.record(after, after, build_index(after), build_index(after))
    assert book.group("isp-924-2") == "isp-924-2a"
    assert book.teacher("нет-такого") is None

    reloaded = RenameBook(tmp_path)
    assert reloaded.group("isp-924-2") == "isp-924-2a"
    data = json.loads((tmp_path / "renames.json").read_text("utf-8"))
    assert data["groups"] == {"isp-924-2": "isp-924-2a"}
    assert data["pending_groups"] == {}


def test_transient_rename_never_reaches_the_book(tmp_path, snapshot):
    """Имя вернули через одно обновление — кандидат забыт, книга пуста."""
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    book = RenameBook(tmp_path)
    book.record(snapshot, after, build_index(snapshot), build_index(after))
    book.record(after, snapshot, build_index(after), build_index(snapshot))
    assert book.group("isp-924-2") is None
    assert "isp-924-2" not in book.pending_groups


def test_chain_is_flattened(tmp_path, snapshot):
    """A -> B, потом B -> C: по A отвечаем C сразу, а не через два шага."""
    second = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    third = renamed_group(second, "ИСП-924/2а", "ИСП-924/2б")
    book = RenameBook(tmp_path)
    settle(book, snapshot, second)
    settle(book, second, third)
    assert book.group("isp-924-2") == "isp-924-2b"
    assert book.group("isp-924-2a") == "isp-924-2b"


def test_old_name_coming_back_cancels_the_alias(tmp_path, snapshot):
    """Вернули прежнее имя — это снова настоящая группа, подменять её нельзя."""
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    book = RenameBook(tmp_path)
    settle(book, snapshot, after)
    assert book.group("isp-924-2") is not None
    settle(book, after, snapshot)
    assert book.group("isp-924-2") is None
    assert book.group("isp-924-2a") == "isp-924-2"


def test_split_in_two_steps_removes_the_alias(tmp_path, snapshot):
    """Сегодня колонку переименовали в /1, завтра завели /2: запись снимается."""
    first = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    book = RenameBook(tmp_path)
    settle(book, snapshot, first)
    assert book.group("isp-924-2") == "isp-924-2a"
    second = GroupRef(name="ИСП-924/2-2", id=group_id("ИСП-924/2-2"), column=9999)
    later = Snapshot(sheet_title=first.sheet_title, groups=first.groups + [second],
                     dates=list(first.dates), schedule=dict(first.schedule))
    later.schedule[second.id] = {}
    book.record(first, later, build_index(first), build_index(later))
    assert book.group("isp-924-2") is None


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


def test_subgroup_marked_in_the_middle_of_the_name_is_a_split(snapshot):
    """«КС-926» -> «КС(1)-926»: пометка в середине, а не хвост, но это подгруппа."""
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП(1)-924/2")
    assert detect(group_traces(snapshot), group_traces(after)) == {}
    # А лишняя буква в середине — переименование, как у «…ГД.Д.ОФ…».
    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-Д-924/2")
    assert detect(group_traces(snapshot), group_traces(after)) == {"isp-924-2": "isp-d-924-2"}


def test_split_with_a_mark_in_two_steps_removes_the_alias(tmp_path, snapshot):
    first = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    book = RenameBook(tmp_path)
    settle(book, snapshot, first)
    assert book.group("isp-924-2") == "isp-924-2a"
    second = GroupRef(name="ИСП(2)-924/2", id=group_id("ИСП(2)-924/2"), column=9999)
    later = Snapshot(sheet_title=first.sheet_title, groups=first.groups + [second],
                     dates=list(first.dates), schedule=dict(first.schedule))
    later.schedule[second.id] = {}
    book.record(first, later, build_index(first), build_index(later))
    assert book.group("isp-924-2") is None


def test_teacher_replaced_by_another_person_is_not_a_rename(tmp_path, snapshot):
    """Ушёл на больничный, в его ячейки вписали другого: фамилия другая — замена."""
    index = build_index(snapshot)
    tid = max(index.schedule, key=lambda t: sum(len(v) for v in index.schedule[t].values()))
    name = index.names[tid]
    after = renamed_teacher(snapshot, name, "Замятина Ольга Петровна")
    book = RenameBook(tmp_path)
    settle(book, snapshot, after)
    assert book.teacher(tid) is None
    # А опечатка в отчестве — переименование.
    fixed = renamed_teacher(snapshot, name, name + "а")
    book = RenameBook(tmp_path)
    settle(book, snapshot, fixed)
    assert book.teacher(tid) == teacher_id(name + "а")



def test_quiet_sheet_confirms_by_time(tmp_path, snapshot, monkeypatch):
    """Лист не меняется — не разбирается, и «три обновления подряд» ждали ночи,
    а приложение через час говорило «группы больше нет» (второй аудит, В7)."""
    from whensclass.service import renames

    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    book = RenameBook(tmp_path)
    book.record(snapshot, after, build_index(snapshot), build_index(after))
    book.tick()
    assert book.group("isp-924-2") is None, "сразу — рано"
    later = renames._now() + renames.CONFIRM_AFTER
    monkeypatch.setattr(renames, "_now", lambda: later)
    book.tick()
    assert book.group("isp-924-2") == "isp-924-2a"


def _week(snapshot, first, last, shift_days=0, rename=None, gid="лист"):
    from whensclass.domain.models import SheetPlace

    out = Snapshot(sheet_title=gid)
    old_id = new_id = None
    if rename:
        old_id, new_id = group_id(rename[0]), group_id(rename[1])
    out.groups = [
        GroupRef(name=rename[1], id=new_id, column=g.column) if g.id == old_id else g
        for g in snapshot.groups
    ]
    shift = dt.timedelta(days=shift_days)
    out.schedule = {
        (new_id if g == old_id else g): {
            day + shift: lessons for day, lessons in by_date.items() if first <= day <= last
        }
        for g, by_date in snapshot.schedule.items()
    }
    out.dates = [d + shift for d in snapshot.dates if first <= d <= last]
    out.places = {d: SheetPlace(gid=gid, row=1) for d in out.dates}
    out.sheet_columns = {gid: {g.id: g.column for g in out.groups}}
    return out


def test_rename_on_the_border_of_two_sheets(tmp_path, snapshot):
    """Старый лист с «ИСП-924/2», новый — с «ИСП-924/2а». Пока оба в окне, оба
    id живут в снимке; ушёл старый — старый id исчез, а новый «не появился»:
    книга не видела такого никогда (второй аудит, В6)."""
    first, last = dt.date(2026, 9, 2), dt.date(2026, 9, 5)
    old_sheet = _week(snapshot, first, last, gid="старый")
    new_sheet = _week(snapshot, first, last, shift_days=7,
                      rename=("ИСП-924/2", "ИСП-924/2а"), gid="новый")
    both = old_sheet.merged_with(new_sheet)
    book = RenameBook(tmp_path)
    book.record(both, new_sheet, build_index(both), build_index(new_sheet))
    assert book.pending_groups["isp-924-2"]["to"] == "isp-924-2a"


def test_border_without_rename_records_nothing(tmp_path, snapshot):
    first, last = dt.date(2026, 9, 2), dt.date(2026, 9, 5)
    old_sheet = _week(snapshot, first, last, gid="старый")
    new_sheet = _week(snapshot, first, last, shift_days=7, gid="новый")
    both = old_sheet.merged_with(new_sheet)
    book = RenameBook(tmp_path)
    book.record(both, new_sheet, build_index(both), build_index(new_sheet))
    assert book.pending_groups == {} and book.groups == {}


def test_teacher_rename_on_the_border_of_two_sheets(tmp_path, snapshot):
    """Преподавателю поправили опечатку в имени только в новом листе: на стыке
    книга этого не видела — как и у групп (второй аудит, В6)."""
    first, last = dt.date(2026, 9, 2), dt.date(2026, 9, 5)
    old_sheet = _week(snapshot, first, last, gid="старый")
    index = build_index(old_sheet)
    # Самый загруженный: у кого меньше трёх пар за неделю, того не узнать и в
    # обычном переименовании (MIN_SHARED).
    busiest = max(index.names, key=lambda tid: sum(len(v) for v in index.days(tid).values()))
    teacher = index.names[busiest]
    surname = teacher.split()[0]
    fixed = surname + " Исправленное Имя"
    new_sheet = _week(renamed_teacher(snapshot, teacher, fixed), first, last, shift_days=7, gid="новый")
    both = old_sheet.merged_with(new_sheet)
    book = RenameBook(tmp_path)
    book.record(both, new_sheet, build_index(both), build_index(new_sheet))
    assert book.pending_teachers[teacher_id(teacher)]["to"] == teacher_id(fixed)


def test_rename_on_the_border_seen_without_a_merged_snapshot(tmp_path, snapshot):
    """Третий аудит, М21 прогона 1: новый лист прочитан впервые, когда старый
    уже ушёл, — склейки не было, и стык не был виден."""
    first, last = dt.date(2026, 9, 2), dt.date(2026, 9, 5)
    old_sheet = _week(snapshot, first, last, gid="старый")
    new_sheet = _week(snapshot, first, last, shift_days=7,
                      rename=("ИСП-924/2", "ИСП-924/2а"), gid="новый")
    book = RenameBook(tmp_path)
    book.record(old_sheet, new_sheet, build_index(old_sheet), build_index(new_sheet))
    assert book.pending_groups["isp-924-2"]["to"] == "isp-924-2a"

    plain = _week(snapshot, first, last, shift_days=7, gid="новый")
    quiet = RenameBook(tmp_path / "другая")
    (tmp_path / "другая").mkdir()
    quiet.record(old_sheet, plain, build_index(old_sheet), build_index(plain))
    assert quiet.pending_groups == {}


def test_confirmation_does_not_miss_the_forty_minute_refresh(tmp_path, snapshot, monkeypatch):
    """Третий аудит, М20 прогона 1: заход T+40 звал подтверждение на секунды
    раньше сорока минут от записи, и оно уезжало на T+60, а вечером — на ночь."""
    from whensclass.service import renames

    after = renamed_group(snapshot, "ИСП-924/2", "ИСП-924/2а")
    book = RenameBook(tmp_path)
    book.record(snapshot, after, build_index(snapshot), build_index(after))
    almost = renames._now() + renames.CONFIRM_AFTER - dt.timedelta(seconds=5)
    monkeypatch.setattr(renames, "_now", lambda: almost)
    book.tick()
    assert book.group("isp-924-2") == "isp-924-2a"


# --- Третий аудит, М56 прогона 2: правила осторожности книги ----------------

def _trace(*items):
    items = frozenset(items)
    return (items, items)


def test_three_of_ten_shared_lessons_are_not_a_rename():
    """MIN_SHARE: общих пар три из десяти — совпадение, не та же группа."""
    old = {"a-1": _trace(*range(10))}
    new = {"b-1": _trace(0, 1, 2, *range(100, 107))}
    assert detect(old, new) == {}


def test_two_equal_candidates_are_not_a_guess():
    """Две новые группы с теми же парами и не подгруппы — угадывать нельзя."""
    old = {"a-1": _trace(*range(10))}
    new = {"b-1": _trace(*range(10)), "v-1": _trace(*range(10))}
    assert detect(old, new) == {}
