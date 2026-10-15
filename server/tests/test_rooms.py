"""Аудитории дня: что считается кабинетом, занятость по парам и накладки."""

import datetime as dt

from whensclass.domain.models import GroupRef, Lesson, Snapshot
from whensclass.service.rooms import MAIN, VOSKHOD, room_of, rooms_payload

DAY = dt.date(2026, 10, 16)


def test_what_is_a_room():
    assert room_of("269") == (MAIN, "269")
    assert room_of("55/2") == (MAIN, "55/2")
    assert room_of("262а") == (MAIN, "262А")
    assert room_of("Восход  222") == (VOSKHOD, "Восход 222")
    for place in ("Спортзал", "актовый зал", "Фойе 3 этажа", "", None, "ул. Восход, д. 15", "онлайн 2"):
        assert room_of(place) is None


def _snapshot():
    groups = [GroupRef(name=n, id=n.lower(), column=4 * i) for i, n in enumerate(["А", "Б", "В", "Г"])]

    def x(n, room, subject="Физика", teacher="Иванов И.И.", **kw):
        return Lesson(number=n, subject=subject, room=room, teachers=(teacher,), **kw)

    # Кабинеты должны встречаться в листе: «269» и «270» — в другие дни.
    other = dt.date(2026, 10, 15)
    filler = {other: [x(1, "269"), x(2, "270"), x(3, "269"), x(4, "270"), x(5, "269"), x(6, "270")] * 1}
    sched = {
        "а": {**filler, DAY: [x(1, "269"), x(2, "270", teacher="Петров П.П.")]},
        # Тот же преподаватель в том же кабинете — поток, не накладка.
        "б": {**filler, DAY: [x(1, "269")]},
        # Другой преподаватель в 270 на второй паре — накладка.
        "в": {**filler, DAY: [x(2, "270", subject="Химия", teacher="Сидоров С.С."), x(3, "Спортзал")]},
        "г": {**filler, DAY: [x(1, "270", cancelled=True), x(4, "270", online=True)]},
    }
    return Snapshot("лист", groups=groups, dates=[other, DAY], schedule=sched, unread={"г": {}})


def test_busy_free_and_clashes():
    body = rooms_payload(_snapshot(), DAY, {"1": ["9:00", "10:30"]})
    assert [r["room"] for b in body["buildings"] for r in b["rooms"]] == ["269", "270"]
    assert body["buildings"][0]["rooms"][0]["floor"] == "2"
    assert set(body["busy"]["1"]) == {"269"}           # отменённая пара кабинет не занимает
    assert set(body["busy"]["2"]) == {"270"}
    assert "4" not in body["busy"]                      # онлайн не занимает кабинет
    assert [(c["n"], c["room"]) for c in body["clashes"]] == [(2, "270")]
    assert body["unread"] == []


def test_unread_group_is_named_and_its_rooms_not_counted():
    snap = _snapshot()
    snap.unread = {"а": {DAY: False}}
    body = rooms_payload(snap, DAY, {})
    assert body["unread"] == ["А"]
    assert body["clashes"] == []


def test_stream_is_one_line_and_subgroups_are_not_a_clash():
    snap = _snapshot()
    pair = lambda teacher, subject="Физика": Lesson(number=5, subject=subject, room="269", teachers=(teacher,))
    # Б и В — одна пара на поток; у Г две подгруппы в том же кабинете с разными преподавателями.
    snap.schedule["б"][DAY] = [pair("Иванов И.И.")]
    snap.schedule["в"][DAY] = [pair("Иванов И.И.")]
    body = rooms_payload(snap, DAY, {})
    assert body["busy"]["5"]["269"] == [{"g": "Б, В", "s": "Физика", "t": ["Иванов И.И."]}]
    assert body["clashes"] == []
    snap.schedule["б"][DAY] = []
    snap.schedule["в"][DAY] = []
    snap.schedule["г"][DAY] = [pair("Миллер Д.", "Немецкий"), pair("Уэллс Д.", "Английский")]
    assert rooms_payload(snap, DAY, {})["clashes"] == []


def test_lesson_with_a_link_in_a_room_takes_the_room():
    """«Преподаватель на онлайн, студенты в кабинете 269»: ссылка есть, пара очная."""
    snap = _snapshot()
    snap.schedule["б"][DAY] = [Lesson(number=6, subject="Право", room="269", teachers=("Петров П.П.",),
                                      url="https://my.mts-link.ru/j/1")]
    assert "269" in rooms_payload(snap, DAY, {})["busy"]["6"]


def test_a_shared_lesson_is_one_sighting_of_a_room():
    """Кабинет из одной ячейки общей пары — всё ещё «один раз в листе»."""
    snap = _snapshot()
    once = Lesson(number=6, subject="Право", room="368", teachers=("Петров П.П.",))
    snap.schedule["а"][DAY] = snap.schedule["а"][DAY] + [once]
    snap.schedule["б"][DAY] = snap.schedule["б"][DAY] + [once]
    rooms = [r["room"] for b in rooms_payload(snap, DAY, {})["buildings"] for r in b["rooms"]]
    assert "368" not in rooms
