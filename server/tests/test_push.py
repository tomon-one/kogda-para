"""Уведомления сайта: подписки, служба целиком, API.

Шифрование — test_webpush.py, правила изменений и напоминаний —
test_push_changes.py, рассылка — test_push_delivery.py.
"""

import dataclasses
import datetime as dt
import threading

import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI
from fastapi.testclient import TestClient

from whensclass.push import changes, service, subscription, webpush
from whensclass.push.webpush import b64encode


# --- подписки ------------------------------------------------------------------


@pytest.mark.parametrize("endpoint, ok", [
    ("https://web.push.apple.com/QGuQ/abc", True),
    ("https://fcm.googleapis.com/fcm/send/abc:APA91", True),
    ("https://updates.push.services.mozilla.com/wpush/v2/gAAA", True),
    ("https://wns2-db5p.notify.windows.com/w/?token=BQYAAA", True),
    ("http://web.push.apple.com/abc", False),
    ("https://web.push.apple.com:8443/abc", False),
    ("https://web.push.apple.com.evil.example/abc", False),
    ("https://user:pw@web.push.apple.com/abc", False),
    ("https://127.0.0.1/abc", False),
    ("https://localhost/abc", False),
    ("https://kogda-para-nsk.ru/v1/meta", False),
    ("https://web.push.apple.com/" + "a" * 1100, False),
    (None, False),
])
def test_only_browser_push_services(endpoint, ok):
    assert subscription.endpoint_allowed(endpoint) is ok


def _keys() -> dict:
    ua = ec.generate_private_key(ec.SECP256R1())
    return {
        "p256dh": b64encode(ua.public_key().public_bytes(
            webpush.Encoding.X962, webpush.PublicFormat.UncompressedPoint)),
        "auth": b64encode(b"0123456789abcdef"),
    }


def _body(**extra) -> dict:
    return {"endpoint": "https://web.push.apple.com/abc", "keys": _keys(),
            "kind": "group", "id": "isp-924-1", "changes": True, "remind": 20, **extra}


def test_subscription_parsed_and_checked():
    sub = subscription.parse_subscription(_body())
    assert sub["kind"] == "group" and sub["remind"] == 20 and sub["changes"] is True
    for bad in (
        _body(kind="room"), _body(id=""), _body(remind=5), _body(remind=241), _body(remind=True),
        _body(changes="да"), _body(keys={"p256dh": "AAAA", "auth": "AAAA"}),
        _body(endpoint="https://example.com/x"), [], "текст",
    ):
        with pytest.raises(subscription.BadSubscription):
            subscription.parse_subscription(bad)


def test_subscriptions_survive_restart_and_turn_off(tmp_path):
    push = service.Push(tmp_path, None)
    today = dt.date(2026, 9, 29)
    assert push.subscribe(subscription.parse_subscription(_body()), today)
    again = service.Push(tmp_path, None)
    assert again.count() == 1
    # Ни изменений, ни напоминаний — подписка стирается, а не висит пустой.
    assert again.subscribe(
        subscription.parse_subscription(_body(changes=False, remind=0)), today
    )
    assert service.Push(tmp_path, None).count() == 0


# --- служба целиком ------------------------------------------------------------------


class Recorder(service.Push):
    def __init__(self, path, vapid):
        super().__init__(path, vapid)
        self.sent = []

    def _dispatch(self, jobs):
        self.sent.extend(jobs)


def _vapid() -> webpush.Vapid:
    key = ec.generate_private_key(ec.SECP256R1())
    return webpush.Vapid(b64encode(key.private_numbers().private_value.to_bytes(32, "big")),
                         "https://kogda-para-nsk.ru")


@pytest.fixture
def snapshots(fixture_csv):
    from whensclass.domain.teachers import build_index
    from whensclass.parser.csv_schedule import FIXTURE
    from whensclass.parser.export import parse_csv

    before = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    after = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    day = dt.date(2026, 9, 8)
    lessons = after.schedule["isp-924-2"][day]
    first = lessons[0]
    lessons[0] = dataclasses.replace(first, cancelled=True)
    return before, build_index(before), after, build_index(after), day, first


def test_new_subscription_gets_a_hello_once(tmp_path):
    push = Recorder(tmp_path, _vapid())
    day = dt.date(2026, 9, 29)
    push.subscribe(subscription.parse_subscription(_body()), day)
    push.subscribe(subscription.parse_subscription(_body(remind=30)), day)
    assert [j.message["notification"]["data"]["t"] for j in push.sent] == ["hello"]
    note = push.sent[0].message["notification"]
    assert note["title"] == "Уведомления включены"
    assert note["body"] == (
        "Сюда будут приходить отмены и замены на сегодня и завтра и напоминания о паре."
    )
    # Декларативный формат Apple: нажатие ведёт на тот сайт, что подписан.
    assert push.sent[0].message["web_push"] == 8030 and push.sent[0].message["mutable"] is True
    assert note["navigate"] == "https://kogda-para-nsk.ru/"


