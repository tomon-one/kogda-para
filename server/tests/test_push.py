"""Уведомления сайта: шифрование по RFC 8291, подпись VAPID, подписки, правила.

Правила изменений и напоминаний перенесены из приложения (ScheduleDiff.kt,
LessonAlarms.kt) — тесты здесь повторяют их случаи.
"""

import dataclasses
import datetime as dt
import json

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives import hmac as chmac
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import FastAPI
from fastapi.testclient import TestClient

from whensclass.push import changes, service, webpush
from whensclass.push.webpush import b64decode, b64encode


def _private(b64: str) -> ec.EllipticCurvePrivateKey:
    return ec.derive_private_key(int.from_bytes(b64decode(b64), "big"), ec.SECP256R1())


# --- RFC 8291, приложение A ----------------------------------------------------

RFC_PLAINTEXT = b"When I grow up, I want to be a watermelon"
RFC_AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
RFC_UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
RFC_UA_PUBLIC = (
    "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
)
RFC_SALT = "DGv6ra1nlYgDCS1FRnbzlw"
RFC_AUTH = "BTBZMqHH6r4Tts7J_aSIgg"
RFC_BODY = (
    "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmY"
    "WAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSg"
    "Sxsj_Qulcy4a-fN"
)


def test_encryption_matches_rfc_8291_example():
    body = webpush.encrypt(
        RFC_PLAINTEXT,
        b64decode(RFC_UA_PUBLIC),
        b64decode(RFC_AUTH),
        server_key=_private(RFC_AS_PRIVATE),
        salt=b64decode(RFC_SALT),
    )
    assert b64encode(body) == RFC_BODY


def _decrypt(body: bytes, ua_private: ec.EllipticCurvePrivateKey, auth: bytes) -> bytes:
    """Расшифровка стороной браузера — по тексту RFC, независимо от encrypt()."""
    salt, rs, idlen = body[:16], int.from_bytes(body[16:20], "big"), body[20]
    server_public = body[21:21 + idlen]
    ciphertext = body[21 + idlen:]
    assert rs == 4096 and idlen == 65
    shared = ua_private.exchange(
        ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), server_public)
    )
    ua_public = ua_private.public_key().public_bytes(
        webpush.Encoding.X962, webpush.PublicFormat.UncompressedPoint
    )

    def hkdf(salt: bytes, ikm: bytes, info: bytes, length: int) -> bytes:
        h = chmac.HMAC(salt, hashes.SHA256())
        h.update(ikm)
        prk = h.finalize()
        h = chmac.HMAC(prk, hashes.SHA256())
        h.update(info + b"\x01")
        return h.finalize()[:length]

    ikm = hkdf(auth, shared, b"WebPush: info\x00" + ua_public + server_public, 32)
    cek = hkdf(salt, ikm, b"Content-Encoding: aes128gcm\x00", 16)
    nonce = hkdf(salt, ikm, b"Content-Encoding: nonce\x00", 12)
    record = AESGCM(cek).decrypt(nonce, ciphertext, None)
    assert record.endswith(b"\x02")
    return record[:-1]


def test_encryption_round_trip_with_fresh_keys():
    ua = ec.generate_private_key(ec.SECP256R1())
    ua_public = ua.public_key().public_bytes(
        webpush.Encoding.X962, webpush.PublicFormat.UncompressedPoint
    )
    auth = b"0123456789abcdef"
    message = json.dumps({"t": "changes", "lines": [["2026-09-29", "вт, 29 сентября: отменили"]]},
                         ensure_ascii=False).encode()
    first = webpush.encrypt(message, ua_public, auth)
    second = webpush.encrypt(message, ua_public, auth)
    assert _decrypt(first, ua, auth) == message
    # Соль и ключ сервера каждый раз новые: одно и то же сообщение не
    # шифруется одинаково.
    assert first[:16] != second[:16] and first != second


