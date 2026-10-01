"""Ключ Sheets API и список листов: тревога о мёртвом ключе, ключ не попадает
в журнал и тревогу, прежний список при сбое API, слежка за книгой.
"""

import datetime as dt

import pytest

from test_refresh_failures import TODAY
from whensclass.config import settings
from whensclass.service import refresher as refresher_mod
from whensclass.service.refresher import Refresher
from whensclass.sources import gsheets, sheet_index, sheet_memory
from whensclass.storage.snapshot_store import SnapshotStore


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
    """Полчаса без связи с Google — не тревога «Проверить ключ»: отказ ключа —
    только ответ Google о ключе или квоте. Когда ключ снова работает, об этом
    говорится."""
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


# --- Ключ в журнале ---------------------------------------------------------


SECRET = "AIzaSyD-секретный-ключ"


def test_key_never_reaches_alert_or_log(monkeypatch, sent, caplog):
    """Исключение httpx несёт полный адрес вместе с ?key=…: подсовываем
    исключение именно с ключом."""
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


# --- Список листов и слежка за книгой ---------------------------------------


def _list():
    """list_sheets при сбое API без прежнего списка бросает «не найден», а при
    обрыве сети — саму сетевую ошибку."""
    import httpx

    try:
        return sheet_index.list_sheets()
    except (sheet_index.SheetNotFound, httpx.TransportError):
        return None


def test_failed_api_keeps_the_last_list_and_there_is_no_xlsx(monkeypatch, sent):
    """При сбое API — прежний список от API, если он свежий, иначе «не найден»;
    книгу в xlsx не качаем: gid она не даёт."""
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


def test_new_group_sheet_rejected_by_search_is_looked_at_again(tmp_path, monkeypatch):
    """Лист «групп», заведённый пустым, поиск отвергает, а заполненный позже
    слежка перепроверяет, а не ждёт ночи."""
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
    sheet_memory.SheetIndex(tmp_path).remember(
        "расписание групп 28.09-03.10", "2", dt.date(2026, 9, 28), dt.date(2026, 10, 3)
    )
    r.look_for_new_sheet()
    r.look_for_new_sheet()
    assert calls == [True, True, True], "принятый — больше не трогаем"


def test_watch_baseline_survives_restart(tmp_path, monkeypatch):
    """Лист, заведённый, пока служба перезапускалась, — новый для первого
    взгляда после запуска: базовая линия на диске."""
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
