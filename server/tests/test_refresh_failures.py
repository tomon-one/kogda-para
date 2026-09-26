"""Заход обновления падает громко: stale, тревога, архив — но не молча.

Раньше было несколько путей, где обновление останавливалось
без единого сигнала или сигналило не тем: прочерк вместо имени преподавателя
замораживал снимок при status ok, прочерк вместо имени группы выглядел
сетевым сбоем, тревога о диске шла на каждом заходе, перезапуск
посреди сбоя повторял тревогу, чих Google сразу показывал телефонам
«сбой», страница входа выдавалась за «формат», смерть посреди
разбора не оставляла следов.
"""

import datetime as dt

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


def _csv(rows) -> str:
    import csv
    import io

    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    return buf.getvalue()


def cell_replace(text: str, old: str, new: str) -> str:
    assert old in text, old
    return text.replace(old, new, 1)


def a_teacher(fixture_csv) -> str:
    """Какое-нибудь имя преподавателя из фикстуры — чтобы испортить его ячейку."""
    snapshot = parse_csv(fixture_csv, "ф", FIXTURE)
    return next(t for by in snapshot.schedule.values() for ls in by.values() for x in ls
                for t in x.teachers)


# --- Прочерк вместо преподавателя -------------------------------------------

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
    # Через csv.writer, с кавычками: без них запятая резала ячейку, и «.» уезжала
    # в соседнюю колонку, которую разбор не читает.
    rows = read_csv(fixture_csv)
    i, j = next(
        (i, j) for i, row in enumerate(rows) for j, cell in enumerate(row) if name in cell
    )
    rows[i][j] = rows[i][j].replace(name, f"{name}, .")
    sheet["text"] = _csv(rows)
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


# --- Прочерк вместо имени группы --------------------------------------------

def test_dash_instead_of_group_name_is_format_not_network(tmp_path, sheet, sent, fixture_csv):
    """Колонка без имени — пропуск блока с предупреждением, а не ValueError."""
    rows = read_csv(fixture_csv)
    names = next(i for i, r in enumerate(rows) if sum(c.strip() == "Преподаватель" for c in r) >= 3) + 1
    # Колонка имени — та, где выше стоит «Преподаватель», а не первая непустая:
    # прежний тест попадал в «№» и проходил и на коде до починки.
    col = next(c for c, v in enumerate(rows[names - 1]) if v.strip() == "Преподаватель")
    known = {g.name for g in parse_csv(fixture_csv, "ф", FIXTURE).groups}
    assert rows[names][col].strip() in known, "портим именно имя группы"
    rows[names][col] = "-"
    sheet["text"] = _csv(rows)
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    r.refresh(today=TODAY)
    # Либо лист принят без этой колонки, либо отвергнут как формат — но не «сеть».
    assert not (r.last_error or "").startswith("таблица не прочиталась")
    assert r.status == "ok" or "формат" in (r.last_error or "")


# --- from далеко от сегодня -------------------------------------------------

