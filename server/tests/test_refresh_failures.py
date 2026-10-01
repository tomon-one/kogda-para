"""Заход обновления падает громко: stale, тревога, архив — но не молча.

Раньше было несколько путей, где обновление останавливалось
без единого сигнала или сигналило не тем: тревога о диске шла на каждом
заходе, перезапуск посреди сбоя повторял тревогу, чих Google сразу показывал
телефонам «сбой», страница входа выдавалась за «формат», смерть посреди
разбора не оставляла следов.

Общие для тестов захода `sent` и `sheet` — в conftest.py.
"""

import datetime as dt

import pytest

from whensclass.config import settings
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.parser.export import parse_csv
from whensclass.service import alerts, refresher as refresher_mod
from whensclass.service.failing import FETCH_GRACE
from whensclass.service.refresher import Refresher
from whensclass.sources import gsheets, sheet_index
from whensclass.storage import history
from whensclass.storage.snapshot_store import SnapshotStore


TODAY = dt.date(2026, 9, 8)


def cell_replace(text: str, old: str, new: str) -> str:
    assert old in text, old
    return text.replace(old, new, 1)


def a_teacher(fixture_csv) -> str:
    """Какое-нибудь имя преподавателя из фикстуры — чтобы испортить его ячейку."""
    snapshot = parse_csv(fixture_csv, "ф", FIXTURE)
    return next(t for by in snapshot.schedule.values() for ls in by.values() for x in ls
                for t in x.teachers)


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


# --- Архив ------------------------------------------------------------------


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


# --- Диск -------------------------------------------------------------------


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


# --- Перезапуск посреди сбоя ------------------------------------------------


def test_restart_during_failure_does_not_repeat_alert(tmp_path, sent, fixture_csv):
    store = SnapshotStore(tmp_path)
    store.put(parse_csv(fixture_csv, "ф", FIXTURE), dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc))
    Refresher(store, tmp_path)._fail("формат таблицы изменился: x", kind="format")
    assert len(sent) == 1
    alerts._last_sent.clear()           # новый процесс — пустая память
    again = Refresher(store, tmp_path)  # поднимает failing.json
    again._fail("формат таблицы изменился: x", kind="format")
    assert len(sent) == 1, "тревога уже уходила — окно тишины переживает перезапуск"


# --- Чих Google -------------------------------------------------------------


def test_network_blip_is_not_shown_as_failure(tmp_path, sent, fixture_csv):
    store = SnapshotStore(tmp_path)
    store.put(parse_csv(fixture_csv, "ф", FIXTURE), dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc))
    r = Refresher(store, tmp_path)
    r._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    assert r.status == "ok" and sent == []
    # Полчаса спустя — уже сбой, и наружу, и владельцу.
    r.failing_since -= FETCH_GRACE
    r._fetch_since -= FETCH_GRACE
    r._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    assert r.status == "stale" and len(sent) == 1
    # Не сеть — показывать сразу, даже если началось с чиха.
    r2 = Refresher(store, tmp_path / "другой")
    (tmp_path / "другой").mkdir()
    r2._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    r2._fail("формат таблицы изменился: x", kind="format")
    assert r2.status == "stale"


# --- Страница входа ---------------------------------------------------------


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


# --- Смерть посреди разбора -------------------------------------------------


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


def test_network_blip_during_format_failure_is_not_a_new_alarm(tmp_path, sent, fixture_csv):
    """Лист отвергнут по формату, через час один
    таймаут Google. Раньше льгота сети считалась от начала всего сбоя — и
    сразу уходила тревога «таблица не прочиталась», а err подменялся сетевым.
    И окно тишины после перезапуска помнилось только для последнего вида."""
    store = SnapshotStore(tmp_path)
    store.put(
        parse_csv(fixture_csv, "ф", FIXTURE), dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc)
    )
    r = Refresher(store, tmp_path)
    r._fail("формат таблицы изменился: x", kind="format")
    assert len(sent) == 1
    r.failing_since -= dt.timedelta(hours=1)
    r._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    assert len(sent) == 1, "один таймаут посреди отказа по формату — не тревога"
    assert r.last_error.startswith("формат таблицы изменился")

    # Сеть лежит полчаса подряд — тревога о сети, и после перезапуска окно
    # тишины помнится и для неё, и для формата.
    r._fetch_since -= FETCH_GRACE
    r._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    assert len(sent) == 2
    alerts._last_sent.clear()
    again = Refresher(store, tmp_path)
    again._fail("формат таблицы изменился: x", kind="format")
    assert len(sent) == 2, "формат уже сказан — окно тишины пережило перезапуск"


