"""Правила изменений и напоминаний для уведомлений сайта.

Перенесены из приложения (ScheduleDiff.kt, LessonAlarms.kt) — тесты здесь
повторяют их случаи.
"""

import datetime as dt

import pytest

from whensclass.push import changes


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
        _l(1, "Физика", t=["Сильверхенд Д."]),
        _l(2, "История", r="301"),
        _l(4, "Химия"),
        _l(5, "Право", o=1, u="https://my.mts-link.ru/a"),
    ]})
    new = _group({TUE: [
        _l(1, "Физика", t=["Кушинада Л."]),
        _l(2, "История", o=1, r="12"),
        _l(3, "Биология"),
        _l(5, "Право", o=1, u="https://evil.example/a"),
    ]})
    assert changes.compare(old, new) == [
        (TUE, "у 1 пары другой преподаватель: Кушинада Л."),
        (TUE, "2 пара стала онлайн"),
        (TUE, "у 2 пары онлайн-комната 12"),
        (TUE, "добавилась 3 пара: Биология"),
        (TUE, "убрали 4 пару: Химия"),
        (TUE, "у 5 пары сменилась ссылка — чужой адрес: evil.example"),
    ]


def test_teacher_lessons_are_tagged_with_groups():
    old = {"g": "t1", "gn": "Сильверхенд Д.", "kind": "teacher",
           "days": [{"d": TUE, "l": [_l(2, "Физика", gr="ИСП-924/1, ИСП-924/2")]}]}
    new = {"g": "t1", "gn": "Сильверхенд Д.", "kind": "teacher", "days": [{"d": TUE, "l": [
        _l(2, "Физика", gr="ИСП-924/1"), _l(2, "Физика", gr="ИСП-924/2", x=1),
    ]}]}
    assert changes.compare(old, new) == [(TUE, "отменили 2 пару (ИСП-924/2): Физика")]


def test_teacher_sees_only_the_group_that_joined_or_left():
    """Склеенная запись берёт самое длинное название, и оно меняется, когда к
    паре присоединяется или уходит группа: «убрали 2 пару (Л-926/4)» не
    должно приходить про группу, у которой ничего не менялось.
    ScheduleDiff.kt — так же."""
    def teacher(lessons):
        return {"g": "t1", "gn": "Такемура Г. С.", "kind": "teacher",
                "days": [{"d": TUE, "l": lessons}]}

    joined = changes.compare(
        teacher([_l(2, "Физическая культура", gr="Л-926/4")]),
        teacher([_l(2, "Физическая культура / Адаптивная физическая культура",
                    gr="Л-1126, Л-926/4")]),
    )
    assert joined == [
        (TUE, "добавилась 2 пара (Л-1126): Физическая культура / Адаптивная физическая культура")
    ]
    left = changes.compare(
        teacher([_l(5, "Физическая культура.", gr="ГД-926/3, ПД-925/1, ПД-925/2, ПД-925/3")]),
        teacher([_l(5, "Физическая культура", gr="ГД-926/3")]),
    )
    assert left == [(TUE, "убрали 5 пару (ПД-925/1, ПД-925/2, ПД-925/3): Физическая культура.")]


def test_teacher_gets_both_lines_when_the_subject_of_the_same_group_changes():
    """На номере те же группы, но другой предмет — замена: и «убрали»
    прежний, и «добавилась» новый, как у группы."""
    def teacher(lessons):
        return {"g": "t1", "gn": "Такемура Г. С.", "kind": "teacher",
                "days": [{"d": TUE, "l": lessons}]}

    swapped = changes.compare(teacher([_l(3, "Физика", gr="ИСП-924/1")]),
                              teacher([_l(3, "Астрономия", gr="ИСП-924/1")]))
    assert sorted(text for _, text in swapped) == [
        "добавилась 3 пара (ИСП-924/1): Астрономия",
        "убрали 3 пару (ИСП-924/1): Физика",
    ]


@pytest.mark.parametrize("old, new", [
    ("Обествознание", "Обществознание"),
    ("кураторский час", "Кураторский час"),
    ("Физическая культура / Адаптивная физическая культура", "Физическая культура"),
])
def test_spelling_fix_is_not_a_change(old, new):
    """Исправили написание — пара та же: без «убрали» и «добавилась»."""
    was = _group({TUE: [_l(2, old, t=["Сильверхенд Д."], r="301")]})
    assert changes.compare(was, _group({TUE: [_l(2, new, t=["Сильверхенд Д."], r="301")]})) == []
    # Другой преподаватель — уже другая пара.
    assert len(changes.compare(was, _group({TUE: [_l(2, new, t=["Кушинада Л."], r="301")]}))) == 2


