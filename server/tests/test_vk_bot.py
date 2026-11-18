"""Бот ВКонтакте: поиск группы, раскладка расписания и обработка сообщений.

Без сети: обращений к VK нет — клиент подменяется, отправка перехватывается.
"""

import datetime as dt
import logging
import time

import httpx
import pytest

from whensclass.config import settings
from whensclass.domain.models import GroupRef, Lesson, Snapshot
from whensclass.vk.bot import VkBot, VkError
from whensclass.vk.render import (
    HELP_TEXT,
    SOURCE_LINE,
    find_group,
    normalize,
    reply_text,
    schedule_text,
)

GENERATED = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.UTC)
MONDAY = dt.date(2026, 9, 7)
TUESDAY = dt.date(2026, 9, 8)


def group(name: str, gid: str) -> GroupRef:
    return GroupRef(name=name, id=gid, column=0)


def make_snapshot(groups, schedule, dates, unread=None, bells=None) -> Snapshot:
    snap = Snapshot(sheet_title="лист", groups=groups, dates=dates)
    snap.schedule = schedule
    if unread is not None:
        snap.unread = unread
    if bells is not None:
        snap.bells = bells
    return snap


class FakeStore:
    def __init__(self, snapshot=None, generated=None):
        self.snapshot = snapshot
        self.generated = generated


# --- нормализация и поиск -----------------------------------------------------


def test_normalize():
    assert normalize("ИСП-924/2") == "исп-924-2"
    assert normalize("исп 924 2") == "исп-924-2"
    assert normalize("  Ё-группа//  ") == "е-группа"


def test_find_group_exact_name():
    snap = make_snapshot([group("ИСП-924/2", "isp-924-2")], {}, [MONDAY])
    found = find_group(snap, "ИСП-924/2")
    assert found.kind == "group"
    assert found.group.id == "isp-924-2"


def test_find_group_messy_input():
    snap = make_snapshot([group("ИСП-924/2", "isp-924-2")], {}, [MONDAY])
    assert find_group(snap, "исп 924 2").group.id == "isp-924-2"


def test_find_group_ambiguous():
    snap = make_snapshot(
        [group("ИСП-924/1", "isp-924-1"), group("ИСП-924/2", "isp-924-2")],
        {},
        [MONDAY],
    )
    found = find_group(snap, "924")
    assert found.kind == "ambiguous"
    assert set(found.names) == {"ИСП-924/1", "ИСП-924/2"}


def test_find_group_many():
    groups = [group(f"Г-{n}", f"g-{n}") for n in range(11)]
    snap = make_snapshot(groups, {}, [MONDAY])
    assert find_group(snap, "г").kind == "many"


def test_find_group_garbage():
    snap = make_snapshot([group("ИСП-924/2", "isp-924-2")], {}, [MONDAY])
    assert find_group(snap, "бла-бла").kind == "help"


def test_find_group_как_пишут_люди():
    # Подсказка обещает: регистр, пробелы и дефисы не важны.
    snap = make_snapshot([group("ИСП-924/1", "isp-924-1")], {}, [MONDAY])
    assert find_group(snap, "исп 924 1").kind == "group"
    assert find_group(snap, "ИСП 924-1").kind == "group"
    assert find_group(snap, "исп-924/1").kind == "group"


def test_find_group_greeting():
    snap = make_snapshot([group("ИСП-924/2", "isp-924-2")], {}, [MONDAY])
    assert find_group(snap, "/start").kind == "help"
    assert find_group(snap, "расписание").kind == "help"


def test_reply_garbage_is_help():
    snap = make_snapshot([group("ИСП-924/2", "isp-924-2")], {}, [MONDAY])
    assert reply_text(snap, "блабла", GENERATED, MONDAY) == HELP_TEXT


def test_reply_ambiguous_lists_names():
    snap = make_snapshot(
        [group("ИСП-924/1", "isp-924-1"), group("ИСП-924/2", "isp-924-2")],
        {},
        [MONDAY],
    )
    text = reply_text(snap, "924", GENERATED, MONDAY)
    assert "ИСП-924/1" in text
    assert "ИСП-924/2" in text


# --- раскладка расписания -----------------------------------------------------


def test_render_ordinary_day():
    g = group("ИСП-924/2", "isp-924-2")
    snap = make_snapshot(
        [g],
        {
            "isp-924-2": {
                MONDAY: [
                    Lesson(number=1, subject="Математика", kind="Лек",
                           teachers=("Иванова И.И.",), room="305")
                ],
                TUESDAY: [
                    Lesson(number=2, subject="Физика", kind="Пр",
                           teachers=("Петров П.П.",), room="272")
                ],
            }
        },
        [MONDAY, TUESDAY],
    )
    text = schedule_text(snap, g, GENERATED, MONDAY)
    assert "ИСП-924/2" in text
    assert "Сегодня, пн 7.9" in text
    assert "Завтра, вт 8.9" in text
    assert "1) 09:00–10:30 Математика (Лек) · каб. 305 · Иванова И.И." in text
    assert "2) 10:40–12:10 Физика (Пр) · каб. 272 · Петров П.П." in text
    assert text.endswith(SOURCE_LINE)


