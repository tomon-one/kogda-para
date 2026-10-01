"""Рассылка уведомлений сайта: размер сообщения, повторы, отказы служб
рассылки, снятие подписок, которые не доставляются."""

import datetime as dt
import json

import pytest

from test_push import _body, _vapid
from whensclass.push import delivery, messages, service, subscription, webpush
from whensclass.push.webpush import b64encode


def test_changes_fit_the_push_size_first_kept():
    """Не влезает — прочь хвост: строки идут по дням, сначала сегодня."""
    sub = subscription.parse_subscription(_body())
    lines = [["2026-09-29", f"вт, 29 сентября: {i} " + "очень длинная строка " * 40] for i in range(8)]
    out = messages.changes_message(sub, lines)
    raw = json.dumps(out, ensure_ascii=False).encode("utf-8")
    assert len(raw) <= messages.MAX_PAYLOAD
    body = out["notification"]["body"].split("\n")
    assert body[0].startswith("вт, 29 сентября: 0 ") and len(body) < 8
    assert len(body) == len(out["notification"]["data"]["days"])


@pytest.fixture
def sending(tmp_path, monkeypatch):
    """Push с подменённой отправкой: ответы по очереди, повторы — сразу и в список."""
    answers, later = [], []
    monkeypatch.setattr(webpush, "send", lambda *a, **k: answers.pop(0))
    push = service.Push(tmp_path, _vapid())
    push._later = lambda delay, action: later.append(delay)
    return push, answers, later


def test_retry_once_after_retry_after(sending):
    push, answers, later = sending
    sub = subscription.parse_subscription(_body())
    push._subs[sub["endpoint"]] = sub
    job = delivery.Job(sub, messages.message(sub, "changes", "т", "б"), 3600)
    answers.append(webpush.Result(429, "TooManyRequests", 7))
    push._send(job)
    assert later == [7]
    answers.append(webpush.Result(503))
    push._send(job, attempt=1)
    assert later == [7]
    assert push.count() == 1


def test_no_retry_after_the_lesson_started(sending):
    push, answers, later = sending
    sub = subscription.parse_subscription(_body())
    job = delivery.Job(sub, messages.message(sub, "lesson", "т", "б"), 600, deadline=0)
    answers.append(webpush.Result(503))
    push._send(job)
    assert later == []


@pytest.mark.parametrize("status, reason", [(410, None), (404, None), (403, "VapidPkHashMismatch")])
def test_dead_or_foreign_key_subscription_is_dropped(sending, status, reason):
    push, answers, later = sending
    sub = subscription.parse_subscription(_body())
    push._subs[sub["endpoint"]] = sub
    answers.append(webpush.Result(status, reason))
    push._send(delivery.Job(sub, messages.message(sub, "hello", "т", "б"), 60))
    assert push.count() == 0 and later == []


def test_bad_token_keeps_the_subscription(sending):
    push, answers, later = sending
    sub = subscription.parse_subscription(_body())
    push._subs[sub["endpoint"]] = sub
    answers.append(webpush.Result(403, "BadJwtToken"))
    push._send(delivery.Job(sub, messages.message(sub, "hello", "т", "б"), 60))
    assert push.count() == 1 and later == []


def test_key_off_the_curve_and_control_chars_are_refused():
    """65 байт с 0x04 впереди — ещё не ключ: такую подписку служба принимала и
    не снимала никогда. Адрес с \\t проходил urlsplit и ронял httpx мимо
    журнала."""
    bad_key = {"p256dh": b64encode(b"\x04" + b"\x01" * 64), "auth": b64encode(b"0123456789abcdef")}
    with pytest.raises(subscription.BadSubscription, match="кривой"):
        subscription.parse_subscription(_body(keys=bad_key))
    assert not subscription.endpoint_allowed("https://web.push.apple.com/a\tb")
    assert not subscription.endpoint_allowed("https://web.push.apple.com/a b")


def test_services_have_their_own_queues():
    assert delivery.family("https://fcm.googleapis.com/fcm/send/x") == "google"
    assert delivery.family("https://web.push.apple.com/x") == "apple"
    assert delivery.family("https://wns2-db5p.notify.windows.com/w/?token=1") == "microsoft"
    assert delivery.family("https://updates.push.services.mozilla.com/wpush/v2/x") == "mozilla"


