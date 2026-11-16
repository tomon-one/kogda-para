"""Вечерняя сводка: первая пара завтра одной строкой."""

import datetime as dt

from whensclass.push import digest

TOMORROW = dt.date(2026, 11, 17)


def payload(**day) -> dict:
    return {
        "bells": {"1": ["09:00", "10:30"], "3": ["11:30", "13:00"]},
        "days": [{"d": TOMORROW.isoformat(), **day}],
    }


def test_first_lesson_by_bell_not_by_number():
    text = digest.text(payload(l=[
        {"n": 3, "s": "Основы алгоритмизации и программирования", "sn": "алгоритмы", "r": "214"},
        {"n": 1, "s": "Физика", "r": "305"},
    ]), TOMORROW)
    assert text == ("Завтра к 1-й паре", "Физика, 09:00, ауд. 305")


def test_short_name_from_service_and_online():
    text = digest.text(payload(l=[
        {"n": 3, "s": "Основы алгоритмизации и программирования", "sn": "алгоритмы", "r": "214"},
    ]), TOMORROW)
    assert text == ("Завтра к 3-й паре", "алгоритмы, 11:30, ауд. 214")
    online = digest.text(payload(l=[{"n": 1, "s": "Информатика", "o": 1}]),
                         TOMORROW)
    assert online == ("Завтра к 1-й паре", "Информатика, 09:00, онлайн")


def test_nothing_to_say_is_silence():
    # Пар нет, день не прочитан, день прошёл, остались только отменённые.
    assert digest.text(payload(l=[]), TOMORROW) is None
    assert digest.text(payload(l=[], un="missing"), TOMORROW) is None
    assert digest.text(payload(l=[{"n": 1, "s": "Физика", "x": 1}]), TOMORROW) is None
    assert digest.text(payload(l=[{"n": 1, "s": "Физика"}]), dt.date(2026, 11, 18)) is None
    assert digest.text(None, TOMORROW) is None


def test_saturday_evening_is_silent():
    # Сводка — вечером накануне учебного дня: в субботу молчим (воскресенье),
    # в воскресенье и пятницу — приходит.
    assert digest.evening(dt.date(2026, 11, 14)) is False
    assert digest.evening(dt.date(2026, 11, 15)) is True
    assert digest.evening(dt.date(2026, 11, 13)) is True