@pytest.mark.parametrize("url, host", [
    ("https://[ссылка", "https://[ссылка"),
    ("https://my.mts-link.ru／j/1", "https://my.mts-link.ru／j/1"),
    ("https://evil.com\\my.mts-link.ru/j/1", "evil.com"),
])
def test_crooked_or_backslash_link_is_foreign_and_does_not_break(url, host):
    """Кривая ссылка не роняет сводку всем подписчикам, а «\\» не выдаёт
    чужой адрес за площадку колледжа."""
    old = _group({TUE: [_l(1, "Право", o=1)]})
    new = _group({TUE: [_l(1, "Право", o=1, u=url)]})
    assert changes.compare(old, new) == [(TUE, f"у 1 пары появилась ссылка — чужой адрес: {host}")]


def test_trimmed_changes_keep_today():
    """Больше восьми строк — режется завтрашний хвост, а не сегодня."""
    old = _group({"2026-09-28": [_l(1, "А")], TUE: [_l(n, f"Б{n}") for n in range(1, 9)]})
    new = _group({"2026-09-28": [_l(1, "А", x=1)], TUE: [_l(n, f"Б{n}", x=1) for n in range(1, 9)]})
    lines = changes.change_lines(old, new, dt.date(2026, 9, 28))
    assert len(lines) == 8 and lines[0] == ["2026-09-28", "пн, 28 сентября: отменили 1 пару: А"]


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
        _l(1, "Физика", r="275", k="Лек", t=["Сильверхенд Д."]),
        _l(2, "История", r="актовый зал"),
        _l(3, "Химия", x=1),
        _l(4, "Право", o=1, r="12"),
    ]})
    plan = changes.reminders(payload, 20, day)
    # 1-я — первая в дне; 2-я — напоминание в 10:20, посреди 1-й (до 10:30),
    # — нет; 3-я отменена; 4-я — после окна: 14:00 — не посреди пары.
    assert [(a["number"], a["at"].strftime("%H:%M")) for a in plan] == [(1, "08:40"), (4, "14:00")]
    assert plan[0]["title"] == "09:00 — Физика"
    assert plan[0]["text"] == "Каб. 275. 1 пара, лекция. Сильверхенд Д."
    assert plan[1]["text"] == "Онлайн, комната 12. 4 пара"
    # За 10 минут до 2-й — ровно на перемене (10:30): звонок — ещё не перемена.
    assert [a["number"] for a in changes.reminders(payload, 10, day)] == [1, 4]
    assert [a["number"] for a in changes.reminders(payload, 5, day)] == [1, 2, 4]


def test_reminder_names_both_lessons_of_a_halved_block():
    day = dt.date(2026, 9, 29)
    payload = _group({TUE: [
        _l(4, "Немецкий", r="55/1", t=["Пикулина Л. Е."]),
        _l(4, "Английский", r="467", t=["Здорик И. Р."]),
    ]})
    plan = changes.reminders(payload, 15, day)
    assert [a["number"] for a in plan] == [4]
    assert plan[0]["title"] == "14:20 — Немецкий / Английский"
    assert plan[0]["subject"] == "Немецкий / Английский"
    assert plan[0]["text"] == "Каб. 55/1 — Немецкий. Пикулина Л. Е.\nКаб. 467 — Английский. Здорик И. Р."
    # Отменили одну — напоминание об оставшейся, как об обычной паре.
    payload["days"][0]["l"][0]["x"] = 1
    assert changes.reminders(payload, 15, day)[0]["text"] == "Каб. 467. 4 пара. Здорик И. Р."


def test_reminder_names_the_group_only_for_teacher():
    lesson = _l(2, "Физика", r="275", gr="ИСП-924/2")
    assert changes.reminder_text(lesson, "ИСП-924/1") == "Каб. 275. 2 пара. ИСП-924/2"
    assert changes.reminder_text(_l(2, "Физика", r="актовый зал"), "ИСП-924/1") == "Актовый зал. 2 пара"
