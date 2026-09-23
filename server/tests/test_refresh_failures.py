"""Заход обновления падает громко: stale, тревога, архив — но не молча.

Второй аудит (прогон 1) нашёл несколько путей, где обновление останавливалось
без единого сигнала или сигналило не тем: прочерк вместо имени преподавателя
замораживал снимок при status ok (К2), прочерк вместо имени группы выглядел
сетевым сбоем (В26), тревога о диске шла на каждом заходе (В16), перезапуск
посреди сбоя повторял тревогу (М23), чих Google сразу показывал телефонам
«сбой» (М25), страница входа выдавалась за «формат» (М7), смерть посреди
разбора не оставляла следов (М36).
"""

import datetime as dt
import gzip
import json

import pytest

from whensclass.config import settings
from whensclass.parser import cells
from whensclass.parser.csv_schedule import FIXTURE, parse_csv, read_csv
from whensclass.service import alerts, refresher as refresher_mod
from whensclass.service.refresher import FETCH_GRACE, Refresher
from whensclass.sources import gsheets, sheet_index
from whensclass.storage import history
from whensclass.storage.snapshot_store import SnapshotStore

TODAY = dt.date(2026, 9, 8)


@pytest.fixture
def sent(monkeypatch):
    box = []

    class Client:
        def __init__(self, *args, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def post(self, url, json=None):
            box.append(json)

            class R:
                status_code = 200

                def raise_for_status(self):
                    return None
            return R()

    monkeypatch.setattr(alerts.httpx, "Client", Client)
    monkeypatch.setattr(settings, "ntfy_topic", "тема")
    alerts._last_sent.clear()
    return box


@pytest.fixture
def sheet(monkeypatch, fixture_csv):
    """Лист, который служба «скачивает»: текст можно подменить в тесте."""
    box = {"text": fixture_csv}
    monkeypatch.setattr(sheet_index, "resolve_window", lambda *a, **k: [("лист", "656498718")])
    monkeypatch.setattr(gsheets, "fetch_sheet_csv", lambda gid=None, title=None: box["text"])
    monkeypatch.setattr(refresher_mod, "_limits", lambda: FIXTURE)
    return box


def cell_replace(text: str, old: str, new: str) -> str:
    assert old in text, old
    return text.replace(old, new, 1)


def a_teacher(fixture_csv) -> str:
    """Какое-нибудь имя преподавателя из фикстуры — чтобы испортить его ячейку."""
    snapshot = parse_csv(fixture_csv, "ф", FIXTURE)
    return next(t for by in snapshot.schedule.values() for ls in by.values() for x in ls
                for t in x.teachers)


# --- К2: прочерк вместо преподавателя --------------------------------------

@pytest.mark.parametrize("junk", ["-", "—", "?", ".", "..."])
def test_punctuation_is_not_a_teacher(junk):
    assert cells.split_teachers(junk) == ()
    assert cells.split_teachers(f"Иванов И. И., {junk}") == ("Иванов И. И.",)


def test_dash_under_empty_subject_is_no_lesson():
    assert cells.parse_lesson(1, "", "", "-") is None


def test_punctuation_teacher_does_not_freeze_snapshot(tmp_path, sheet, sent, fixture_csv):
    """Точка после запятой в ячейке преподавателя: снимок обновляется, индекс цел."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    name = a_teacher(fixture_csv)
    sheet["text"] = cell_replace(fixture_csv, name, f"{name}, .")
    assert r.refresh(today=TODAY, force=True) is True
    assert r.status == "ok" and "." not in store.teachers.names.values()
    # И дальше обновляется: колледж правит лист — изменения доходят.
    sheet["text"] = fixture_csv
    assert r.refresh(today=TODAY, force=True) is True


def test_unexpected_error_in_refresh_is_loud(tmp_path, sheet, sent, monkeypatch):
    """Любое исключение захода — stale и тревога, а не тишина в планировщике."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True

    def boom(*args, **kwargs):
        raise RuntimeError("что-то новое")

    monkeypatch.setattr(refresher_mod, "build_index", boom)
    assert r.refresh(today=TODAY, force=True) is False
    assert r.status == "stale"
    assert "RuntimeError" in r.last_error
    assert any("RuntimeError" in b["message"] for b in sent)


def test_bad_snapshot_on_disk_does_not_break_teachers(tmp_path, fixture_csv):
    """Снимок, записанный старой службой с именем «.», поднимается и отвечает."""
    snapshot = parse_csv(fixture_csv, "ф", FIXTURE)
    by = next(iter(snapshot.schedule.values()))
    day = next(iter(by))
    lesson = by[day][0]
    by[day][0] = type(lesson)(**{**lesson.__dict__, "teachers": lesson.teachers + (".",)})
    store = SnapshotStore(tmp_path)
    store.put(snapshot, dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc))
    again = SnapshotStore(tmp_path)
    assert again.load()
    assert "." not in again.teachers.names.values()