def test_vapid_header_is_a_valid_es256_jwt():
    key = ec.generate_private_key(ec.SECP256R1())
    private = b64encode(key.private_numbers().private_value.to_bytes(32, "big"))
    vapid = webpush.Vapid(private, "https://kogda-para-nsk.ru")
    header = vapid.header("https://web.push.apple.com/QGuQyavXutnMtjIJaD4R/abc", now=1_800_000_000)
    assert header.startswith("vapid t=")
    token, k = header[len("vapid t="):].split(", k=")
    assert k == vapid.public
    head, body, signature = token.split(".")
    assert json.loads(b64decode(head)) == {"typ": "JWT", "alg": "ES256"}
    claims = json.loads(b64decode(body))
    # 12 часов: Apple — не дальше суток и не обновлять чаще раза в час.
    assert claims == {"aud": "https://web.push.apple.com", "exp": 1_800_043_200,
                      "sub": "https://kogda-para-nsk.ru"}
    raw = b64decode(signature)
    der = encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"))
    ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), b64decode(k)).verify(
        der, f"{head}.{body}".encode(), ec.ECDSA(hashes.SHA256())
    )


def test_vapid_token_is_reused_until_an_hour_is_left():
    key = ec.generate_private_key(ec.SECP256R1())
    vapid = webpush.Vapid(b64encode(key.private_numbers().private_value.to_bytes(32, "big")),
                          "https://kogda-para-nsk.ru")
    apple = "https://web.push.apple.com/a"
    first = vapid.header(apple, now=1_800_000_000)
    assert vapid.header(apple + "b", now=1_800_000_000 + 10 * 3600) == first
    # Другая служба — свой aud и свой токен.
    assert vapid.header("https://fcm.googleapis.com/fcm/send/x", now=1_800_000_000) != first
    # До конца меньше часа — новый.
    assert vapid.header(apple, now=1_800_000_000 + 11 * 3600 + 1) != first


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
    assert service.endpoint_allowed(endpoint) is ok


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
    sub = service.parse_subscription(_body())
    assert sub["kind"] == "group" and sub["remind"] == 20 and sub["changes"] is True
    for bad in (
        _body(kind="room"), _body(id=""), _body(remind=5), _body(remind=241), _body(remind=True),
        _body(changes="да"), _body(keys={"p256dh": "AAAA", "auth": "AAAA"}),
        _body(endpoint="https://example.com/x"), [], "текст",
    ):
        with pytest.raises(service.BadSubscription):
            service.parse_subscription(bad)


def test_subscriptions_survive_restart_and_turn_off(tmp_path):
    push = service.Push(tmp_path, None)
    today = dt.date(2026, 9, 29)
    assert push.subscribe(service.parse_subscription(_body()), today)
    again = service.Push(tmp_path, None)
    assert again.count() == 1
    # Ни изменений, ни напоминаний — подписка стирается, а не висит пустой.
    assert again.subscribe(service.parse_subscription(_body(changes=False, remind=0)), today)
    assert service.Push(tmp_path, None).count() == 0


# --- изменения (ScheduleDiff) ----------------------------------------------------


def _group(days: dict[str, list[dict]], name="ИСП-924/1") -> dict:
    return {"g": "isp-924-1", "gn": name, "days": [{"d": d, "l": l} for d, l in days.items()]}


def _l(n, s, **kw) -> dict:
    return {"n": n, "s": s, **kw}


TUE = "2026-09-29"


def test_cancel_room_and_replacement():
    old = _group({TUE: [_l(1, "Физика", r="275"), _l(2, "История", r="301"), _l(3, "Химия")]})
    new = _group({TUE: [
        _l(1, "Физика", r="355"),
        _l(2, "История", r="301", x=1),
        _l(3, "Биология", c="вместо: Химия"),
    ]})
    assert changes.compare(old, new) == [
        (TUE, "1 пара переехала в каб. 355"),
        (TUE, "отменили 2 пару: История"),
        (TUE, "замена 3 пары: Химия → Биология"),
    ]


def test_added_removed_online_teacher_and_links():
    old = _group({TUE: [
        _l(1, "Физика", t=["Иванов И. И."]),
        _l(2, "История", r="301"),
        _l(4, "Химия"),
        _l(5, "Право", o=1, u="https://my.mts-link.ru/a"),
    ]})
    new = _group({TUE: [
        _l(1, "Физика", t=["Петров П. П."]),
        _l(2, "История", o=1, r="12"),
        _l(3, "Биология"),
        _l(5, "Право", o=1, u="https://evil.example/a"),
    ]})
    assert changes.compare(old, new) == [
        (TUE, "у 1 пары другой преподаватель: Петров П. П."),
        (TUE, "2 пара стала онлайн"),
        (TUE, "у 2 пары онлайн-комната 12"),
        (TUE, "добавилась 3 пара: Биология"),
        (TUE, "убрали 4 пару: Химия"),
        (TUE, "у 5 пары сменилась ссылка — чужой адрес: evil.example"),
    ]


