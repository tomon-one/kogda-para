"""Запасной путь к Google через exit: когда идёт, что отдаёт наверх и что
уходит на exit."""

from __future__ import annotations

import subprocess

import httpx
import pytest

from whensclass.config import settings
from whensclass.sources import gsheets


def _google(handler):
    """_client(), который вместо Google зовёт handler."""
    return lambda: httpx.Client(transport=httpx.MockTransport(handler), follow_redirects=True)


def _unreachable(request):
    raise httpx.ConnectError("нет маршрута", request=request)


class _Ssh:
    """Подмена subprocess.run: запоминает вызов, отвечает заданным."""

    def __init__(self, stdout: bytes = b"", returncode: int = 0, stderr: bytes = b""):
        self.stdout, self.returncode, self.stderr = stdout, returncode, stderr
        self.calls: list[tuple[list[str], bytes]] = []

    def __call__(self, command, input=None, capture_output=True, timeout=None):
        self.calls.append((command, input))
        return subprocess.CompletedProcess(command, self.returncode, self.stdout, self.stderr)


@pytest.fixture
def exit_on(monkeypatch):
    monkeypatch.setattr(settings, "exit_ssh", "wc-fetch@exit.example:2222")
    ssh = _Ssh(stdout="﻿Дата,ИСП-924/1\n".encode())
    monkeypatch.setattr(gsheets.subprocess, "run", ssh)
    return ssh


def test_google_answers_directly_exit_is_not_used(monkeypatch, exit_on):
    monkeypatch.setattr(
        gsheets, "_client", _google(lambda r: httpx.Response(200, text="a,b\n", headers={"content-type": "text/csv"})),
    )
    assert gsheets.fetch_sheet_csv("123") == "a,b\n"
    assert exit_on.calls == []


def test_google_unreachable_sheet_comes_through_exit(monkeypatch, exit_on):
    monkeypatch.setattr(gsheets, "_client", _google(_unreachable))
    assert gsheets.fetch_sheet_csv("656498718") == "Дата,ИСП-924/1\n"
    command, stdin = exit_on.calls[0]
    assert command[-3:] == ["wc-fetch@exit.example", "csv", "656498718"]
    assert ["-p", "2222"] == command[command.index("-p"):command.index("-p") + 2]
    for option in ("BatchMode=yes", "StrictHostKeyChecking=yes", "IdentitiesOnly=yes"):
        assert option in command
    assert stdin == b""


def test_without_exit_the_network_error_goes_up(monkeypatch):
    monkeypatch.setattr(settings, "exit_ssh", None)
    monkeypatch.setattr(gsheets, "_client", _google(_unreachable))
    with pytest.raises(httpx.ConnectError):
        gsheets.fetch_sheet_csv("1")


def test_google_error_code_is_not_a_reason_to_go_around(monkeypatch, exit_on):
    # Закрытую таблицу или мёртвый ключ exit не починит.
    monkeypatch.setattr(gsheets, "_client", _google(lambda r: httpx.Response(403, text="нет")))
    with pytest.raises(httpx.HTTPStatusError):
        gsheets.fetch_sheet_csv("1")
    assert exit_on.calls == []


def test_exit_failed_too_the_original_error_goes_up(monkeypatch, exit_on):
    exit_on.returncode, exit_on.stderr = 255, b"Connection refused"
    monkeypatch.setattr(gsheets, "_client", _google(_unreachable))
    with pytest.raises(httpx.ConnectError):
        gsheets.fetch_sheet_csv("1")


def test_page_instead_of_csv_through_exit_is_a_closed_sheet(monkeypatch, exit_on):
    exit_on.stdout = b"<!DOCTYPE html><html>sign in</html>"
    monkeypatch.setattr(gsheets, "_client", _google(_unreachable))
    with pytest.raises(gsheets.SheetClosed):
        gsheets.fetch_sheet_csv("1")


def test_api_key_goes_to_exit_on_stdin_not_in_arguments(monkeypatch, exit_on):
    exit_on.stdout = b'{"sheets":[{"properties":{"sheetId":656498718,"title":"groups 22.09","hidden":false}}]}'
    monkeypatch.setattr(gsheets, "_client", _google(_unreachable))
    sheets = gsheets.list_sheets_via_api("секретный-ключ")
    assert sheets == [gsheets.SheetInfo(title="groups 22.09", gid="656498718", hidden=False)]
    command, stdin = exit_on.calls[0]
    assert command[-1] == "sheets"
    assert not any("секретный-ключ" in part for part in command)
    assert stdin == "секретный-ключ\n".encode()


def test_google_error_through_exit_keeps_its_code(monkeypatch, exit_on):
    """curl -f на exit: Google ответил 400 — это мёртвый gid, а не сеть.
    Раньше через exit любой отказ Google выглядел обрывом связи."""
    exit_on.returncode, exit_on.stderr = 22, b"curl: (22) The requested URL returned error: 400"
    monkeypatch.setattr(gsheets, "_client", _google(_unreachable))
    with pytest.raises(httpx.HTTPStatusError) as caught:
        gsheets.fetch_sheet_csv("1")
    assert caught.value.response.status_code == 400


def test_gid_that_is_not_a_number_is_dropped(monkeypatch, exit_on):
    exit_on.stdout = b'{"sheets":[{"properties":{"sheetId":"../../x","title":"groups"}}]}'
    monkeypatch.setattr(gsheets, "_client", _google(_unreachable))
    assert gsheets.list_sheets_via_api("ключ") == [gsheets.SheetInfo(title="groups", gid=None)]


def test_exit_setting_without_port_does_not_call_ssh(monkeypatch, exit_on):
    monkeypatch.setattr(settings, "exit_ssh", "wc-fetch@exit.example")
    monkeypatch.setattr(gsheets, "_client", _google(_unreachable))
    with pytest.raises(httpx.ConnectError):
        gsheets.fetch_sheet_csv("1")
    assert exit_on.calls == []
