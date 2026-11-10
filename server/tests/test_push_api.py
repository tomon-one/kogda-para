"""Уведомления сайта: API подписки, очереди служб рассылки, предел размера сообщения."""

import datetime as dt
import threading

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from test_push import Recorder, _body, _vapid

from whensclass.push import service


@pytest.fixture
def api(tmp_path, fixture_csv, monkeypatch):
    from test_api import FakeStore

    from whensclass.api import routes
    from whensclass.parser.csv_schedule import FIXTURE
    from whensclass.parser.export import parse_csv

    monkeypatch.setattr(routes, "_today", lambda: dt.date(2026, 9, 8))
    app = FastAPI()
    app.include_router(routes.router)
    app.state.store = FakeStore(parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE))
    # Recorder, а не Push: тест не должен слать в настоящую службу рассылки.
    app.state.push = Recorder(tmp_path, _vapid())
    return TestClient(app), app.state.push


def test_api_subscribe_and_remove(api):
    client, push = api
    key = client.get("/v1/push/key").json()["key"]
    assert key == push.vapid.public
    assert client.post("/v1/push/subscribe", json=_body(id="isp-924-2")).status_code == 204
    assert push.count() == 1
    assert client.post("/v1/push/subscribe", json=_body(id="net-takoy")).status_code == 404
    assert client.post("/v1/push/subscribe", json=_body(endpoint="https://10.0.0.1/x")).status_code == 422
    assert client.post("/v1/push/subscribe", content=b"{").status_code == 422
    assert client.post("/v1/push/remove", json={"endpoint": "https://web.push.apple.com/abc"}
                       ).status_code == 204
    assert push.count() == 0


def test_api_without_key_says_so(tmp_path, api):
    client, push = api
    push.vapid = None
    assert client.get("/v1/push/key").status_code == 404
    assert client.post("/v1/push/subscribe", json=_body()).status_code == 404


def test_browser_moved_subscription_keeps_the_choice(api):
    client, push = api
    assert client.post("/v1/push/subscribe", json=_body(id="isp-924-2", remind=45)).status_code == 204
    fresh = _body(endpoint="https://web.push.apple.com/new")
    body = {"old": "https://web.push.apple.com/abc", "endpoint": fresh["endpoint"], "keys": fresh["keys"]}
    assert client.post("/v1/push/move", json=body).status_code == 204
    assert list(push._subs) == ["https://web.push.apple.com/new"]
    moved = push._subs["https://web.push.apple.com/new"]
    assert moved["id"] == "isp-924-2" and moved["remind"] == 45 and moved["p256dh"] == fresh["keys"]["p256dh"]
    # Чужой адрес вместо нового — отказ; неизвестный прежний — 404 (сервис-
    # воркер по нему сбросит отметку, и страница перешлёт подписку) и
    # ничего не меняет.
    bad = {**body, "old": "https://web.push.apple.com/new", "endpoint": "https://10.0.0.1/x"}
    assert client.post("/v1/push/move", json=bad).status_code == 422
    assert client.post("/v1/push/move", json={**body, "old": "https://web.push.apple.com/none"}
                       ).status_code == 404
    assert list(push._subs) == ["https://web.push.apple.com/new"]


def test_service_wires_push_to_the_store_and_the_refresher(tmp_path):
    """Рассылка знает прежних преподавателей (почасовику без пар — «убрали»)
    и зовётся после каждого нового снимка."""
    from whensclass.main import build_state

    store, refresher, push = build_state(tmp_path)
    assert push.known_teacher == store.known_teacher
    assert refresher.on_update == push.after_refresh


def test_each_push_service_has_its_own_queue(tmp_path, monkeypatch):
    """Медленная или лежащая служба рассылки не держит доставку остальных."""
    from whensclass.push.delivery import family

    seen = {}
    done = threading.Event()

    def send(self, job, attempt=0):
        seen[family(job.sub["endpoint"])] = threading.current_thread().name
        if len(seen) == 2:
            done.set()

    monkeypatch.setattr(service.Push, "_send", send)
    push = service.Push(tmp_path, _vapid())
    for endpoint in ("https://web.push.apple.com/a", "https://fcm.googleapis.com/fcm/send/b"):
        push._dispatch([service.Job({"endpoint": endpoint}, {}, 60)])
    assert done.wait(5)
    assert seen["apple"].startswith("push-apple") and seen["google"].startswith("push-google")


def test_reminder_with_a_huge_link_still_fits_the_message():
    """Ссылка «Войти» в тысячи знаков или название из четырёхбайтовых букв не
    делают напоминание больше, чем примет служба рассылки: сначала уходит
    ссылка, потом строки короче."""
    import json

    from whensclass.push.messages import MAX_PAYLOAD
    from whensclass.push.service import _reminder

    sub = {"site": "main", "kind": "group", "id": "isp-924-2"}
    alarm = {"number": 2, "title": "𝐌" * 300, "text": "𝐓" * 300, "subject": "𝐒" * 300,
             "join": "https://my.mts-link.ru/j/" + "a" * 3700}
    data = _reminder(sub, alarm, 1, 2)
    assert len(json.dumps(data, ensure_ascii=False).encode("utf-8")) <= MAX_PAYLOAD
    short = _reminder(sub, {**alarm, "title": "Физика", "text": "каб. 369", "subject": "Физика",
                            "join": "https://my.mts-link.ru/j/1"}, 1, 2)
    assert short["notification"]["data"]["join"] == "https://my.mts-link.ru/j/1"


def test_rare_failures_of_a_push_service_add_up_across_windows(tmp_path, monkeypatch, sent):
    """Подписчиков у службы единицы — по отказу в окно: тревога всё равно
    приходит, когда отказы идут подряд, а доставка снимает счёт."""
    push = Recorder(tmp_path, _vapid())
    clock = [1000.0]
    monkeypatch.setattr(service.time, "monotonic", lambda: clock[0])
    for _ in range(service.ALARM_MIN_FAILS):
        push._stats = {"Apple": [0, 1, "403 BadJwtToken"]}
        push._checked = -1e9
        push._check(None, None, dt.date(2026, 9, 8), fresh=False)
    assert any("Apple" in b["message"] for b in sent)
    push._stats = {"Apple": [1, 0, None]}
    push._checked = -1e9
    push._check(None, None, dt.date(2026, 9, 8), fresh=False)
    assert push._fail_streak["Apple"] == 0


def test_line_break_in_a_group_name_does_not_shift_lines_against_days():
    """Перевод строки в имени группы не даёт лишних строк сервис-воркеру."""
    from whensclass.push.messages import changes_message

    out = changes_message({"site": "main", "kind": "teacher", "id": "t"},
                          [["2026-10-20", "убрали 2 пару (ГД\n-1124)"], ["2026-10-21", "отменили 1 пару"]])
    body = out["notification"]["body"].split("\n")
    assert len(body) == len(out["notification"]["data"]["days"]) == 2