def test_render_online():
    g = group("ИСП-924/2", "isp-924-2")
    snap = make_snapshot(
        [g],
        {"isp-924-2": {MONDAY: [
            Lesson(number=1, subject="Математика", online=True, url="https://example.com/r")
        ]}},
        [MONDAY],
    )
    text = schedule_text(snap, g, GENERATED, MONDAY)
    assert "онлайн" in text
    assert "каб." not in text
    assert "https://example.com/r" in text


def test_render_cancelled():
    g = group("ИСП-924/2", "isp-924-2")
    snap = make_snapshot(
        [g],
        {"isp-924-2": {MONDAY: [Lesson(number=1, subject="Математика", cancelled=True)]}},
        [MONDAY],
    )
    assert "отменена" in schedule_text(snap, g, GENERATED, MONDAY)


def test_render_empty_day():
    g = group("ИСП-924/2", "isp-924-2")
    snap = make_snapshot(
        [g],
        {"isp-924-2": {TUESDAY: [Lesson(number=1, subject="Математика")]}},
        [MONDAY, TUESDAY],
    )
    text = schedule_text(snap, g, GENERATED, MONDAY)
    assert "Сегодня, пн 7.9" in text
    assert "Пар нет" in text


def test_render_unread_missing():
    target = group("ИСП-924/2", "isp-924-2")
    others = [group(f"Г-{n}", f"g-{n}") for n in range(2)]
    schedule = {g.id: {} for g in [target, *others]}
    schedule["isp-924-2"][MONDAY] = [Lesson(number=1, subject="Математика")]
    for g in others:
        schedule[g.id][MONDAY] = [Lesson(number=1, subject="А")]
        schedule[g.id][TUESDAY] = [Lesson(number=1, subject="Б")]
    snap = make_snapshot(
        [target, *others],
        schedule,
        [MONDAY, TUESDAY],
        unread={"isp-924-2": {TUESDAY: False}},
    )
    text = schedule_text(snap, target, GENERATED, MONDAY)
    assert "Завтра, вт 8.9" in text
    assert "Не прочитан" in text
    assert "Пар нет" not in text


def test_render_unread_kept():
    g = group("ИСП-924/2", "isp-924-2")
    snap = make_snapshot(
        [g],
        {"isp-924-2": {MONDAY: [Lesson(number=1, subject="Математика")]}},
        [MONDAY],
        unread={"isp-924-2": {MONDAY: True}},
    )
    text = schedule_text(snap, g, GENERATED, MONDAY)
    assert "Не прочитан, показано прежнее" in text
    assert "Математика" in text


def test_render_day_without_coverage():
    g = group("ИСП-924/2", "isp-924-2")
    sunday = MONDAY - dt.timedelta(days=1)
    snap = make_snapshot(
        [g],
        {"isp-924-2": {MONDAY: [Lesson(number=1, subject="Математика")]}},
        [MONDAY],
    )
    text = schedule_text(snap, g, GENERATED, sunday)
    assert "Сегодня, вс 6.9" in text
    assert "Расписание ещё не опубликовано" in text
    assert "Завтра, пн 7.9" in text
    assert "Математика" in text


def test_render_truncates_long():
    g = group("ИСП-924/2", "isp-924-2")

    def lessons():
        return [
            Lesson(number=(i % 6) + 1, subject=f"Предмет с длинным названием номер {i}",
                   kind="Лек", teachers=("Иванов И.И.",), room="305")
            for i in range(40)
        ]

    snap = make_snapshot(
        [g],
        {"isp-924-2": {MONDAY: lessons(), TUESDAY: lessons()}},
        [MONDAY, TUESDAY],
    )
    text = schedule_text(snap, g, GENERATED, MONDAY)
    assert len(text) <= 4096
    assert "…" in text
    assert "Сегодня, пн 7.9" in text
    assert "Завтра, вт 8.9" in text
    assert text.endswith(SOURCE_LINE)


# --- обработка message_new ----------------------------------------------------


