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
    """Прежний снимок знал дату, но пар у группы в ней не было (неделю
    вписывают впервые): не «пары какими были… Пар нет», а «не прочитан»."""
    before = parse_csv(fixture_csv, "ф", FIXTURE)
    before.schedule["bp-1126"].pop(DAY)
    snapshot = parse_csv(spilled(fixture_csv, BP), "ф", FIXTURE)
    _hold_unread(snapshot, before)
    assert snapshot.unread == {"bp-1126": {DAY: False}}
    assert _days(schedule_payload(snapshot, "bp-1126", DAY, 1, GEN, today=TODAY))["2026-09-02"]["un"] == "missing"


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


def test_changes_are_not_counted_on_an_unread_day():
    """День без пар «не прочитан» — не «убрали»; прочитанный следом — не
    «добавилась»."""
    read = {"g": "a-1", "gn": "А-1", "days": [{"d": "2026-09-02", "l": [{"n": 2, "s": "Х"}]}]}
    unread = {"g": "a-1", "gn": "А-1", "days": [{"d": "2026-09-02", "l": [], "un": "missing"}]}
    assert compare(read, unread) == []
    assert compare(unread, read) == []


def test_versions_without_marks_get_the_cut_as_before(fixture_csv):
    """Прежние версии пометок не знают: пустой непрочитанный день прочли бы как
    «пар нет» и прислали бы «убрали». Им край — перед ним, без `un`; новые
    просят пометки `?marks=1`."""
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    snapshot = parse_csv(spilled(fixture_csv, BP), "ф", FIXTURE)
    _hold_unread(snapshot, None)
    old = schedule_payload(snapshot, "bp-1126", DAY, 7, GEN, today=TODAY, marks=False)
    # 02.09 — первый день листа: перед ним у группы ничего, cov нет вовсе.
    assert old["days"] == [] and "cov" not in old

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
