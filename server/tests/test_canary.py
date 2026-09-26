"""Канарейка (tools/check_source.py): тем же путём, что служба, и без паники
на каникулах. Третий аудит, прогон 2: М37, М42, М84, М88."""

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
    """М88: лист кончился 12.09, а сегодня 01.10 — каникулы: код 0 и тихая
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
    """М37: пороги — те же, что у службы; раньше зашитые FULL_SHEET давали на
    фикстуре «формат изменился», сколько бы порог ни опускали."""
    module, sent = canary
    monkeypatch.setattr(module, "_today", lambda: dt.date(2026, 9, 8))
    assert module.main() == 0 and sent == []


def test_certificate_close_to_expiry_is_an_alarm(canary, monkeypatch):
    """М53: хук перезагрузки nginx после продления ни разу не срабатывал;
    канарейка сверяет срок сертификата, который nginx отдаёт на деле."""
    module, sent = canary
    monkeypatch.setattr(module, "_today", lambda: dt.date(2026, 9, 8))
    monkeypatch.setattr(module, "cert_days_left", lambda host: 10)
    module.main()
    assert ("canary-cert", False) in sent
