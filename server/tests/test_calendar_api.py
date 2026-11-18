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


def test_неделя_графика_за_краем_колонки_приходит_с_состоянием(snapshot):
    """Каникулы в неделе, где у группы нет ни одной пары.

    Край группы — её последний день с парами плюс остаток той же недели;
    неделя каникул за ним не приходила вовсе, и человек видел «ещё не
    опубликовано» вместо состояния дня.
    """
    from whensclass.domain.models import Snapshot

    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    week = [dt.date(2026, 9, 14) + dt.timedelta(days=i) for i in range(6)]
    # Каркас дат так и пишет колледж: даты без пар на неделю вперёд.
    snap = Snapshot(
        sheet_title=snapshot.sheet_title,
        groups=list(snapshot.groups),
        dates=sorted({*snapshot.dates, *week, dt.date(2026, 9, 21)}),
    )
    snap.schedule = dict(snapshot.schedule)
    body = schedule_payload(
        snap, group.id, START, 14, GENERATED, today=dt.date(2026, 9, 15),
        calendar={d.isoformat(): "holidays" for d in week},
    )
    days = {d["d"]: d for d in body["days"]}
    for day in week:
        assert days[day.isoformat()]["l"] == []
        assert days[day.isoformat()]["st"] == "holidays"
    # Край группы и дни с парами не меняются: cov прежний, `st` у дней внутри него нет.
    assert body["cov"] == ["2026-09-02", "2026-09-12"]
    assert "st" not in days["2026-09-12"]
    # Исключение узкое: будень за краем без пометки и воскресенья в листе нет.
    assert "2026-09-21" not in days
    assert "2026-09-20" not in days


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


def test_тот_же_график_второй_раз_на_диск_не_пишется(tmp_path, monkeypatch):
    """Сравнение с прежним не может споткнуться об `updated`: он есть у обеих сторон."""
    from whensclass.service import calendar as module

    source = _source(tmp_path, monkeypatch)
    writes = []
    real = module.write_json
    monkeypatch.setattr(
        module, "write_json", lambda path, body: (writes.append(body["updated"]), real(path, body))
    )
    source.refresh()
    assert len(writes) == 1
    # Заход через сутки: лист тот же, пометки те же — файл и время не двигаются.
    source.now = lambda: dt.datetime(2026, 9, 8, 6, 23, tzinfo=dt.UTC)
    source.refresh()
    assert len(writes) == 1
    assert source.read_at == writes[0]


def test_повтор_только_после_неудачи(tmp_path, monkeypatch):
    """Суточный заход не удался — повтор; прочитанный график не перечитывается."""
    source = _source(tmp_path, monkeypatch)
    calls = []

    def counted(text):
        def fetch(gid=None, title=None):
            calls.append(1)
            return text
        return fetch

    monkeypatch.setattr("whensclass.service.calendar.gsheets.fetch_sheet_csv", counted(CALENDAR))
    source.refresh()
    assert not source.failed and len(calls) == 1
    source.refresh_if_failed()
    assert len(calls) == 1

    def boom(gid=None, title=None):
        calls.append(1)
        raise OSError("нет связи")

    monkeypatch.setattr("whensclass.service.calendar.gsheets.fetch_sheet_csv", boom)
    source.refresh()
    assert source.failed and len(calls) == 2
    # Прежний график отдаётся, а следующий час пробует ещё раз.
    assert source.read_at is not None
    source.refresh_if_failed()
    assert len(calls) == 3


def test_график_читается_раз_в_сутки_и_при_старте():
    """Не каждый час: каждая выгрузка листа приближает отказ Google."""
    import zoneinfo

    from apscheduler.schedulers.background import BackgroundScheduler

    from whensclass.main import schedule_calendar

    class Source:
        def refresh(self):
            ...

        def refresh_if_failed(self):
            ...

    zone = zoneinfo.ZoneInfo("Asia/Novosibirsk")
    scheduler = BackgroundScheduler(timezone=zone)
    schedule_calendar(scheduler, Source(), zone, 6, 22)

    trigger = scheduler.get_job("calendar").trigger
    at = dt.datetime(2026, 11, 17, tzinfo=zone)
    fires = []
    moment = trigger.get_next_fire_time(None, at)
    while moment is not None and moment.date() == at.date():
        fires.append(moment.strftime("%H:%M"))
        moment = trigger.get_next_fire_time(moment, moment + dt.timedelta(minutes=1))
    assert fires == ["06:23"]
    # Повтор — гейт по неудаче, а не второй суточный заход.
    assert scheduler.get_job("calendar-retry").func.__name__ == "refresh_if_failed"
    starts = [job for job in scheduler.get_jobs() if job.id not in ("calendar", "calendar-retry")]
    assert len(starts) == 1 and starts[0].trigger.__class__.__name__ == "DateTrigger"
