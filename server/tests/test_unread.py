"""Непрочитанный день группы: запись, которую разбор не знает, в одном-двух блоках.

Лист принимается всем остальным; у задетых групп этот день — прежними парами
(`un: kept`), а если прежних пар нет — пустым с пометкой (`un: missing`).
Остальные дни группы — как обычно. Наружу — stale для старых версий и
`refresh`/`unread` для новых. Сдвиг по многим блокам — отказ, как и был.
"""

import csv
import datetime as dt
import io

import pytest

from whensclass.api import routes
from whensclass.api.payloads import meta_payload, schedule_payload, teacher_payload
from whensclass.domain.models import SourceFormatChanged
from whensclass.domain.teachers import build_index
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.push.changes import compare
from whensclass.parser.export import parse_csv, read_csv
from whensclass.service.refresher import Refresher, _hold_unread
from whensclass.storage.snapshot_store import SnapshotStore

TODAY = dt.date(2026, 9, 8)
DAY = dt.date(2026, 9, 2)
# Строка второй пары 02.09 в фикстуре и колонки блоков: БП-1126, БП-926/1,
# ГД-1125, общая ДП-923 и ДП-1124.
ROW = 7
BP, BP2, GD, DP = 2, 6, 10, 14


def spilled(text: str, *blocks: int) -> str:
    """Название в колонке +1 блоков — так разбор видит незнакомую запись."""
    rows = read_csv(text)
    for col in blocks:
        rows[ROW][col + 1] = "Математика (Лек)"
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    return buf.getvalue()


def test_text_in_one_block_marks_the_day_of_that_group(fixture_csv):
    honest = parse_csv(fixture_csv, "ф", FIXTURE)
    snapshot = parse_csv(spilled(fixture_csv, BP), "ф", FIXTURE)
    assert snapshot.unread == {"bp-1126": {DAY: False}}
    assert DAY not in snapshot.schedule["bp-1126"]
    assert "БП-1126 02.09" in snapshot.unread_why[0]
    assert snapshot.schedule["bp-926-1"] == honest.schedule["bp-926-1"]
    assert snapshot.schedule["bp-1126"][dt.date(2026, 9, 3)] == honest.schedule["bp-1126"][dt.date(2026, 9, 3)]


def test_shared_column_marks_both_groups(fixture_csv):
    snapshot = parse_csv(spilled(fixture_csv, DP), "ф", FIXTURE)
    assert set(snapshot.unread) == {"dp-923", "dp-1124"}


def test_one_letter_is_a_typo_not_an_unread_day(fixture_csv):
    rows = read_csv(fixture_csv)
    rows[ROW][BP + 1] = "з"
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    assert parse_csv(buf.getvalue(), "ф", FIXTURE).unread == {}


def test_three_blocks_are_a_shift(fixture_csv):
    parse_csv(spilled(fixture_csv, BP, BP2), "ф", FIXTURE)
    with pytest.raises(SourceFormatChanged, match="пустых колонок"):
        parse_csv(spilled(fixture_csv, BP, BP2, GD), "ф", FIXTURE)


def test_unread_day_keeps_previous_lessons_and_says_so(tmp_path, sheet, sent, fixture_csv):
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    honest = store.snapshot.schedule["bp-1126"][DAY]

    sheet["text"] = spilled(fixture_csv, BP)
    assert r.refresh(today=TODAY) is True
    assert store.snapshot.schedule["bp-1126"][DAY] == honest
    assert store.snapshot.unread == {"bp-1126": {DAY: True}}
    assert r.status == "ok"
    assert any("не прочитаны дни" in b["message"] for b in sent)

    status, since, error = routes._outward(store, r)
    assert status == "stale" and since == store.snapshot.unread_since
    assert "БП-1126 02.09" in error
    meta = meta_payload(store.snapshot, store.generated, status, r.checked_at,
                        today=TODAY, failing_since=since, error=error, refresh=r.status)
    assert meta["status"] == "stale" and meta["refresh"] == "ok"
    assert meta["unread"] == ["2026-09-02"]
    body = schedule_payload(store.snapshot, "bp-1126", DAY, 7, store.generated, today=TODAY)
    assert body["days"][0]["d"] == "2026-09-02" and body["days"][0]["l"]
    assert body["days"][0]["un"] == "kept"
    assert not any("un" in day for day in body["days"][1:])

    # Перезапуск помнит, что день не прочитан.
    again = SnapshotStore(tmp_path)
    assert again.load() and again.snapshot.unread == {"bp-1126": {DAY: True}}

    sent.clear()
    sheet["text"] = fixture_csv
    assert r.refresh(today=TODAY) is True
    assert store.snapshot.unread == {}
    assert routes._outward(store, r)[0] == "ok"
    assert any("снова прочитаны" in b["message"] for b in sent)


