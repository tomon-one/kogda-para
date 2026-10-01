"""Канарейка (tools/check_source.py): тем же путём, что служба, и без паники
на каникулах."""

import datetime as dt
import importlib.util
import pathlib

import pytest

from whensclass.config import settings
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.service import alerts

ROOT = pathlib.Path(__file__).resolve().parents[2]


@pytest.fixture
def canary(monkeypatch, tmp_path, fixture_csv):
    spec = importlib.util.spec_from_file_location("check_source", ROOT / "tools/check_source.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    sent = []
    monkeypatch.setattr(settings, "ntfy_topic", "тема")
    monkeypatch.setattr(
        alerts, "notify",
        lambda kind, text, force=False, good=False, quiet=False, window=0: sent.append(
            (kind, quiet)
        ) or True,
    )
    monkeypatch.setattr(module.sheet_index, "resolve_for", lambda *a, **k: ("лист", "1"))
    monkeypatch.setattr(module.gsheets, "fetch_sheet_csv", lambda gid=None, title=None: fixture_csv)
    monkeypatch.setattr(module, "_limits", lambda: FIXTURE)
    monkeypatch.setattr(module, "cert_days_left", lambda host: 60)
    monkeypatch.setattr("sys.argv", ["check_source.py", "--quiet", "--state-dir", str(tmp_path)])
    return module, sent


def test_holidays_are_one_quiet_note_not_a_daily_alarm(canary, monkeypatch):
    """Лист кончился 12.09, а сегодня 01.10 — каникулы: код 0 и тихая
    тревога, а не звонок каждое утро и failed юнита."""
    module, sent = canary
    monkeypatch.setattr(module, "_today", lambda: dt.date(2026, 10, 1))
    assert module.main() == 0
    assert sent == [("canary-holiday", True)]


def test_school_day_past_the_sheet_is_still_an_alarm(canary, monkeypatch):
    module, sent = canary
    monkeypatch.setattr(module, "_today", lambda: dt.date(2026, 9, 14))
    assert module.main() == 1
    assert sent == [("canary-coverage", False)]


def test_limits_come_from_the_service_settings(canary, monkeypatch):
    """Пороги — те же, что у службы, а не зашитые FULL_SHEET: рычаги настроек
    должны доходить и до канарейки."""
    module, sent = canary
    monkeypatch.setattr(module, "_today", lambda: dt.date(2026, 9, 8))
    assert module.main() == 0 and sent == []


def test_certificate_close_to_expiry_is_an_alarm(canary, monkeypatch):
    """Канарейка сверяет срок сертификата, который nginx отдаёт на деле, а не
    файла: хук перезагрузки после продления может и не сработать."""
    module, sent = canary
    monkeypatch.setattr(module, "_today", lambda: dt.date(2026, 9, 8))
    monkeypatch.setattr(module, "cert_days_left", lambda host: 10)
    module.main()
    assert ("canary-cert", False) in sent


def test_wrong_certificate_is_an_alarm_but_network_is_not(canary, monkeypatch):
    """Чужой или самоподписанный сертификат на домене — тревога, а не только
    строка в журнале. Таймаут — не беда канарейки."""
    import ssl

    module, sent = canary
    monkeypatch.setattr(module, "_today", lambda: dt.date(2026, 9, 8))

    def bad(host):
        raise ssl.SSLCertVerificationError("self-signed certificate")

    monkeypatch.setattr(module, "cert_days_left", bad)
    module.main()
    assert ("canary-cert-bad", False) in sent
    sent.clear()

    def slow(host):
        raise TimeoutError("timed out")

    monkeypatch.setattr(module, "cert_days_left", slow)
    module.main()
    assert not any(kind.startswith("canary-cert") for kind, _ in sent)


def test_closed_table_during_the_search_is_an_alarm_not_a_crash(canary, monkeypatch):
    """Закрытая таблица при поиске листа — тревога, а не трассировка."""
    module, sent = canary

    def closed(*args, **kwargs):
        raise module.gsheets.SheetClosed("страница входа")

    monkeypatch.setattr(module.sheet_index, "resolve_for", closed)
    monkeypatch.setattr(module, "_today", lambda: dt.date(2026, 9, 8))
    assert module.main() == 2
    assert sent == [("canary-closed", False)]