def test_freeze_is_stale_at_once_without_touching_the_sheet(
    tmp_path, sheet, sent, monkeypatch
):
    """Рычаг заморозки из runbook
    (SPREADSHEET_ID=stop) полчаса держал ok, стирал память о листе и вёл
    ссылку на таблицу в никуда. WHENSCLASS_FREEZE — сразу stale на прежнем
    снимке, в сеть не ходим, тревог нет."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    before = store.snapshot

    def no_network(*args, **kwargs):
        raise AssertionError("замороженная служба в таблицу не ходит")

    monkeypatch.setattr(gsheets, "fetch_sheet_csv", no_network)
    monkeypatch.setattr(sheet_index, "resolve_window", no_network)
    monkeypatch.setattr(settings, "freeze", True)
    assert r.refresh(today=TODAY, force=True) is False
    assert r.status == "stale" and "заморожено" in r.last_error
    assert store.snapshot is before and sent == []
    assert r.look_for_new_sheet() is False


def test_recovery_with_a_failing_disk_still_closes_the_failure(
    tmp_path, sheet, sent, monkeypatch, fixture_csv
):
    """Лист починили, а снимок не лёг на диск —
    сбой не закрывался, «починилось» не уходило, и первый чих сети сразу давал
    stale."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    r._fail("формат таблицы изменился: x", kind="format")

    def full(*args, **kwargs):
        raise OSError("места нет")

    monkeypatch.setattr(store, "put", full)
    sheet["text"] = cell_replace(fixture_csv, a_teacher(fixture_csv), "Смит А. А.")
    assert r.refresh(today=TODAY, force=True) is True
    assert r.status == "ok" and r.failing_since is None
    assert any("снова обновляется" in m["message"] for m in sent)
    assert any("не записался" in m["message"] for m in sent)


def test_disk_alert_window_survives_restart_and_recovery_is_told(
    tmp_path, sheet, sent, monkeypatch
):
    """Тревога о диске жила в памяти — после
    перезапуска посреди той же беды уходила снова, а «починилось» не
    приходило вовсе."""
    store = SnapshotStore(tmp_path)

    def full(*args, **kwargs):
        raise OSError("места нет")

    monkeypatch.setattr(store, "put", full)
    r = Refresher(store, tmp_path)
    r.refresh(today=TODAY)
    assert sum("не записался" in m["message"] for m in sent) == 1
    alerts._last_sent.clear()
    again = Refresher(store, tmp_path)
    again.refresh(today=TODAY, force=True)
    assert sum("не записался" in m["message"] for m in sent) == 1, "окно пережило перезапуск"
    fresh = SnapshotStore(tmp_path)
    healed = Refresher(fresh, tmp_path)
    assert healed.refresh(today=TODAY, force=True) is True
    assert any("снова записывается" in m["message"] for m in sent)


def test_network_grace_is_half_an_hour_by_the_clock(tmp_path, sent, fixture_csv):
    """Льгота держалась только относительно себя —
    при FETCH_GRACE в секунду тесты были зелёными. Тут — минутами."""
    store = SnapshotStore(tmp_path)
    store.put(
        parse_csv(fixture_csv, "ф", FIXTURE), dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc)
    )
    r = Refresher(store, tmp_path)
    r._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    r.failing_since -= dt.timedelta(minutes=29)
    r._fetch_since -= dt.timedelta(minutes=29)
    r._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    assert r.status == "ok" and sent == []
    r.failing_since -= dt.timedelta(minutes=2)
    r._fetch_since -= dt.timedelta(minutes=2)
    r._fail("таблица не прочиталась: ReadTimeout", kind="fetch")
    assert r.status == "stale" and len(sent) == 1


def test_snapshot_survives_the_disk_whole(tmp_path, fixture_csv):
    """Снимок на диске проверялся только по places —
    забудь дописать новое поле пары, и тесты зелёные. Тут — целиком."""
    from whensclass.parser.export import parse_export

    snapshot = parse_export(fixture_csv, "лист", "656498718", limits=FIXTURE)
    store = SnapshotStore(tmp_path)
    store.put(snapshot, dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc))
    loaded = SnapshotStore(tmp_path)
    assert loaded.load()
    assert loaded.snapshot == snapshot
