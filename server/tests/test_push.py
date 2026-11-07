"""Уведомления сайта: подписки, служба целиком, API.

Шифрование — test_webpush.py, правила изменений и напоминаний —
test_push_changes.py, рассылка — test_push_delivery.py.
"""

import dataclasses
import datetime as dt

import pytest
from cryptography.hazmat.primitives.asymmetric import ec

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
        # Днём: тихие часы проверяются отдельно.
        self.clock = lambda: dt.datetime(2026, 9, 8, 12, 0)

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


def test_changes_at_night_wait_for_the_morning(tmp_path, snapshots):
    before, bt, after, at, day, first = snapshots
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-2")), day)
    push.sent.clear()
    push.clock = lambda: dt.datetime(2026, 9, 7, 22, 30)
    push.after_refresh(before, bt, after, at, day)
    assert push.sent == []
    # Придержанное переживает перезапуск службы.
    again = Recorder(tmp_path, _vapid())
    again.remind(after, at, now=dt.datetime(2026, 9, 8, 6, 59))
    assert again.sent == []
    again.remind(after, at, now=dt.datetime(2026, 9, 8, 7, 0))
    changed = [j for j in again.sent if j.message["notification"]["data"]["t"] == "changes"]
    assert len(changed) == 1
    assert changed[0].message["notification"]["body"] == f"вт, 8 сентября: отменили {first.number} пару: {first.subject}"
    again.sent.clear()
    again.remind(after, at, now=dt.datetime(2026, 9, 8, 7, 1))
    assert not [j for j in again.sent if j.message["notification"]["data"]["t"] == "changes"]


def test_held_changes_go_out_by_day(tmp_path, snapshots):
    """Ночью правки нескольких заходов копятся по приходу; утром сегодня — первым:
    сервис-воркер оставляет первые восемь строк."""
    _, _, after, at, day, _ = snapshots
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-2")), day)
    push.sent.clear()
    push._held = {("group", "isp-924-2"): [["2026-09-09", "ср: первая"], ["2026-09-08", "вт: вторая"],
                                           ["2026-09-09", "ср: третья"]]}
    push.remind(after, at, now=dt.datetime(2026, 9, 8, 7, 0))
    changed = [j for j in push.sent if j.message["notification"]["data"]["t"] == "changes"]
    assert changed[0].message["notification"]["body"] == "вт: вторая\nср: первая\nср: третья"
    # Больше восьми: уходят дальние дни, а в сегодняшнем — старые строки.
    many = [["2026-09-09", f"ср: {n}"] for n in range(4)] + [["2026-09-08", f"вт: {n}"] for n in range(7)]
    many.insert(5, ["2026-09-08", "вт: отменили"])
    many.append(["2026-09-08", "вт: вернули"])
    today = [x for x in many if x[0] == "2026-09-08"]
    assert len(today) == 9 and service._by_day(many) == today[1:]


def test_day_change_after_missed_morning_goes_after_the_night(tmp_path, snapshots):
    """Служба лежала в 7:00: первый заход днём шлёт ночное и свежее одним
    сообщением, свежее — последним."""
    before, bt, after, at, day, first = snapshots
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-2")), day)
    push.sent.clear()
    push._held = {("group", "isp-924-2"): [["2026-09-08", "вт: ночная"]]}
    push.clock = lambda: dt.datetime(2026, 9, 8, 7, 30)
    push.after_refresh(before, bt, after, at, day)
    assert len(push.sent) == 1 and push._held == {}
    assert push.sent[0].message["notification"]["body"] == \
        f"вт: ночная\nвт, 8 сентября: отменили {first.number} пару: {first.subject}"


def test_held_changes_drop_past_days(tmp_path, snapshots):
    before, bt, after, at, day, _ = snapshots
    push = Recorder(tmp_path, _vapid())
    push.subscribe(subscription.parse_subscription(_body(id="isp-924-2")), day)
    push.sent.clear()
    push.clock = lambda: dt.datetime(2026, 9, 7, 23, 0)
    push.after_refresh(before, bt, after, at, day)
    # Утро после дня, о котором строки: сказать уже нечего.
    push.remind(after, at, now=dt.datetime(2026, 9, 9, 7, 0))
    assert not [j for j in push.sent if j.message["notification"]["data"]["t"] == "changes"]


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


def test_teacher_subscription_follows_a_corrected_spelling(tmp_path, snapshots):
    """Колледж исправил опечатку в имени в том же обновлении, где отменил
    пару: подписка по прежнему id получает отмену, хотя ответ пришёл под
    другим id."""
    before, bt, after, at, day, first = snapshots
    name = first.teachers[0]
    fixed = name[:-1] + ("а" if name[-1] != "а" else "о")
    for by_date in after.schedule.values():
        for d, lessons in by_date.items():
            by_date[d] = [dataclasses.replace(x, teachers=tuple(fixed if t == name else t for t in x.teachers))
                          for x in lessons]
    from whensclass.domain.teachers import build_index

    tid = next(t for t, n in bt.names.items() if n == name)
    push = Recorder(tmp_path, _vapid())
    push.known_teacher = {tid: name}.get
    push.subscribe(subscription.parse_subscription(_body(kind="teacher", id=tid)), day)
    push.sent.clear()
    push.after_refresh(before, bt, after, build_index(after), day)
    assert [j.message["notification"]["data"]["t"] for j in push.sent] == ["changes"]
    assert f"отменили {first.number} пару" in push.sent[0].message["notification"]["body"]


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