def test_undeliverable_subscription_is_dropped_only_if_the_service_works(sending):
    """Пять отказов подряд — подписка снята, если та же служба рассылки за это
    время доставляла другим. Сбой всей службы (или нашего ключа) подписки не
    стирает."""
    push, answers, later = sending
    dead = subscription.parse_subscription(_body(endpoint="https://web.push.apple.com/dead"))
    alive = subscription.parse_subscription(_body(endpoint="https://web.push.apple.com/alive"))
    for sub in (dead, alive):
        push._subs[sub["endpoint"]] = sub

    def hello(sub):
        return delivery.Job(sub, messages.message(sub, "hello", "т", "б"), 60)

    for _ in range(delivery.DROP_AFTER_FAILS):
        answers.append(webpush.Result(400, "BadDeviceToken"))
        push._send(hello(dead))
    assert push.count() == 2, "служба не доставила никому — не снимать"
    # Служба заработала для других, а эта всё так же не доставляется.
    answers.append(webpush.Result(201))
    push._send(hello(alive))
    answers.append(webpush.Result(400, "BadDeviceToken"))
    push._send(hello(dead))
    assert list(push._subs) == ["https://web.push.apple.com/alive"]
    # Удачная доставка обнуляет счёт.
    for _ in range(delivery.DROP_AFTER_FAILS - 1):
        answers.append(webpush.Result(400))
        push._send(hello(alive))
    answers.append(webpush.Result(201))
    push._send(hello(alive))
    answers.append(webpush.Result(400))
    push._send(hello(alive))
    assert push.count() == 1


def test_any_error_in_sending_is_logged_not_lost(sending, monkeypatch, caplog):
    push, answers, later = sending
    sub = subscription.parse_subscription(_body())
    push._subs[sub["endpoint"]] = sub

    def boom(*a, **k):
        raise RuntimeError("can't start new thread")

    monkeypatch.setattr(webpush, "send", boom)
    push._send(delivery.Job(sub, messages.message(sub, "hello", "т", "б"), 60))
    assert "RuntimeError" in caplog.text and push.count() == 1


def test_too_large_message_is_ours_not_the_subscriptions(sending, monkeypatch):
    push, answers, later = sending
    sub = subscription.parse_subscription(_body())
    push._subs[sub["endpoint"]] = sub

    def big(*a, **k):
        raise webpush.TooLarge("5000 байт")

    monkeypatch.setattr(webpush, "send", big)
    for _ in range(delivery.DROP_AFTER_FAILS + 1):
        push._send(delivery.Job(sub, messages.message(sub, "hello", "т", "б"), 60))
    assert push.count() == 1 and not push._fails


def test_reminder_is_not_sent_after_the_lesson_started(sending):
    """Очередь продержала напоминание до начала пары — оно уже не уходит."""
    push, answers, later = sending
    sub = subscription.parse_subscription(_body())
    answers.append(webpush.Result(201))
    push._send(delivery.Job(sub, messages.message(sub, "lesson", "т", "б"), 600, deadline=1))
    assert answers == [webpush.Result(201)]


def test_retry_queue_is_bounded():
    delayed = delivery._Delayed(limit=1)
    assert delayed.add(3600, lambda: None) is True
    assert delayed.add(3600, lambda: None) is False


def test_whole_service_failing_alarms_the_owner(sending, monkeypatch):
    """Сплошной отказ службы рассылки был виден только в журнале."""
    push, answers, later = sending
    said = []
    monkeypatch.setattr(service.alerts, "notify", lambda kind, text, **k: said.append((kind, text)))
    sub = subscription.parse_subscription(_body())
    push._subs[sub["endpoint"]] = sub
    for _ in range(service.ALARM_MIN_FAILS):
        answers.append(webpush.Result(403, "BadJwtToken"))
        push._send(delivery.Job(sub, messages.message(sub, "hello", "т", "б"), 60))
    push._checked -= service.CHECK_EVERY
    push._check(None, None, dt.date(2026, 9, 29), fresh=False)
    assert said and said[0][0] == "push-apple" and "BadJwtToken" in said[0][1]


def test_changes_live_until_the_end_of_their_last_day():
    """Было — 12 часов при любом дне."""
    import zoneinfo

    zone = zoneinfo.ZoneInfo("Asia/Novosibirsk")
    evening = dt.datetime(2026, 9, 29, 20, 0, tzinfo=zone)
    tomorrow = [["2026-09-29", "а"], ["2026-09-30", "б"]]
    assert messages.changes_ttl(tomorrow, evening) == 28 * 3600
    assert messages.changes_ttl([["2026-09-29", "а"]], evening) == 4 * 3600
    assert messages.changes_ttl([["2026-09-29", "а"]],
                               dt.datetime(2026, 9, 29, 23, 59, 50, tzinfo=zone)) == 60