def test_handle_replies_to_from_id(monkeypatch):
    g = group("ИСП-924/2", "isp-924-2")
    snap = make_snapshot([g], {"isp-924-2": {}}, [MONDAY])
    bot = VkBot(FakeStore(snap, GENERATED))
    sent = []
    monkeypatch.setattr(bot, "_send", lambda peer_id, message: sent.append((peer_id, message)))
    bot._handle({"type": "message_new",
                 "object": {"message": {"text": "ИСП-924/2", "from_id": 123, "peer_id": 123}}})
    assert len(sent) == 1
    assert sent[0][0] == 123
    assert "ИСП-924/2" in sent[0][1]


def test_handle_ignores_community(monkeypatch):
    bot = VkBot(FakeStore())
    sent = []
    monkeypatch.setattr(bot, "_send", lambda peer_id, message: sent.append((peer_id, message)))
    bot._handle({"type": "message_new",
                 "object": {"message": {"text": "привет", "from_id": -123456789}}})
    assert sent == []


def test_handle_ignores_empty_text(monkeypatch):
    bot = VkBot(FakeStore())
    sent = []
    monkeypatch.setattr(bot, "_send", lambda peer_id, message: sent.append((peer_id, message)))
    bot._handle({"type": "message_new", "object": {"message": {"text": "   ", "from_id": 5}}})
    assert sent == []


def test_send_includes_random_id_and_peer(monkeypatch):
    bot = VkBot(FakeStore())
    calls = []
    monkeypatch.setattr(bot, "_call",
                        lambda method, params: calls.append((method, params)) or {})
    bot._send(42, "привет")
    assert len(calls) == 1
    method, params = calls[0]
    assert method == "messages.send"
    assert params["peer_id"] == 42
    assert params["message"] == "привет"
    assert isinstance(params["random_id"], int)


def test_send_retries_on_too_many_requests(monkeypatch):
    bot = VkBot(FakeStore())
    monkeypatch.setattr(time, "sleep", lambda s: None)
    calls = []

    def fail(method, params):
        calls.append(params)
        raise VkError(method, 6, "too many requests")

    monkeypatch.setattr(bot, "_call", fail)
    bot._send(42, "привет")
    assert len(calls) == 2  # первая попытка и один повтор


def test_пробельный_токен_бота_не_поднимает(monkeypatch):
    """«Пусто — бота нет» верно и для строки из пробелов.

    Незакавыченное значение systemd обрезает сам, а в кавычках хвост остаётся;
    такой «токен» поднимал бота, который вечно писал ошибку в журнал.
    """
    from whensclass.config import Settings

    for raw in ("", " ", "\n", "\t", "  \n "):
        assert Settings(_env_file=None, vk_token=raw).vk_token is None
    # Верный токен с хвостом остаётся токеном, но без хвоста.
    assert Settings(_env_file=None, vk_token=" tok\n").vk_token == "tok"
    # Значение из окружения обрезается так же, как заданное руками.
    monkeypatch.setenv("WHENSCLASS_VK_TOKEN", "  ")
    assert Settings(_env_file=None).vk_token is None


def test_error_log_has_no_token_or_url(caplog, monkeypatch):
    monkeypatch.setattr(settings, "vk_token", "SECRET_TOKEN_123")
    bot = VkBot(FakeStore())
    monkeypatch.setattr(bot, "_call",
                        lambda method, params: (_ for _ in ()).throw(VkError(method, 10, "error")))
    with caplog.at_level(logging.WARNING):
        bot._send(42, "привет")
    assert "SECRET_TOKEN_123" not in caplog.text
    assert "api.vk.com" not in caplog.text
    assert "https://" not in caplog.text
    assert "http://" not in caplog.text


def test_call_network_error_has_no_url(monkeypatch):
    monkeypatch.setattr(settings, "vk_token", "SECRET_TOKEN_123")
    bot = VkBot(FakeStore())

    def handler(request):
        raise httpx.ConnectError("connection failed", request=request)

    bot._client = httpx.Client(transport=httpx.MockTransport(handler))
    with pytest.raises(VkError) as exc_info:
        bot._call("messages.send", {"peer_id": 1})
    assert "SECRET_TOKEN_123" not in str(exc_info.value)
    assert "api.vk.com" not in str(exc_info.value)


# --- остановка потока ---------------------------------------------------------


def test_run_recovers_then_stops_on_event(monkeypatch):
    bot = VkBot(FakeStore())
    bot._backoff = 0.0
    calls = []

    def step():
        calls.append(1)
        if len(calls) == 1:
            raise VkError("longPoll", None, "ReadTimeout")
        bot.stop.set()

    monkeypatch.setattr(bot, "_step", step)
    bot._run()
    assert len(calls) == 2


def test_run_exits_immediately_when_stopped(monkeypatch):
    bot = VkBot(FakeStore())
    bot.stop.set()
    monkeypatch.setattr(bot, "_step", lambda: pytest.fail("не должен зваться"))
    bot._run()