# --- В26: прочерк вместо имени группы ---------------------------------------

def test_dash_instead_of_group_name_is_format_not_network(tmp_path, sheet, sent, fixture_csv):
    """Колонка без имени — пропуск блока с предупреждением, а не ValueError."""
    rows = read_csv(fixture_csv)
    names = next(i for i, r in enumerate(rows) if sum(c.strip() == "Преподаватель" for c in r) >= 3) + 1
    col = next(c for c, v in enumerate(rows[names]) if v.strip())
    victim = rows[names][col].strip()
    sheet["text"] = fixture_csv.replace(victim, "-", 1)
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    r.refresh(today=TODAY)
    # Либо лист принят без этой колонки, либо отвергнут как формат — но не «сеть».
    assert not (r.last_error or "").startswith("таблица не прочиталась")


# --- В27: from далеко от сегодня --------------------------------------------

def test_far_from_is_422_not_500(fixture_csv):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from whensclass.api.routes import router

    class Store:
        snapshot = parse_csv(fixture_csv, "ф", FIXTURE)
        generated = dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc)
        teachers = None

    class Ref:
        status, last_error, failing_since, checked_at = "ok", None, None, None

    app = FastAPI()
    app.include_router(router)
    app.state.store, app.state.refresher = Store(), Ref()
    client = TestClient(app, raise_server_exceptions=False)
    group = Store.snapshot.groups[0].id
    assert client.get(f"/v1/schedule/{group}?from=9999-12-31").status_code == 422
    assert client.get(f"/v1/schedule/{group}?from=0001-01-01&days=14").status_code == 422
    assert client.get(f"/v1/schedule/{group}?from=2026-09-07").status_code == 200


# --- М20, М24, М21: архив ---------------------------------------------------

def test_today_gate_rejection_is_archived(tmp_path, sheet, sent, fixture_csv, monkeypatch):
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True

    def gate(previous, current, today):
        raise sheet_index.SheetNotFound("новый набор листов не покрывает сегодня")

    monkeypatch.setattr(refresher_mod, "_check_today_kept", gate)
    sheet["text"] = fixture_csv + "\n"
    assert r.refresh(today=TODAY, force=True) is False
    rejected = list((tmp_path / "history" / "656498718").glob("*-rejected.csv.gz"))
    assert rejected and rejected[0].with_suffix("").with_suffix(".txt").exists()


def test_text_accepted_after_rejection_is_archived(tmp_path):
    history.archive(tmp_path, "1", "лист", "abcdef0123", rejected="причина")
    assert history.archive(tmp_path, "1", "лист", "abcdef0123") is not None


def test_old_copies_are_pruned_in_every_sheet_folder(tmp_path):
    import os
    old = tmp_path / "history" / "прежний" / "2026-01-01-0000-deadbeef.csv.gz"
    old.parent.mkdir(parents=True)
    old.write_bytes(b"x")
    os.utime(old, (0, 0))
    history.archive(tmp_path, "нынешний", "лист", "0123456789")
    assert not old.exists()


# --- В16: диск ---------------------------------------------------------------

def test_disk_alert_is_not_repeated_every_refresh(tmp_path, sheet, sent, monkeypatch, fixture_csv):
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)

    def full(*args, **kwargs):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(store, "_write", full)
    for i in range(4):
        sheet["text"] = fixture_csv + "\n" * i
        r.refresh(today=TODAY, force=True)
    assert sum(1 for b in sent if "диск" in b["message"]) == 1


# --- М23: перезапуск посреди сбоя -------------------------------------------

