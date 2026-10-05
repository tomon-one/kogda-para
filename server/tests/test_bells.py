"""Сетка звонков колледжа: обычная и своя у дня, записанная в листе."""

import csv
import datetime as dt
import io
import re

from whensclass.api.payloads import schedule_payload, teacher_payload
from whensclass.domain.teachers import build_index
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.parser.export import parse_csv, read_csv
from whensclass.push import changes
from whensclass.service.bells import BELLS, own_bells
from whensclass.service.refresher import Refresher
from whensclass.storage.snapshot_store import SnapshotStore, _from_dict, _to_dict

GEN = dt.datetime(2026, 9, 8, 3, 0, tzinfo=dt.timezone.utc)
TODAY = dt.date(2026, 9, 2)
SHORT_DAY = dt.date(2026, 9, 3)
# Сокращённые пары, как колледж записал их в листе на 05.10.2026.
SHORT = ["9-00-10.00", "10.10-11.10", "11.20-12.20", "12.40-13.40", "13.50-14.50", "15.00-16.00"]
SHORT_GRID = {
    "1": ["09:00", "10:00"], "2": ["10:10", "11:10"], "3": ["11:20", "12:20"],
    "4": ["12:40", "13:40"], "5": ["13:50", "14:50"], "6": ["15:00", "16:00"],
}
_TIME = re.compile(r"^\d{1,2}[.-]\d{2}-\d{1,2}\.\d{2}$")


def with_times(text: str, day: dt.date, times: list[str | None]) -> str:
    """Лист, где у `day` время пар переписано; None — ячейка времени пуста."""
    rows = read_csv(text)
    inside, left = False, list(times)
    for row in rows:
        date_cell, marker = row[0].strip(), row[1].strip()
        if re.match(r"\d\d\.\d\d\.\d{4}", date_cell):
            inside = date_cell.startswith(f"{day:%d.%m.%Y}")
        if inside and _TIME.match(marker) and left:
            row[1] = left.pop(0) or ""
    assert not left, "в фикстуре у дня меньше пар, чем времён"
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    return buf.getvalue()


def test_the_grid_we_ship_is_the_college_one():
    """Шесть пар по полтора часа, перемены 10 и 20 минут.

    Проверка не на «а вдруг кто-то опечатается»: по этой сетке считаются и
    подсветка идущей пары, и то, о каких парах напоминать.
    """
    assert list(BELLS) == ["1", "2", "3", "4", "5", "6"]
    assert BELLS["1"] == ["09:00", "10:30"]
    assert BELLS["6"] == ["17:40", "19:10"]
    for times in BELLS.values():
        assert len(times) == 2, "у пары есть начало и конец"


def test_sheet_times_are_read_for_every_day(fixture_csv):
    snapshot = parse_csv(fixture_csv, "ф", FIXTURE)
    assert set(snapshot.bells) == set(snapshot.dates)
    assert snapshot.bells[TODAY] == BELLS
    assert all(own_bells(snapshot, day) is None for day in snapshot.dates)


def test_day_with_its_own_times_differs_from_the_usual(fixture_csv):
    snapshot = parse_csv(with_times(fixture_csv, SHORT_DAY, SHORT), "ф", FIXTURE)
    assert own_bells(snapshot, SHORT_DAY) == SHORT_GRID
    assert own_bells(snapshot, TODAY) is None


def test_own_times_go_with_the_day_and_only_with_it(fixture_csv):
    snapshot = parse_csv(with_times(fixture_csv, SHORT_DAY, SHORT), "ф", FIXTURE)
    body = schedule_payload(snapshot, "bp-1126", TODAY, 7, GEN, bells=BELLS, today=TODAY)
    days = {day["d"]: day for day in body["days"]}
    assert body["bells"] == BELLS
    assert days["2026-09-03"]["bl"] == SHORT_GRID
    assert [d for d, day in days.items() if "bl" in day] == ["2026-09-03"]

    index = build_index(snapshot)
    tid = next(
        tid for tid in index.names
        if any(day == SHORT_DAY for day in index.days(tid))
    )
    teacher = teacher_payload(snapshot, index, tid, TODAY, 7, GEN, bells=BELLS, today=TODAY)
    assert [day["d"] for day in teacher["days"] if "bl" in day] == ["2026-09-03"]


def test_times_that_cannot_be_trusted_leave_the_usual_grid(fixture_csv):
    """Половине сетки не верим: время без пары, пара раньше конца прошлой, не время суток."""
    for times in (
        [SHORT[0], None],
        [SHORT[0], "9.30-10.30"],
        [SHORT[0], "10.10-9.10"],
        [SHORT[0], "25.10-26.10"],
    ):
        snapshot = parse_csv(with_times(fixture_csv, SHORT_DAY, times), "ф", FIXTURE)
        assert SHORT_DAY not in snapshot.bells, times
        assert own_bells(snapshot, SHORT_DAY) is None
        body = schedule_payload(snapshot, "bp-1126", TODAY, 7, GEN, bells=BELLS, today=TODAY)
        assert not any("bl" in day for day in body["days"])
        assert snapshot.schedule == parse_csv(fixture_csv, "ф", FIXTURE).schedule


def test_own_times_survive_the_disk_and_a_snapshot_without_them_loads(fixture_csv):
    snapshot = parse_csv(with_times(fixture_csv, SHORT_DAY, SHORT), "ф", FIXTURE)
    data = _to_dict(snapshot)
    assert _from_dict(data).bells == snapshot.bells
    del data["bells"]
    assert _from_dict(data).bells == {}


def test_neighbour_sheet_brings_its_own_times(fixture_csv):
    first = parse_csv(fixture_csv, "первый", FIXTURE)
    second = parse_csv(with_times(fixture_csv, SHORT_DAY, SHORT), "второй", FIXTURE)
    first.bells.pop(SHORT_DAY)
    assert first.merged_with(second).bells[SHORT_DAY] == SHORT_GRID
    assert second.merged_with(first).bells[SHORT_DAY] == SHORT_GRID


def test_reminder_comes_by_the_times_of_the_day():
    day = dt.date(2026, 10, 5)
    lessons = [{"n": 1, "s": "Физика"}, {"n": 2, "s": "История"}, {"n": 5, "s": "Право"}]
    usual = {"g": "isp-924-1", "gn": "ИСП-924/1", "days": [{"d": day.isoformat(), "l": lessons}]}
    short = {**usual, "days": [{**usual["days"][0], "bl": SHORT_GRID}]}

    def at(payload):
        return [(a["number"], f"{a['at']:%H:%M}", a["title"][:5]) for a in changes.reminders(payload, 5, day)]

    assert at(usual) == [(1, "08:55", "09:00"), (2, "10:35", "10:40"), (5, "15:55", "16:00")]
    assert at(short) == [(1, "08:55", "09:00"), (2, "10:05", "10:10"), (5, "13:45", "13:50")]


def test_owner_hears_about_a_day_with_its_own_times_once(tmp_path, sheet, sent, fixture_csv):
    store = SnapshotStore(tmp_path)
    refresher = Refresher(store, tmp_path)
    assert refresher.refresh() and sent == []

    sheet["text"] = with_times(fixture_csv, SHORT_DAY, SHORT)
    assert refresher.refresh()
    assert len(sent) == 1 and "у 03.09 свои звонки: 1 — 09:00–10:00" in sent[0]["message"]
    assert not sent[0]["message"].startswith("в нас проблема")

    sheet["text"] = sheet["text"].replace("Информатика", "Информатика ", 1)
    assert refresher.refresh() and len(sent) == 1