def test_teacher_lessons_are_tagged_with_groups():
    old = {"g": "t1", "gn": "Иванов И. И.", "kind": "teacher",
           "days": [{"d": TUE, "l": [_l(2, "Физика", gr="ИСП-924/1, ИСП-924/2")]}]}
    new = {"g": "t1", "gn": "Иванов И. И.", "kind": "teacher", "days": [{"d": TUE, "l": [
        _l(2, "Физика", gr="ИСП-924/1"), _l(2, "Физика", gr="ИСП-924/2", x=1),
    ]}]}
    assert changes.compare(old, new) == [(TUE, "отменили 2 пару (ИСП-924/2): Физика")]


def test_only_today_and_tomorrow_with_day_and_date():
    old = _group({"2026-09-28": [_l(1, "А")], TUE: [_l(1, "Б")], "2026-09-30": [_l(1, "В")]})
    new = _group({"2026-09-28": [_l(1, "А", x=1)], TUE: [_l(1, "Б", x=1)],
                  "2026-09-30": [_l(1, "В", x=1)]})
    assert changes.change_lines(old, new, dt.date(2026, 9, 28)) == [
        ["2026-09-28", "пн, 28 сентября: отменили 1 пару: А"],
        [TUE, "вт, 29 сентября: отменили 1 пару: Б"],
    ]


def test_new_day_and_other_subject_are_not_changes():
    old = _group({TUE: [_l(1, "А")]})
    assert changes.compare(old, _group({TUE: [_l(1, "А")], "2026-09-30": [_l(1, "Б")]})) == []
    other = {**_group({TUE: [_l(1, "Б")]}), "g": "isp-924-2"}
    assert changes.compare(old, other) == []
    assert changes.compare(None, old) == []


# --- напоминания (LessonAlarms.plan) -------------------------------------------------


def test_reminders_first_of_day_and_after_a_window():
    day = dt.date(2026, 9, 29)
    payload = _group({TUE: [
        _l(1, "Физика", r="275", k="Лек", t=["Иванов И. И."]),
        _l(2, "История", r="актовый зал"),
        _l(3, "Химия", x=1),
        _l(4, "Право", o=1, r="12"),
    ]})
    plan = changes.reminders(payload, 20, day)
    # 1-я — первая в дне; 2-я — напоминание в 10:20, посреди 1-й (до 10:30),
    # — нет; 3-я отменена; 4-я — после окна: 14:00 — не посреди пары.
    assert [(a["number"], a["at"].strftime("%H:%M")) for a in plan] == [(1, "08:40"), (4, "14:00")]
    assert plan[0]["title"] == "09:00 — Физика"
    assert plan[0]["text"] == "Каб. 275. 1 пара, лекция. Иванов И. И."
    assert plan[1]["text"] == "Онлайн, комната 12. 4 пара"
    # За 10 минут до 2-й — ровно на перемене (10:30): звонок — ещё не перемена.
    assert [a["number"] for a in changes.reminders(payload, 10, day)] == [1, 4]
    assert [a["number"] for a in changes.reminders(payload, 5, day)] == [1, 2, 4]


def test_reminder_names_the_group_only_for_teacher():
    lesson = _l(2, "Физика", r="275", gr="ИСП-924/2")
    assert changes.reminder_text(lesson, "ИСП-924/1") == "Каб. 275. 2 пара. ИСП-924/2"
    assert changes.reminder_text(_l(2, "Физика", r="актовый зал"), "ИСП-924/1") == "Актовый зал. 2 пара"


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
    from whensclass.parser.csv_schedule import FIXTURE, parse_csv

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
    push.subscribe(service.parse_subscription(_body()), day)
    push.subscribe(service.parse_subscription(_body(remind=30)), day)
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
    push.subscribe(service.parse_subscription(_body(site="tested")), dt.date(2026, 9, 29))
    assert push.sent[0].message["notification"]["navigate"] == "https://kogda-para-nsk.ru/tested/"
    with pytest.raises(service.BadSubscription):
        service.parse_subscription(_body(site="evil"))