GEN = dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc)


def _days(body: dict) -> dict[str, dict]:
    return {day["d"]: day for day in body["days"]}


def test_unread_day_without_previous_lessons_is_marked_and_the_week_stays(fixture_csv):
    """Прежних пар нет — день пустой с пометкой, а прочитанные дни после него
    на месте, и когда он уже прошёл: «сегодня» у группы не пропадает."""
    honest = parse_csv(fixture_csv, "ф", FIXTURE)
    snapshot = parse_csv(spilled(fixture_csv, BP), "ф", FIXTURE)
    _hold_unread(snapshot, None)
    assert snapshot.unread == {"bp-1126": {DAY: False}}
    days = _days(schedule_payload(snapshot, "bp-1126", DAY, 7, GEN, today=TODAY))
    assert days["2026-09-02"]["l"] == [] and days["2026-09-02"]["un"] == "missing"
    for day in ("2026-09-03", "2026-09-04", "2026-09-05"):
        assert len(days[day]["l"]) == len(honest.schedule["bp-1126"][dt.date.fromisoformat(day)])
        assert "un" not in days[day]
    other = schedule_payload(snapshot, "bp-926-1", DAY, 7, GEN, today=TODAY)
    assert other["days"] and not any("un" in day for day in other["days"])


def test_day_that_was_free_is_not_kept_empty(fixture_csv):
    """Прежний снимок знал дату, но пар у группы в ней не было: новым — не
    «пары какими были… Пар нет», а «не прочитан». Прежним версиям — как
    раньше: пустой день и вся неделя после него, без обрезки."""
    before = parse_csv(fixture_csv, "ф", FIXTURE)
    before.schedule["bp-1126"].pop(DAY)
    snapshot = parse_csv(spilled(fixture_csv, BP), "ф", FIXTURE)
    _hold_unread(snapshot, before)
    assert snapshot.unread == {"bp-1126": {DAY: True}}
    assert _days(schedule_payload(snapshot, "bp-1126", DAY, 1, GEN, today=DAY))["2026-09-02"]["un"] == "missing"
    old = _days(schedule_payload(snapshot, "bp-1126", DAY, 7, GEN, today=DAY, marks=False))
    assert old["2026-09-02"]["l"] == [] and "un" not in old["2026-09-02"]
    assert old["2026-09-03"]["l"]


def _teacher_of(index, group_name: str, day: dt.date, number: int | None = None) -> str:
    return next(
        tid for tid, by_date in index.schedule.items()
        for entry in by_date.get(day, [])
        if number in (None, entry.lesson.number) and group_name in entry.group_name.split(", ")
    )


def test_teacher_mark_names_the_group_and_only_his(fixture_csv):
    """У преподавателя пометка — по группе, чьи пары у него в этот день
    (прежние) или на этой неделе (не прочитан), с её именем. Другие его
    группы и чужие преподаватели — без пометки."""
    before = parse_csv(fixture_csv, "ф", FIXTURE)
    tid = _teacher_of(build_index(before), "БП-1126", DAY, 2)

    kept = parse_csv(spilled(fixture_csv, BP), "ф", FIXTURE)
    _hold_unread(kept, before)
    index = build_index(kept)
    day = _days(teacher_payload(kept, index, tid, DAY, 1, GEN, today=TODAY))["2026-09-02"]
    assert (day["un"], day["ug"]) == ("kept", ["БП-1126"])
    stranger = next(t for t, groups in index.groups.items() if "bp-1126" not in groups)
    assert not any("un" in d for d in teacher_payload(kept, index, stranger, DAY, 7, GEN, today=TODAY)["days"])

    missing = parse_csv(spilled(fixture_csv, BP), "ф", FIXTURE)
    _hold_unread(missing, None)
    index = build_index(missing)
    # Ведёт у группы назавтра — значит, мог быть и в непрочитанном дне.
    weekly = _teacher_of(index, "БП-1126", DAY + dt.timedelta(days=1))
    day = _days(teacher_payload(missing, index, weekly, DAY, 1, GEN, today=TODAY))["2026-09-02"]
    assert (day["un"], day["ug"]) == ("missing", ["БП-1126"])