def test_restart_during_failure_does_not_repeat_alert(tmp_path, sent, fixture_csv):
    store = SnapshotStore(tmp_path)
    store.put(parse_csv(fixture_csv, "ф", FIXTURE), dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc))
    Refresher(store, tmp_path)._fail("формат таблицы изменился: x", kind="format")
    assert len(sent) == 1
    alerts._last_sent.clear()           # новый процесс — пустая память
    again = Refresher(store, tmp_path)  # поднимает failing.json
    again._fail("формат таблицы изменился: x", kind="format")
    assert len(sent) == 1, "тревога уже уходила — окно тишины переживает перезапуск"


# --- М25: чих Google ---------------------------------------------------------

def test_network_blip_is_not_shown_as_failure(tmp_path, sent, fixture_csv):
    store = SnapshotStore(tmp_path)
    store.put(parse_csv(fixture_csv, "ф", FIXTURE), dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc))
    r = Refresher(store, tmp_path)
    r._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    assert r.status == "ok" and sent == []
    # Полчаса спустя — уже сбой, и наружу, и владельцу.
    r.failing_since -= FETCH_GRACE
    r._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    assert r.status == "stale" and len(sent) == 1
    # Не сеть — показывать сразу, даже если началось с чиха.
    r2 = Refresher(store, tmp_path / "другой")
    (tmp_path / "другой").mkdir()
    r2._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    r2._fail("формат таблицы изменился: x", kind="format")
    assert r2.status == "stale"


# --- М7: страница входа ------------------------------------------------------

def test_html_instead_of_csv_is_closed_sheet(monkeypatch):
    class Resp:
        headers = {"content-type": "text/html; charset=utf-8"}
        text = "<!DOCTYPE html><html>Вход</html>"

        def raise_for_status(self):
            return None

    class Client:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def get(self, *a, **k):
            return Resp()

    monkeypatch.setattr(gsheets, "_client", lambda: Client())
    with pytest.raises(gsheets.SheetClosed):
        gsheets.fetch_sheet_csv(gid="1")


def test_closed_sheet_alerts_at_once(tmp_path, sheet, sent, monkeypatch, fixture_csv):
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True

    def closed(gid=None, title=None):
        raise gsheets.SheetClosed("пришла страница")

    monkeypatch.setattr(gsheets, "fetch_sheet_csv", closed)
    r.refresh(today=TODAY, force=True)
    assert r.status == "stale" and "закрыта" in r.last_error and len(sent) == 1


# --- М26: мёртвый ключ -------------------------------------------------------

def test_dead_sheets_key_alerts_after_two_failures(monkeypatch, sent):
    monkeypatch.setattr(sheet_index.settings, "sheets_api_key", "ключ")
    monkeypatch.setattr(sheet_index, "_api_failures", 0)

    def dead(key):
        raise RuntimeError("403")

    monkeypatch.setattr(gsheets, "list_sheets_via_api", dead)
    monkeypatch.setattr(gsheets, "list_sheets_via_xlsx", lambda: [])
    monkeypatch.setattr(sheet_index, "_last_xlsx", None)
    sheet_index.list_sheets()
    assert sent == [], "один отказ — чих"
    sheet_index.list_sheets()
    assert len(sent) == 1 and "Sheets API" in sent[0]["message"]
    assert "ключ" not in sent[0]["message"].split("(")[1].split(")")[0]


# --- М36: смерть посреди разбора --------------------------------------------

def test_crash_mid_refresh_twice_is_reported(tmp_path, sheet, sent, fixture_csv):
    store = SnapshotStore(tmp_path)
    store.put(parse_csv(fixture_csv, "ф", FIXTURE), dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc))
    (tmp_path / "refresh.running").write_text("2", "utf-8")  # два захода не кончились
    r = Refresher(store, tmp_path)
    r.refresh(today=TODAY)
    assert any("умерла посреди захода" in b["message"] for b in sent)
    assert not (tmp_path / "refresh.running").exists()


def test_single_interrupted_refresh_is_quiet(tmp_path, sheet, sent):
    (tmp_path / "refresh.running").write_text("1", "utf-8")  # выкладка посреди захода
    r = Refresher(SnapshotStore(tmp_path), tmp_path)
    assert r.refresh(today=TODAY) is True
    assert sent == []