def test_tested_site_is_opened_by_its_notifications(tmp_path):
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(site="tested")), dt.date(2026, 9, 29))
    assert push.sent[0].message["notification"]["navigate"] == "https://kogda-para-nsk.ru/tested/"
    with pytest.raises(subscription.BadSubscription):
        subscription.parse_subscription(_body(site="evil"))


def test_after_refresh_tells_only_subscribers_of_that_group(tmp_path, snapshots):
    before, bt, after, at, day, first = snapshots
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-2")), day)
    push.subscribe(subscription.parse_subscription(
        _body(id="isp-924-1", endpoint="https://web.push.apple.com/other")), day)
    push.subscribe(subscription.parse_subscription(
        _body(id="isp-924-2", endpoint="https://web.push.apple.com/quiet", changes=False)), day)
    push.sent.clear()
    push.after_refresh(before, bt, after, at, day)
    assert len(push.sent) == 1
    job = push.sent[0]
    note = job.message["notification"]
    assert job.sub["endpoint"] == "https://web.push.apple.com/abc"
    assert note["data"]["t"] == "changes" and note["title"] == "Расписание изменилось"
    assert note["body"] == f"вт, 8 сентября: отменили {first.number} пару: {first.subject}"
    assert note["data"]["days"] == ["2026-09-08"]


def test_reminder_goes_once_at_its_minute(tmp_path, snapshots):
    _, _, after, at, day, _ = snapshots
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-2", changes=False, remind=20)), day)
    push.sent.clear()
    payload = service._payload(after, at, ("group", "isp-924-2"), day)
    first = changes.reminders(payload, 20, day)[0]
    push.remind(after, at, now=first["at"] - dt.timedelta(minutes=1))
    assert push.sent == []
    push.remind(after, at, now=first["at"])
    push.remind(after, at, now=first["at"])
    assert len(push.sent) == 1
    job = push.sent[0]
    note = job.message["notification"]
    assert note["data"]["t"] == "lesson" and note["title"] == first["title"]
    assert job.ttl == 20 * 60
    # Позже начала пары повторять незачем.
    assert job.deadline == note["data"]["start"] / 1000


def test_without_key_nothing_is_sent(tmp_path, snapshots):
    before, bt, after, at, day, _ = snapshots
    push = Recorder(tmp_path, None)
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-2")), day)
    push.sent.clear()
    push.after_refresh(before, bt, after, at, day)
    assert push.sent == []


def test_missed_reminder_minute_is_caught_up_once(tmp_path, snapshots):
    """Выкладка в минуту напоминания его не теряет; догонялка после
    перезапуска не повторяет ушедшее — отметки на диске."""
    _, _, after, at, day, _ = snapshots
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-2", changes=False, remind=20)), day)
    push.sent.clear()
    first = changes.reminders(service._payload(after, at, ("group", "isp-924-2"), day), 20, day)[0]
    push.remind(after, at, now=first["at"] + dt.timedelta(minutes=3))
    assert len(push.sent) == 1
    again = Recorder(tmp_path, push.vapid)
    again.remind(after, at, now=first["at"] + dt.timedelta(minutes=4))
    assert again.sent == []
    # После начала пары не догоняется.
    late = Recorder(tmp_path / "другой", push.vapid)
    late._subs = dict(push._subs)
    late.remind(after, at, now=first["start"] + dt.timedelta(minutes=1))
    assert late.sent == []


def test_freeze_stops_reminders_and_changes(tmp_path, snapshots, monkeypatch):
    """Заморозка — снимок заведомо чужой: напоминания по нему не шлются."""
    before, bt, after, at, day, _ = snapshots
    monkeypatch.setattr(service.settings, "freeze", True)
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-2", remind=20)), day)
    push.sent.clear()
    first = changes.reminders(service._payload(after, at, ("group", "isp-924-2"), day), 20, day)[0]
    push.remind(after, at, now=first["at"])
    push.after_refresh(before, bt, after, at, day)
    assert push.sent == []


def test_one_failing_subject_does_not_silence_the_rest(tmp_path, snapshots, monkeypatch):
    """Сбой подсчёта у одной группы не роняет сводку остальным."""
    before, bt, after, at, day, first = snapshots
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-2")), day)
    push.subscribe(subscription.parse_subscription(
        _body(id="isp-924-1", endpoint="https://web.push.apple.com/other")), day)
    push.sent.clear()
    real = changes.change_lines

    def flaky(old, new, today, same=False):
        if new.get("g") == "isp-924-1":
            raise ValueError("Invalid IPv6 URL")
        return real(old, new, today)

    monkeypatch.setattr(changes, "change_lines", flaky)
    push.after_refresh(before, bt, after, at, day)
    assert [j.sub["id"] for j in push.sent] == ["isp-924-2"]