def test_made_up_host_never_delivered_is_dropped(sending, monkeypatch):
    """Выдуманный поддомен *.notify.windows.com: имени нет в DNS, доставок
    Microsoft нет вовсе — раньше подписка не снималась никогда. Доставленная
    хоть раз — не снимается за DNS."""
    import socket

    import httpx

    push, answers, later = sending
    fake = subscription.parse_subscription(_body(endpoint="https://x1.notify.windows.com/w/?token=1"))
    push._subs[fake["endpoint"]] = fake

    def no_host(*a, **k):
        try:
            raise socket.gaierror(socket.EAI_NONAME, "Name or service not known")
        except socket.gaierror as exc:
            raise httpx.ConnectError("dns") from exc

    monkeypatch.setattr(webpush, "send", no_host)
    for _ in range(delivery.DROP_AFTER_FAILS):
        push._send(delivery.Job(fake, messages.message(fake, "hello", "т", "б"), 60),
                   attempt=1)
    assert push.count() == 0

    real = subscription.parse_subscription(_body(endpoint="https://x2.notify.windows.com/w/?token=2"))
    push._subs[real["endpoint"]] = real
    monkeypatch.setattr(webpush, "send", lambda *a, **k: webpush.Result(201))
    push._send(delivery.Job(real, messages.message(real, "hello", "т", "б"), 60))
    monkeypatch.setattr(webpush, "send", no_host)
    for _ in range(delivery.DROP_AFTER_FAILS + 1):
        push._send(delivery.Job(real, messages.message(real, "hello", "т", "б"), 60),
                   attempt=1)
    assert push.count() == 1


def test_full_house_evicts_a_never_delivered_subscription(sending, monkeypatch):
    """Потолок подписок забит мусором — новая подписка встаёт на место самой
    старой, которой ни разу не доставили; доставленные не трогаются."""
    push, answers, later = sending
    monkeypatch.setattr(service, "MAX_SUBSCRIPTIONS", 2)
    monkeypatch.setattr(push, "_dispatch", lambda jobs: None)
    delivered = subscription.parse_subscription(_body(endpoint="https://web.push.apple.com/ok"))
    junk = subscription.parse_subscription(_body(endpoint="https://web.push.apple.com/junk"))
    push._subs[delivered["endpoint"]] = {**delivered, "ok": "2026-09-29", "since": "2026-09-01"}
    push._subs[junk["endpoint"]] = {**junk, "since": "2026-09-28"}
    new = subscription.parse_subscription(_body(endpoint="https://web.push.apple.com/new"))
    assert push.subscribe(new, dt.date(2026, 9, 29)) is True
    assert set(push._subs) == {delivered["endpoint"], new["endpoint"]}
    push._subs[new["endpoint"]]["ok"] = "2026-09-29"
    other = subscription.parse_subscription(_body(endpoint="https://web.push.apple.com/other"))
    assert push.subscribe(other, dt.date(2026, 9, 29)) is False


def test_resent_subscription_keeps_its_delivered_mark(sending, monkeypatch):
    """Страница пересылает подписку раз в сутки — отметка о доставке остаётся:
    иначе под потолком настоящая уходила раньше свежего мусора."""
    push, answers, later = sending
    monkeypatch.setattr(service, "MAX_SUBSCRIPTIONS", 2)
    monkeypatch.setattr(push, "_dispatch", lambda jobs: None)
    real = subscription.parse_subscription(_body(endpoint="https://web.push.apple.com/real"))
    junk = subscription.parse_subscription(_body(endpoint="https://web.push.apple.com/junk"))
    push._subs[real["endpoint"]] = {**real, "ok": "2026-09-20", "since": "2026-09-20"}
    assert push.subscribe(real, dt.date(2026, 9, 27)) is True
    assert push._subs[real["endpoint"]]["ok"] == "2026-09-20"
    assert push._subs[real["endpoint"]]["since"] == "2026-09-27"
    push._subs[junk["endpoint"]] = {**junk, "since": "2026-09-29"}
    new = subscription.parse_subscription(_body(endpoint="https://web.push.apple.com/new"))
    assert push.subscribe(new, dt.date(2026, 9, 29)) is True
    assert set(push._subs) == {real["endpoint"], new["endpoint"]}