def _marked(fixture_csv, marks: dict[str, bool], day: dt.date = DAY):
    """Честный лист, где у групп (по имени) день не прочитан: True — пары
    прежние, False — их нет."""
    snapshot = parse_csv(fixture_csv, "ф", FIXTURE)
    ids = {g.name: g.id for g in snapshot.groups}
    for name, kept in marks.items():
        if not kept:
            snapshot.schedule[ids[name]].pop(day, None)
        snapshot.unread[ids[name]] = {day: kept}
    return snapshot, build_index(snapshot)


def test_teacher_mark_missing_keeps_the_groups_with_previous_lessons(fixture_csv):
    """В один день у одной его группы пары прежние, другая не прочитана вовсе:
    `missing` главнее, но и прежние пары названы — в `uk`."""
    snapshot, index = _marked(fixture_csv, {"ДП-1124": True, "ИСП-924/2": False})
    tid = "starostina-ekaterina-aleksandrovna"
    day = _days(teacher_payload(snapshot, index, tid, DAY, 1, GEN, today=TODAY))["2026-09-02"]
    assert (day["un"], day["ug"], day["uk"]) == ("missing", ["ИСП-924/2"], ["ДП-1124"])
    alone, index = _marked(fixture_csv, {"ДП-1124": True})
    day = _days(teacher_payload(alone, index, tid, DAY, 1, GEN, today=TODAY))["2026-09-02"]
    assert (day["un"], day["ug"]) == ("kept", ["ДП-1124"]) and "uk" not in day


def test_teacher_with_the_group_on_this_weekday_in_another_week_is_marked(fixture_csv):
    """У группы он ведёт раз в неделю — по средам, но не на этой неделе: был ли
    он в её непрочитанной среде, неизвестно."""
    snapshot, index = _marked(fixture_csv, {"ГД-1125": False})
    tid = "starostina-ekaterina-aleksandrovna"
    assert DAY.weekday() == dt.date(2026, 9, 9).weekday()
    day = _days(teacher_payload(snapshot, index, tid, DAY, 1, GEN, today=TODAY))["2026-09-02"]
    assert (day["un"], day["ug"]) == ("missing", ["ГД-1125"])


def test_teacher_is_not_marked_by_groups_he_has_nothing_to_do_with_that_day(fixture_csv):
    """Пятница: у ДП-1124 пары прежние, но он ведёт у неё по средам и
    вторникам; ГД-1125 не прочитана, но у неё он ведёт только на следующей
    неделе, и не по пятницам. Пометки нет — иначе у физрука с полусотней
    групп она стояла бы почти всегда."""
    friday = dt.date(2026, 9, 4)
    snapshot, index = _marked(fixture_csv, {"ДП-1124": True, "ГД-1125": False}, friday)
    tid = "starostina-ekaterina-aleksandrovna"
    day = _days(teacher_payload(snapshot, index, tid, friday, 1, GEN, today=TODAY))["2026-09-04"]
    assert day["l"] and "un" not in day


