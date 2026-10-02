"""Непрочитанный день группы: запись, которую разбор не знает, в одном-двух блоках.

Лист принимается всем остальным; у задетых групп этот день — прежними парами,
а если прежний снимок его не знал — «ещё не опубликовано». Наружу — stale для
старых версий и `refresh`/`unread` для новых. Сдвиг по многим блокам — отказ,
как и был.
"""

import csv
import datetime as dt
import io

import pytest

from whensclass.api import routes
from whensclass.api.payloads import meta_payload, schedule_payload
from whensclass.domain.models import SourceFormatChanged
from whensclass.parser.csv_schedule import FIXTURE
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
    assert body["unread"] == ["2026-09-02"]
    assert body["days"][0]["d"] == "2026-09-02" and body["days"][0]["l"]

    # Перезапуск помнит, что день не прочитан.
    again = SnapshotStore(tmp_path)
    assert again.load() and again.snapshot.unread == {"bp-1126": {DAY: True}}

    sent.clear()
    sheet["text"] = fixture_csv
    assert r.refresh(today=TODAY) is True
    assert store.snapshot.unread == {}
    assert routes._outward(store, r)[0] == "ok"
    assert any("снова прочитаны" in b["message"] for b in sent)


def test_unread_day_the_previous_snapshot_did_not_know_is_not_published(fixture_csv):
    snapshot = parse_csv(spilled(fixture_csv, BP), "ф", FIXTURE)
    _hold_unread(snapshot, None)
    assert snapshot.unread == {"bp-1126": {DAY: False}}
    body = schedule_payload(snapshot, "bp-1126", DAY, 7, dt.datetime(2026, 9, 2), today=DAY)
    assert body["days"] == [] and body["unread"] == ["2026-09-02"]
    other = schedule_payload(snapshot, "bp-926-1", DAY, 7, dt.datetime(2026, 9, 2), today=DAY)
    assert other["days"] and "unread" not in other
