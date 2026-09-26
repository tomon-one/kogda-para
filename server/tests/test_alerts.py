"""Тревога владельцу: уходит в ntfy, не повторяется, знает, когда починилось."""

import datetime as dt
import json

import pytest

from whensclass.config import settings
from whensclass.parser.csv_schedule import FIXTURE, parse_csv
from whensclass.service import alerts
from whensclass.service.refresher import Refresher
from whensclass.storage.snapshot_store import SnapshotStore


class FakeResponse:
    status_code = 200

    def raise_for_status(self):
        return None


class Sent:
    def __init__(self):
        self.bodies = []


@pytest.fixture
def sent(monkeypatch):
    box = Sent()

    class Client:
        def __init__(self, *args, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def post(self, url, json=None):
            box.bodies.append((url, json))
            return FakeResponse()

    monkeypatch.setattr(alerts.httpx, "Client", Client)
    monkeypatch.setattr(settings, "ntfy_topic", "тема-для-теста")
    alerts._last_sent.clear()
    return box


def test_notify_publishes_json_with_topic_in_body(sent):
    assert alerts.notify("format", "беда") is True
    url, body = sent.bodies[0]
    # Тема в теле, не в адресе: адрес попадает в текст исключений httpx.
    assert body["topic"] == "тема-для-теста" and "тема-для-теста" not in url
    assert body["message"] == "беда" and body["priority"] == 4


def test_same_kind_is_quiet_for_six_hours(sent):
    assert alerts.notify("format", "раз") is True
    assert alerts.notify("format", "два") is False
    assert alerts.notify("sheet", "другое") is True
    assert alerts.notify("format", "три", force=True) is True
    assert len(sent.bodies) == 3


def test_without_topic_nothing_is_sent(monkeypatch, sent):
    monkeypatch.setattr(settings, "ntfy_topic", None)
    assert alerts.notify("format", "беда") is False
    assert sent.bodies == []


def test_failure_state_survives_restart_and_recovery_reports_duration(tmp_path, sent, fixture_csv):
    store = SnapshotStore(tmp_path)
    store.put(parse_csv(fixture_csv, "фикстура", FIXTURE),
              dt.datetime(2026, 9, 11, 3, 40, tzinfo=dt.timezone.utc))
    refresher = Refresher(store, tmp_path)

    refresher._fail("формат таблицы изменился: повторный заголовок", kind="format")
    assert refresher.status == "stale"
    assert refresher.failing_since is not None
    assert len(sent.bodies) == 1, "формат не самолечится — сказать сразу"
    saved = json.loads((tmp_path / "failing.json").read_text("utf-8"))
    assert saved["alerted"] is True and saved["kind"] == "format"

    # Второй сбой того же рода — молчим, состояние держится.
    refresher._fail("формат таблицы изменился: снова", kind="format")
    assert len(sent.bodies) == 1

    # Перезапуск: состояние сбоя поднимается с диска.
    again = Refresher(store, tmp_path)
    assert again.failing_since == refresher.failing_since
    assert again.last_error.startswith("формат таблицы")

    again._recovered()
    assert again.failing_since is None and again.last_error is None
    assert not (tmp_path / "failing.json").exists()
    url, body = sent.bodies[-1]
    assert "снова обновляется" in body["message"] and body["priority"] == 3
    # Лежали минуты — говорим в минутах: «лежало 0.0 ч» было в учебной
    # тревоге 14 сентября 2026.
    assert " мин, с " in body["message"]


def test_recovery_duration_is_human():
    from whensclass.service.refresher import _lying

    assert _lying(dt.timedelta(minutes=2)) == "2 мин"
    assert _lying(dt.timedelta(minutes=59, seconds=59)) == "59 мин"
    assert _lying(dt.timedelta(hours=1)) == "1,0 ч"
    assert _lying(dt.timedelta(hours=26, minutes=30)) == "26,5 ч"


def test_network_blip_is_silent_until_half_an_hour(tmp_path, sent, fixture_csv):
    store = SnapshotStore(tmp_path)
    store.put(parse_csv(fixture_csv, "фикстура", FIXTURE),
              dt.datetime(2026, 9, 11, 3, 40, tzinfo=dt.timezone.utc))
    refresher = Refresher(store, tmp_path)
    refresher._fail("таблица не прочиталась: ReadTimeout", kind="fetch",
                    public="таблица не прочиталась: ReadTimeout")
    assert sent.bodies == [], "один чих Google — не повод будить"
    assert refresher.last_error == "таблица не прочиталась: ReadTimeout"
    # Полчаса спустя — уже повод.
    refresher.failing_since -= dt.timedelta(minutes=31)
    refresher._fetch_since -= dt.timedelta(minutes=31)
    refresher._fail("таблица не прочиталась: ReadTimeout", kind="fetch",
                    public="таблица не прочиталась: ReadTimeout")
    assert len(sent.bodies) == 1
    # Починилось без тревоги — и «починилось» не шлём; с тревогой — шлём.
    refresher._recovered()
    assert len(sent.bodies) == 2