def test_gone_group_gets_one_notice_then_is_dropped(tmp_path, snapshots, monkeypatch):
    """Группу переименовали: через час — «выберите заново», через две недели —
    запись стёрта; вернулась группа — всё как было."""
    _, _, after, at, day, _ = snapshots
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-9", remind=20)), day)
    push.sent.clear()
    clock = [1000.0]
    monkeypatch.setattr(service.time, "monotonic", lambda: clock[0])
    push._checked = -1e9
    push._check(after, at, day, fresh=True)
    assert push.sent == []
    clock[0] += service.GONE_NOTICE_AFTER + service.CHECK_EVERY
    push._check(after, at, day, fresh=True)
    assert [j.message["notification"]["title"] for j in push.sent] == ["Группы больше нет в таблице"]
    # Служба уже ждала час — страница не ждёт своего (main.js, ?gone=1).
    assert push.sent[0].message["notification"]["navigate"].endswith("/?gone=1")
    clock[0] += service.CHECK_EVERY
    push._check(after, at, day, fresh=True)
    assert len(push.sent) == 1, "одно уведомление, не каждые десять минут"
    # Страница пересылает подписку раз в сутки — отметка о пропаже остаётся,
    # и «выберите заново» не приходит снова.
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-9", remind=20)), day)
    clock[0] += service.CHECK_EVERY
    push._check(after, at, day, fresh=True)
    assert len(push.sent) == 1, "пересылка не повторяет «выберите заново»"
    clock[0] += service.CHECK_EVERY
    push._check(after, at, day + dt.timedelta(days=service.GONE_DROP_DAYS), fresh=True)
    assert push.count() == 0


def test_teacher_without_lessons_now_still_gets_changes(tmp_path, snapshots):
    """У почасовика пару отдали замене — в новом снимке его нет, но он был в
    прежних: и приложение, и сайт говорят «убрали»."""
    before, bt, after, at, day, _ = snapshots
    tid = next(iter(bt.names))
    known = {tid: bt.names[tid]}
    for gid, by_date in after.schedule.items():
        for d, lessons in by_date.items():
            by_date[d] = [dataclasses.replace(x, teachers=tuple(t for t in x.teachers
                                                                  if t != bt.names[tid]))
                          for x in lessons]
    from whensclass.domain.teachers import build_index

    after_index = build_index(after)
    assert tid not in after_index.names
    new = service._payload(after, after_index, ("teacher", tid), day, known.get)
    assert new is not None and all(not d["l"] for d in new["days"])


def test_shift_of_two_neighbours_is_not_sent_to_them(tmp_path, snapshots, monkeypatch):
    """Сдвиг у двух соседних групп отказом не ловится (порог — три): им —
    ничего, а не уведомление с парами соседа, владельцу — тревога; остальным
    — как обычно."""
    before, bt, after, at, day, first = snapshots
    import copy

    shifted = copy.deepcopy(before)
    order = []
    for g in shifted.groups:
        if g.id not in order:
            order.append(g.id)
    a, b, c = order[1], order[2], order[3]
    shifted.schedule[b][day] = list(before.schedule[a][day])
    shifted.schedule[c][day] = list(before.schedule[b][day])
    said = []
    monkeypatch.setattr(service.alerts, "notify", lambda kind, text, **k: said.append(kind))
    push = Recorder(tmp_path, _vapid())
    for gid in (b, c):
        push.subscribe(subscription.parse_subscription(
            _body(id=gid, endpoint=f"https://web.push.apple.com/{gid}")), day)
    push.sent.clear()
    from whensclass.domain.teachers import build_index

    push.after_refresh(before, bt, shifted, build_index(shifted), day)
    assert push.sent == [] and said == ["push-shift"]


def test_vertical_shift_of_a_column_is_not_sent_to_it(tmp_path, snapshots, monkeypatch):
    """Пары колонки съехали по вертикали (номера под вопросом), а лист принят:
    её подписчикам — ничего, владельцу — тревога (vertical_groups в _suspects)."""
    before, bt, after, at, day, first = snapshots
    import copy

    gid = "isp-924-2"
    shifted = copy.deepcopy(before)
    for d, lessons in before.schedule[gid].items():
        shifted.schedule[gid][d] = [dataclasses.replace(x, number=x.number + 1) for x in lessons]
    said = []
    monkeypatch.setattr(service.alerts, "notify", lambda kind, text, **k: said.append(kind))
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id=gid)), day)
    push.sent.clear()
    from whensclass.domain.teachers import build_index

    push.after_refresh(before, bt, shifted, build_index(shifted), day)
    assert push.sent == [] and said == ["push-shift"]


# --- API ----------------------------------------------------------------------------


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