def test_unread_day_right_after_the_written_weeks_is_covered_alone(fixture_csv):
    """Неделю группе ещё не вписали, а её понедельник не прочитан (день без
    даты задевает всех): понедельник — в покрытии с пометкой, остаток недели —
    «ещё не опубликовано», а не «пар нет». Непрочитанная среда той же
    недели после пустых понедельника и вторника не покрывается: `cov` —
    сплошной отрезок, и они вышли бы «пар нет»."""
    snapshot = parse_csv(fixture_csv, "ф", FIXTURE)
    week = [dt.date(2026, 9, 7) + dt.timedelta(days=i) for i in range(6)]
    for day in week:
        snapshot.schedule["bp-1126"].pop(day, None)
    snapshot.unread["bp-1126"] = {week[0]: False}
    days = _days(schedule_payload(snapshot, "bp-1126", DAY, 11, GEN, today=DAY))
    assert days["2026-09-07"]["un"] == "missing" and "2026-09-08" not in days
    assert "2026-09-07" not in _days(schedule_payload(snapshot, "bp-1126", DAY, 11, GEN, today=DAY, marks=False))
    snapshot.unread["bp-1126"] = {week[2]: False}
    assert "2026-09-09" not in _days(schedule_payload(snapshot, "bp-1126", DAY, 11, GEN, today=DAY))


def test_changes_are_not_counted_on_an_unread_day():
    """День без пар «не прочитан» — не «убрали»; прочитанный следом — не
    «добавилась»."""
    read = {"g": "a-1", "gn": "А-1", "days": [{"d": "2026-09-02", "l": [{"n": 2, "s": "Х"}]}]}
    unread = {"g": "a-1", "gn": "А-1", "days": [{"d": "2026-09-02", "l": [], "un": "missing"}]}
    assert compare(read, unread) == []
    assert compare(unread, read) == []


def test_versions_without_marks_get_the_cut_as_before(fixture_csv, monkeypatch):
    """Прежние версии пометок не знают: непрочитанный день, которого прежний
    снимок не знал, прочли бы как «пар нет». Им край — перед ним, без `un`;
    новые просят пометки `?marks=1`. Прошедший такой день сегодняшний не
    режет."""
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    snapshot = parse_csv(spilled(fixture_csv, BP), "ф", FIXTURE)
    _hold_unread(snapshot, None)
    old = schedule_payload(snapshot, "bp-1126", DAY, 7, GEN, today=DAY, marks=False)
    # 02.09 — первый день листа: перед ним у группы ничего, cov нет вовсе.
    assert old["days"] == [] and "cov" not in old
    later = _days(schedule_payload(snapshot, "bp-1126", DAY, 7, GEN, today=DAY + dt.timedelta(days=1), marks=False))
    assert later["2026-09-02"]["l"] == [] and "un" not in later["2026-09-02"]
    assert later["2026-09-03"]["l"]
    monkeypatch.setattr(routes, "_today", lambda: DAY)

    class Store:
        teachers = build_index(snapshot)
        generated = GEN

        def known_teacher(self, _):
            return None

    Store.snapshot = snapshot
    app = FastAPI()
    app.include_router(routes.router)
    app.state.store, app.state.refresher = Store(), None
    client = TestClient(app)
    url = "/v1/schedule/bp-1126?from=2026-09-02&days=3"
    assert client.get(url).json()["days"] == []
    assert client.get(url + "&marks=1").json()["days"][0]["un"] == "missing"
    teacher = _teacher_of(Store.teachers, "БП-1126", DAY + dt.timedelta(days=1))
    url = f"/v1/teacher/{teacher}?from=2026-09-02&days=1"
    assert not any("un" in d for d in client.get(url).json()["days"])
    assert client.get(url + "&marks=1").json()["days"][0]["un"] == "missing"


def test_same_unread_days_remind_once_a_day_new_ones_at_once(tmp_path, sheet, sent, fixture_csv, monkeypatch):
    """О тех же непрочитанных днях — раз в сутки, о новых — сразу."""
    from whensclass.service import alerts

    store = SnapshotStore(tmp_path)
    refresher = Refresher(store, tmp_path)
    sheet["text"] = spilled(fixture_csv, BP)
    assert refresher.refresh(force=True)
    assert len(sent) == 1
    clock = {"now": alerts.time.monotonic()}
    monkeypatch.setattr(alerts.time, "monotonic", lambda: clock["now"])
    alerts._last_sent["unread"] = clock["now"]
    clock["now"] += 7 * 3600
    assert refresher.refresh(force=True) and len(sent) == 1
    clock["now"] += 18 * 3600
    assert refresher.refresh(force=True) and len(sent) == 2
    sheet["text"] = spilled(fixture_csv, BP, GD)
    assert refresher.refresh(force=True) and len(sent) == 3