def test_after_refresh_tells_only_subscribers_of_that_group(tmp_path, snapshots):
    before, bt, after, at, day, first = snapshots
    push = Recorder(tmp_path, _vapid())
    push.subscribe(service.parse_subscription(_body(id="isp-924-2")), day)
    push.subscribe(service.parse_subscription(
        _body(id="isp-924-1", endpoint="https://web.push.apple.com/other")), day)
    push.subscribe(service.parse_subscription(
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
    push.subscribe(service.parse_subscription(_body(id="isp-924-2", changes=False, remind=20)), day)
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
    push.subscribe(service.parse_subscription(_body(id="isp-924-2")), day)
    push.sent.clear()
    push.after_refresh(before, bt, after, at, day)
    assert push.sent == []


# --- API ----------------------------------------------------------------------------


@pytest.fixture
def api(tmp_path, fixture_csv, monkeypatch):
    from test_api import FakeStore

    from whensclass.api import routes
    from whensclass.parser.csv_schedule import FIXTURE, parse_csv

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


def test_changes_fit_the_push_size_newest_kept():
    sub = service.parse_subscription(_body())
    lines = [["2026-09-29", f"вт, 29 сентября: {i} " + "очень длинная строка " * 40] for i in range(8)]
    out = service.changes_message(sub, lines)
    raw = json.dumps(out, ensure_ascii=False).encode("utf-8")
    assert len(raw) <= service.MAX_PAYLOAD
    body = out["notification"]["body"].split("\n")
    assert body[-1].startswith("вт, 29 сентября: 7 ")
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
    sub = service.parse_subscription(_body())
    push._subs[sub["endpoint"]] = sub
    job = service.Job(sub, service.message(sub, "changes", "т", "б"), 3600)
    answers.append(webpush.Result(429, "TooManyRequests", 7))
    push._send(job)
    assert later == [7]
    answers.append(webpush.Result(503))
    push._send(job, attempt=1)
    assert later == [7]
    assert push.count() == 1


def test_no_retry_after_the_lesson_started(sending):
    push, answers, later = sending
    sub = service.parse_subscription(_body())
    job = service.Job(sub, service.message(sub, "lesson", "т", "б"), 600, deadline=0)
    answers.append(webpush.Result(503))
    push._send(job)
    assert later == []


@pytest.mark.parametrize("status, reason", [(410, None), (404, None), (403, "VapidPkHashMismatch")])
def test_dead_or_foreign_key_subscription_is_dropped(sending, status, reason):
    push, answers, later = sending
    sub = service.parse_subscription(_body())
    push._subs[sub["endpoint"]] = sub
    answers.append(webpush.Result(status, reason))
    push._send(service.Job(sub, service.message(sub, "hello", "т", "б"), 60))
    assert push.count() == 0 and later == []


def test_bad_token_keeps_the_subscription(sending):
    push, answers, later = sending
    sub = service.parse_subscription(_body())
    push._subs[sub["endpoint"]] = sub
    answers.append(webpush.Result(403, "BadJwtToken"))
    push._send(service.Job(sub, service.message(sub, "hello", "т", "б"), 60))
    assert push.count() == 1 and later == []


def test_browser_moved_subscription_keeps_the_choice(api):
    client, push = api
    assert client.post("/v1/push/subscribe", json=_body(id="isp-924-2", remind=45)).status_code == 204
    fresh = _body(endpoint="https://web.push.apple.com/new")
    body = {"old": "https://web.push.apple.com/abc", "endpoint": fresh["endpoint"], "keys": fresh["keys"]}
    assert client.post("/v1/push/move", json=body).status_code == 204
    assert list(push._subs) == ["https://web.push.apple.com/new"]
    moved = push._subs["https://web.push.apple.com/new"]
    assert moved["id"] == "isp-924-2" and moved["remind"] == 45 and moved["p256dh"] == fresh["keys"]["p256dh"]
    # Чужой адрес вместо нового — отказ; неизвестный прежний — ничего не меняет.
    bad = {**body, "old": "https://web.push.apple.com/new", "endpoint": "https://10.0.0.1/x"}
    assert client.post("/v1/push/move", json=bad).status_code == 422
    assert client.post("/v1/push/move", json={**body, "old": "https://web.push.apple.com/none"}
                       ).status_code == 204
    assert list(push._subs) == ["https://web.push.apple.com/new"]
