"""Контракт JSON. Golden-файл обновляется только осознанно:

    python tools/update_golden.py

Если тест упал — сначала посмотрите на diff: контракт читает приложение,
которое уже стоит у людей на телефонах.
"""

import datetime as dt
import json

import pytest

from whensclass.api.payloads import groups_payload, schedule_payload
from whensclass.parser.csv_schedule import FIXTURE, parse_csv

from conftest import GOLDEN

GENERATED = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.timezone.utc)
START = dt.date(2026, 9, 7)


@pytest.fixture(scope="module")
def snapshot(fixture_csv):
    return parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)


def test_schedule_matches_golden(snapshot):
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, START, 3, GENERATED)
    expected = json.loads((GOLDEN / "isp-924-2.json").read_text("utf-8"))
    assert body == expected


def test_unknown_group_gives_nothing(snapshot):
    assert schedule_payload(snapshot, "нет-такой", START, 3, GENERATED) is None


def test_empty_fields_are_absent(snapshot):
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, START, 3, GENERATED)
    for day in body["days"]:
        for lesson in day["l"]:
            assert None not in lesson.values()
            assert set(lesson) <= {"n", "s", "k", "t", "r", "u", "x", "c", "o"}


def test_day_out_of_coverage_is_omitted(snapshot):
    """Дата за пределами листа не попадает в ответ — это не «пар нет»."""
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, dt.date(2026, 9, 11), 5, GENERATED)
    assert [d["d"] for d in body["days"]] == ["2026-09-11", "2026-09-12"]


def test_free_day_is_present_but_empty(snapshot):
    """А вот день без пар в ответе есть — с пустым списком."""
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, dt.date(2026, 9, 5), 1, GENERATED)
    assert body["days"] == [{"d": "2026-09-05", "l": []}]


def test_bells_included_only_when_known(snapshot):
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    assert "bells" not in schedule_payload(snapshot, group.id, START, 3, GENERATED)
    with_bells = schedule_payload(
        snapshot, group.id, START, 3, GENERATED, bells={"1": ["08:30", "10:00"]}
    )
    assert with_bells["bells"] == {"1": ["08:30", "10:00"]}


def test_groups_payload(snapshot):
    body = groups_payload(snapshot, GENERATED)
    assert body["v"] == 1
    ids = [g["id"] for g in body["groups"]]
    assert "isp-924-2" in ids
    assert len(ids) == len(set(ids)), "идентификаторы групп обязаны быть уникальны"


def test_online_without_a_link_still_says_online(snapshot):
    """Признак «онлайн» отдельный от ссылки, и в ответе он есть без неё.

    Ровно эта подмена — признак через его следствие — стоила нам вечера
    8 сентября: приложение объявило четыре пары «ставшими онлайн» в день,
    когда им всего лишь дописали ссылки. Разбор ячейки закреплён в
    test_cells.py, а сборка ответа не проверялась ничем: ключ можно было
    выбросить целиком, и весь набор остался бы зелёным.
    """
    import dataclasses

    from whensclass.domain.models import Snapshot

    # В фикстуре до 14 сентября 2026 у БП-926/1 11 сентября стояли четыре
    # онлайн-пары «без ссылки» — на деле ссылки там были, их выбрасывал gviz.
    # Пары без ссылки в листе есть (слово «онлайн» в колонке аудитории), но
    # не у групп фикстуры, поэтому здесь они сделаны из настоящих.
    group = next(g for g in snapshot.groups if g.name == "БП-926/1")
    day = dt.date(2026, 9, 11)
    bare = Snapshot(sheet_title=snapshot.sheet_title, groups=list(snapshot.groups),
                    dates=list(snapshot.dates))
    bare.schedule = {group.id: {day: [
        dataclasses.replace(x, url=None, room=None, online=True)
        for x in snapshot.schedule[group.id][day]
    ]}}
    body = schedule_payload(bare, group.id, day, 1, GENERATED)

    lessons = body["days"][0]["l"]
    assert lessons, "в фикстуре у этой группы 11 сентября четыре онлайн-пары"
    for lesson in lessons:
        assert lesson["o"] == 1
        assert "u" not in lesson, "ссылки у этих пар нет"
        assert "r" not in lesson, "вместо аудитории в ячейке стояло слово «онлайн»"


def test_offline_lesson_has_no_online_mark(snapshot):
    """И обратное: у очной пары признака нет вовсе, а не «o»: 0."""
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, START, 3, GENERATED)

    offline = [lesson for day in body["days"] for lesson in day["l"] if "r" in lesson]
    assert offline, "в окне должны быть пары с аудиторией"
    for lesson in offline:
        assert "o" not in lesson
