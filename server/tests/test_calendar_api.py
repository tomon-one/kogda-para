"""Календарный график: `st` в дне, отметка в /v1/meta, хранение прочитанного."""

import csv
import datetime as dt
import io

import pytest
from conftest import GOLDEN

from whensclass.api.payloads import meta_payload, schedule_payload
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.parser.export import parse_csv
from whensclass.service.calendar import CalendarSource
from whensclass.sources.gsheets import SheetInfo

GENERATED = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.UTC)
START = dt.date(2026, 9, 7)


@pytest.fixture(scope="module")
def snapshot(fixture_csv):
    return parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)


def test_день_получает_состояние_и_с_парами(snapshot):
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    days = {d["d"]: d for d in schedule_payload(
        snapshot, group.id, START, 3, GENERATED,
        calendar={"2026-09-08": "session"},
    )["days"]}
    assert days["2026-09-08"]["st"] == "session"
    # Пометка не прячет пары: день остаётся днём с парами (вариант В).
    assert "l" in days["2026-09-08"]
    assert "st" not in days["2026-09-07"]


def test_без_графика_поля_нет(snapshot):
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, START, 3, GENERATED)
    assert all("st" not in day for day in body["days"])
    # Старые поля не изменились: тот же ответ, что и в golden.
    assert body == __import__("json").loads((GOLDEN / "isp-924-2.json").read_text("utf-8"))


def test_meta_говорит_когда_прочитан_график(snapshot):
    body = meta_payload(snapshot, GENERATED, "ok", None, calendar_at="2026-09-07T05:23:00+00:00")
    assert body["calendar"] == "2026-09-07T05:23:00+00:00"
    assert "calendar" not in meta_payload(snapshot, GENERATED, "ok", None)


def _sheet(rows):
    out = io.StringIO()
    csv.writer(out).writerows(rows)
    return out.getvalue()


CALENDAR = _sheet([
    ["месяц", "сентябрь", "", ""],
    ["№ недели", "1", "2", "3"],
    ["дата", "1-6", "7-13", "14-20"],
    ["1 КУРС"],
    ["АА-101/1-2", "", "::", "="],
    ["ББ-102", "УП", "", ""],
])


def _source(tmp_path, monkeypatch, text=CALENDAR):
    monkeypatch.setattr(
        "whensclass.service.calendar.sheet_index.list_sheets",
        lambda max_age=None: [SheetInfo("Календарный график 2026-2027г.", "42")],
    )
    monkeypatch.setattr(
        "whensclass.service.calendar.gsheets.fetch_sheet_csv",
        lambda gid=None, title=None: text,
    )
    names = {"АА-101/1": "aa-101-1", "АА-101/2": "aa-101-2", "ББ-102": "bb-102"}
    return CalendarSource(tmp_path, lambda: names)


def test_источник_кладёт_дни_по_id(tmp_path, monkeypatch):
    source = _source(tmp_path, monkeypatch)
    source.refresh()
    assert source.states("aa-101-1")["2026-09-07"] == "session"
    assert source.states("aa-101-2")["2026-09-14"] == "holidays"
    assert source.states("bb-102")["2026-09-01"] == "practice"
    assert source.read_at is not None


def test_сбой_чтения_оставляет_прежнее(tmp_path, monkeypatch):
    source = _source(tmp_path, monkeypatch)
    source.refresh()
    before = source.payload

    def boom(gid=None, title=None):
        raise OSError("нет связи")

    monkeypatch.setattr("whensclass.service.calendar.gsheets.fetch_sheet_csv", boom)
    source.refresh()
    assert source.payload == before


def test_листа_нет_прежнее_не_стирается(tmp_path, monkeypatch):
    source = _source(tmp_path, monkeypatch)
    source.refresh()
    before = source.payload
    monkeypatch.setattr(
        "whensclass.service.calendar.sheet_index.list_sheets",
        lambda max_age=None: [],
    )
    source.refresh()
    assert source.payload == before