def test_far_from_is_422_not_500(fixture_csv, monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from whensclass.api import routes
    from whensclass.api.routes import router

    # «Сегодня» — своё: с 09.09.2027 настоящее сделало бы from=2026-09-07
    # далёким, и красный тест остановил бы выкладку.
    monkeypatch.setattr(routes, "_today", lambda: dt.date(2026, 9, 8))

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


# --- Мёртвый ключ -----------------------------------------------------------

def test_dead_sheets_key_alerts_after_two_failures(monkeypatch, sent):
    monkeypatch.setattr(sheet_index.settings, "sheets_api_key", "ключ")
    monkeypatch.setattr(sheet_index, "_api_failures", 0)

    def dead(key):
        raise _google_says(403)

    monkeypatch.setattr(gsheets, "list_sheets_via_api", dead)
    monkeypatch.setattr(sheet_index, "_last_list", [])
    _list()
    assert sent == [], "один отказ — чих"
    _list()
    assert len(sent) == 1 and "Sheets API" in sent[0]["message"]
    assert "ключ" not in sent[0]["message"].split("(")[1].split(")")[0]


def _google_says(code: int, url: str = "https://sheets.googleapis.com/v4/spreadsheets/x"):
    import httpx

    request = httpx.Request("GET", url)
    return httpx.HTTPStatusError(
        f"Client error '{code}' for url '{url}'", request=request,
        response=httpx.Response(code, request=request),
    )


def test_network_trouble_is_not_blamed_on_the_key(monkeypatch, sent):
    """Полчаса без связи с Google давали тревогу
    «Проверить ключ». Отказ ключа — только ответ Google о ключе или квоте; и
    когда ключ снова работает, об этом говорится."""
    import httpx

    monkeypatch.setattr(sheet_index.settings, "sheets_api_key", "ключ")
    monkeypatch.setattr(sheet_index, "_api_failures", 0)
    failure = {"exc": httpx.ConnectError("нет связи")}

    def api(key):
        if failure["exc"]:
            raise failure["exc"]
        return []

    monkeypatch.setattr(gsheets, "list_sheets_via_api", api)
    monkeypatch.setattr(sheet_index, "_last_list", [])
    for _ in range(3):
        _list()
    assert sent == []
    failure["exc"] = _google_says(403)
    _list()
    _list()
    assert len(sent) == 1
    failure["exc"] = None
    _list()
    assert len(sent) == 2 and "ключ работает" in sent[1]["message"]


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


# --- Недописанный следующий лист -------------------------------------------

def _first_day_only(fixture_csv: str, new_date: str) -> str:
    """Следующий лист, который колледж только начал: шапка и один день."""
    import csv
    import io

    from whensclass.parser.csv_schedule import date_rows

    rows = read_csv(fixture_csv)
    starts = sorted(date_rows([r[0] if r else "" for r in rows]).values())
    first, second = starts[0] - 1, starts[1] - 1
    kept = rows[:second]
    kept[first][0] = new_date
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(kept)
    return buf.getvalue()


def test_half_built_next_sheet_is_left_out_not_fatal(tmp_path, sent, fixture_csv, monkeypatch):
    """Колледж завёл следующий лист и вписал в него один день. Раньше его
    отказ по объёму валил весь набор: stale у всех, правки сегодняшнего листа
    не доходили, а err не называл лист. Теперь он пропускается до поры."""
    texts = {
        "лист": fixture_csv,
        "следующий": _first_day_only(fixture_csv, "14.09.2026 понедельник"),
    }
    monkeypatch.setattr(
        sheet_index, "resolve_window", lambda *a, **k: [("лист", "1"), ("следующий", "2")]
    )
    monkeypatch.setattr(gsheets, "fetch_sheet_csv", lambda gid=None, title=None: texts[title])
    monkeypatch.setattr(refresher_mod, "_limits", lambda: FIXTURE)

    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    assert r.status == "ok" and not sent
    assert max(store.snapshot.dates) < dt.date(2026, 9, 14)
    rejected = list((tmp_path / "history" / "2").glob("*-rejected.txt"))
    assert rejected and "недописан" in rejected[0].read_text("utf-8")

    # Правка сегодняшнего листа доходит, пока следующий недописан.
    name = a_teacher(fixture_csv)
    texts["лист"] = cell_replace(fixture_csv, name, "Новиков Н. Н.")
    assert r.refresh(today=TODAY) is True
    assert "Новиков Н. Н." in store.teachers.names.values()


def test_too_small_current_sheet_still_fails_and_names_itself(
    tmp_path, sheet, sent, fixture_csv
):
    """Мал сам сегодняшний лист — это по-прежнему отказ, и err называет лист."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    sheet["text"] = _first_day_only(fixture_csv, "08.09.2026 вторник")
    assert r.refresh(today=TODAY) is False
    assert r.status in ("stale", "empty") and "лист 'лист'" in r.last_error



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


# --- Ключ в журнале и ворота обновления ------------------------------------

SECRET = "AIzaSyD-секретный-ключ"


def test_key_never_reaches_alert_or_log(monkeypatch, sent, caplog):
    """Исключение httpx несёт полный адрес вместе с ?key=…;
    прежний тест подсовывал исключение без ключа и проверял пустоту."""
    monkeypatch.setattr(sheet_index.settings, "sheets_api_key", SECRET)
    monkeypatch.setattr(sheet_index, "_api_failures", 0)

    def dead(key):
        raise _google_says(
            403, f"https://sheets.googleapis.com/v4/spreadsheets/x?key={SECRET}&fields=sheets"
        )

    monkeypatch.setattr(gsheets, "list_sheets_via_api", dead)
    monkeypatch.setattr(sheet_index, "_last_list", [])
    with caplog.at_level("WARNING"):
        _list()
        _list()
    assert len(sent) == 1
    assert SECRET not in sent[0]["message"] and SECRET not in caplog.text


def test_key_in_unexpected_error_does_not_leak(tmp_path, sheet, sent, monkeypatch, caplog):
    """То же для «служба споткнулась»: текст исключения идёт в err, тревогу и
    журнал."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)

    def boom(*args, **kwargs):
        raise RuntimeError(f"GET https://sheets.googleapis.com/?key={SECRET} упал")

    monkeypatch.setattr(refresher_mod, "build_index", boom)
    with caplog.at_level("ERROR"):
        assert r.refresh(today=TODAY) is False
    assert SECRET not in (r.last_error or "")
    assert all(SECRET not in m["message"] for m in sent)


def _shift_one_day(text: str, column: int) -> str:
    """«Вставить ячейки, сдвиг вправо» на блок — на строках первого дня листа."""
    import csv
    import io

    from whensclass.parser.csv_schedule import date_rows

    rows = read_csv(text)
    starts = sorted(date_rows([r[0] if r else "" for r in rows]).values())
    for i in range(starts[0] - 1, starts[1] - 1):
        rows[i][column:column] = [""] * 4
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    return buf.getvalue()


@pytest.mark.parametrize("gate", ["группы", "сдвиг", "объём"])
def test_refresher_gates_keep_the_previous_snapshot(
    tmp_path, sheet, sent, fixture_csv, monkeypatch, gate
):
    """Ворота проверялись как отдельные функции — убери их из
    Refresher, и тесты зелёные. Здесь — через заход: отказ, stale, тревога и
    прежний снимок на месте."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    before = store.snapshot
    if gate == "группы":
        # Два блока из пяти (две группы из шести) удалены целиком — треть.
        import csv
        import io

        rows = read_csv(fixture_csv)
        for row in rows:
            del row[6:14]
        buf = io.StringIO()
        csv.writer(buf, lineterminator="\n").writerows(rows)
        sheet["text"] = buf.getvalue()
        reason = "пропало 2 групп"
    elif gate == "сдвиг":
        sheet["text"] = _shift_one_day(fixture_csv, 6)
        reason = "сдвиг"
    else:
        from whensclass.parser.csv_schedule import Limits

        monkeypatch.setattr(refresher_mod, "_limits", lambda: Limits(3, 2, 10**6))
        reason = "ожидал не меньше 1000000"
    assert r.refresh(today=TODAY, force=True) is False
    assert r.status == "stale" and store.snapshot is before
    assert reason in r.last_error and sent, r.last_error


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
    sheet["text"] = cell_replace(fixture_csv, a_teacher(fixture_csv), "Новиков Н. Н.")
    assert r.refresh(today=TODAY, force=True) is True
    assert r.status == "ok" and r.failing_since is None
    assert any("снова обновляется" in m["message"] for m in sent)
    assert any("не записался" in m["message"] for m in sent)


def test_new_group_sheet_rejected_by_search_is_looked_at_again(tmp_path, monkeypatch):
    """Лист «групп», заведённый пустым, поиск
    отвергал, и заполненный позже служба узнавала только ночью."""
    from whensclass.sources.gsheets import SheetInfo

    monkeypatch.setattr(settings, "sheets_api_key", "ключ")
    books = [[SheetInfo(title="расписание групп 21.-26.09", gid="1", hidden=False)]]
    monkeypatch.setattr(sheet_index, "list_sheets", lambda: books[-1])
    monkeypatch.setattr(sheet_index, "candidates", lambda sheets, day: sheets)
    r = Refresher(SnapshotStore(tmp_path), tmp_path)
    calls = []
    monkeypatch.setattr(r, "refresh", lambda force=False: calls.append(force) or False)
    r.look_for_new_sheet()
    books.append(books[-1] + [SheetInfo(title="расписание групп 28.09-03.10", gid="2",
                                        hidden=False)])
    r.look_for_new_sheet()
    r.look_for_new_sheet()
    assert calls == [True, True], "пустой новый лист — посмотреть ещё раз"
    sheet_index.SheetIndex(tmp_path).remember(
        "расписание групп 28.09-03.10", "2", dt.date(2026, 9, 28), dt.date(2026, 10, 3)
    )
    r.look_for_new_sheet()
    r.look_for_new_sheet()
    assert calls == [True, True, True], "принятый — больше не трогаем"


def test_window_of_sheets_starts_on_monday_like_the_app(tmp_path, sheet, sent, monkeypatch):
    """В воскресенье на стыке листов набор
    считался от сегодня — в снимке оставался только будущий лист, и прожитая
    неделя пропадала с экрана. Приложение просит окно с понедельника."""
    asked = []

    def window(start, days, state_dir, deep=False):
        asked.append((start, days))
        return [("лист", "656498718")]

    monkeypatch.setattr(sheet_index, "resolve_window", window)
    r = Refresher(SnapshotStore(tmp_path), tmp_path)
    r.refresh(today=dt.date(2026, 9, 13))  # воскресенье
    assert asked[0] == (dt.date(2026, 9, 7), settings.window_days + 6)


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


def test_only_the_failing_sheet_is_archived_as_rejected(
    tmp_path, sent, fixture_csv, monkeypatch
):
    """Отказ одного листа окна клал «отвергнутыми»
    все листы с одной причиной — исправный текущий получал чужое «нашёл всего»."""
    texts = {"лист": fixture_csv, "следующий": fixture_csv.replace("Дисциплина", "Предмет")}
    monkeypatch.setattr(
        sheet_index, "resolve_window", lambda *a, **k: [("лист", "1"), ("следующий", "2")]
    )
    monkeypatch.setattr(gsheets, "fetch_sheet_csv", lambda gid=None, title=None: texts[title])
    monkeypatch.setattr(refresher_mod, "_limits", lambda: FIXTURE)
    r = Refresher(SnapshotStore(tmp_path), tmp_path)
    assert r.refresh(today=TODAY) is False
    assert not list((tmp_path / "history" / "1").glob("*-rejected*"))
    assert list((tmp_path / "history" / "2").glob("*-rejected.txt"))


def _list():
    """list_sheets при сбое API без прежнего списка честно бросает «не найден»."""
    try:
        return sheet_index.list_sheets()
    except sheet_index.SheetNotFound:
        return None


def test_failed_api_keeps_the_last_list_and_there_is_no_xlsx(monkeypatch, sent):
    """При сбое API служба качала книгу в xlsx —
    22 МБ под замком, — а читаемых листов там нет: gid xlsx не даёт. Теперь —
    прежний список от API, если он свежий, иначе «не найден»."""
    from whensclass.sources.gsheets import SheetInfo

    monkeypatch.setattr(sheet_index.settings, "sheets_api_key", "ключ")
    monkeypatch.setattr(sheet_index, "_last_list", [])
    state = {"ok": True}

    def api(key):
        if state["ok"]:
            return [SheetInfo(title="расписание групп", gid="1")]
        raise _google_says(503)

    monkeypatch.setattr(gsheets, "list_sheets_via_api", api)
    assert [s.gid for s in sheet_index.list_sheets()] == ["1"]
    state["ok"] = False
    assert [s.gid for s in sheet_index.list_sheets()] == ["1"], "прежний список"
    monkeypatch.setattr(sheet_index, "_last_list", [])
    with pytest.raises(sheet_index.SheetNotFound, match="не ответил"):
        sheet_index.list_sheets()
    monkeypatch.setattr(sheet_index.settings, "sheets_api_key", None)
    with pytest.raises(sheet_index.SheetNotFound, match="ключа Sheets API нет"):
        sheet_index.list_sheets()


def test_watch_baseline_survives_restart(tmp_path, monkeypatch):
    """Лист, заведённый, пока служба
    перезапускалась, первый взгляд после запуска клал в базовую линию и не
    искал — до ночи."""
    from whensclass.sources.gsheets import SheetInfo

    monkeypatch.setattr(settings, "sheets_api_key", "ключ")
    books = [[SheetInfo(title="расписание групп 21.-26.09", gid="1")]]
    monkeypatch.setattr(sheet_index, "list_sheets", lambda: books[-1])
    monkeypatch.setattr(sheet_index, "candidates", lambda sheets, day: sheets)
    first = Refresher(SnapshotStore(tmp_path), tmp_path)
    first.look_for_new_sheet()
    books.append(books[-1] + [SheetInfo(title="расписание групп 28.09-03.10", gid="2")])
    restarted = Refresher(SnapshotStore(tmp_path), tmp_path)
    calls = []
    monkeypatch.setattr(restarted, "refresh", lambda force=False: calls.append(force) or False)
    restarted.look_for_new_sheet()
    assert calls == [True]


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
    from whensclass.parser.csv_schedule import parse_export

    snapshot = parse_export(fixture_csv, "лист", "656498718", limits=FIXTURE)
    store = SnapshotStore(tmp_path)
    store.put(snapshot, dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc))
    loaded = SnapshotStore(tmp_path)
    assert loaded.load()
    assert loaded.snapshot == snapshot


def test_one_changed_sheet_of_two_rebuilds_the_whole_window(
    tmp_path, sent, fixture_csv, monkeypatch
):
    """Окно из двух листов, изменился один —
    снимок пересобирается из обоих."""
    later = fixture_csv
    for old, new in (("02.09.2026", "14.09.2026"), ("03.09.2026", "15.09.2026"),
                     ("04.09.2026", "16.09.2026"), ("05.09.2026", "17.09.2026"),
                     ("07.09.2026", "18.09.2026"), ("08.09.2026", "19.09.2026"),
                     ("09.09.2026", "21.09.2026"), ("10.09.2026", "22.09.2026"),
                     ("11.09.2026", "23.09.2026"), ("12.09.2026", "24.09.2026")):
        later = later.replace(old, new)
    import re as _re

    later = _re.sub(r"(\d\d\.09\.2026)\s+\w+", r"\1", later)
    texts = {"лист": fixture_csv, "следующий": later}
    monkeypatch.setattr(
        sheet_index, "resolve_window", lambda *a, **k: [("лист", "1"), ("следующий", "2")]
    )
    monkeypatch.setattr(gsheets, "fetch_sheet_csv", lambda gid=None, title=None: texts[title])
    monkeypatch.setattr(refresher_mod, "_limits", lambda: FIXTURE)
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    texts["лист"] = cell_replace(fixture_csv, a_teacher(fixture_csv), "Новиков Н. Н.")
    assert r.refresh(today=TODAY) is True
    days = set(store.snapshot.dates)
    assert dt.date(2026, 9, 2) in days and dt.date(2026, 9, 24) in days
