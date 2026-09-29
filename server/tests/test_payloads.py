"""Контракт JSON. Golden-файл обновляется только осознанно:

    python tools/update_golden.py

Если тест упал — сначала посмотрите на diff: контракт читает приложение,
которое уже стоит у людей на телефонах.
"""

import datetime as dt
import json

import pytest

from whensclass.api.payloads import groups_payload, schedule_payload
from whensclass.domain.models import GroupRef, Lesson, Snapshot
from whensclass.parser.csv_schedule import FIXTURE, parse_csv

from conftest import GOLDEN

GENERATED = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.timezone.utc)
START = dt.date(2026, 9, 7)


@pytest.fixture(scope="module")
def snapshot(fixture_csv):
    return parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)


def test_schedule_matches_golden(snapshot):
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, START, 3, GENERATED)
    expected = json.loads((GOLDEN / "isp-924-2.json").read_text("utf-8"))
    assert body == expected


def test_unknown_group_gives_nothing(snapshot):
    assert schedule_payload(snapshot, "нет-такой", START, 3, GENERATED) is None


def test_empty_fields_are_absent(snapshot):
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, START, 3, GENERATED)
    for day in body["days"]:
        for lesson in day["l"]:
            assert None not in lesson.values()
            assert set(lesson) <= {"n", "s", "k", "t", "r", "u", "x", "c", "o"}


def test_day_out_of_coverage_is_omitted(snapshot):
    """Дата за пределами листа не попадает в ответ — это не «пар нет»."""
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, dt.date(2026, 9, 11), 5, GENERATED)
    assert [d["d"] for d in body["days"]] == ["2026-09-11", "2026-09-12"]


def test_free_day_is_present_but_empty(snapshot):
    """А вот день без пар в ответе есть — с пустым списком."""
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, dt.date(2026, 9, 5), 1, GENERATED)
    assert body["days"] == [{"d": "2026-09-05", "l": []}]


def test_bells_included_only_when_known(snapshot):
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    assert "bells" not in schedule_payload(snapshot, group.id, START, 3, GENERATED)
    with_bells = schedule_payload(
        snapshot, group.id, START, 3, GENERATED, bells={"1": ["08:30", "10:00"]}
    )
    assert with_bells["bells"] == {"1": ["08:30", "10:00"]}


def test_groups_payload(snapshot):
    body = groups_payload(snapshot, GENERATED)
    assert body["v"] == 1
    ids = [g["id"] for g in body["groups"]]
    assert "isp-924-2" in ids
    assert len(ids) == len(set(ids)), "идентификаторы групп обязаны быть уникальны"


def test_online_without_a_link_still_says_online(snapshot):
    """Признак «онлайн» отдельный от ссылки, и в ответе он есть без неё.

    Ровно эта подмена — признак через его следствие — стоила нам вечера
    8 сентября: приложение объявило четыре пары «ставшими онлайн» в день,
    когда им всего лишь дописали ссылки. Разбор ячейки закреплён в
    test_cells.py, а сборка ответа не проверялась ничем: ключ можно было
    выбросить целиком, и весь набор остался бы зелёным.
    """
    import dataclasses

    from whensclass.domain.models import Snapshot

    # В фикстуре до 14 сентября 2026 у БП-926/1 11 сентября стояли четыре
    # онлайн-пары «без ссылки» — на деле ссылки там были, их выбрасывал gviz.
    # Пары без ссылки в листе есть (слово «онлайн» в колонке аудитории), но
    # не у групп фикстуры, поэтому здесь они сделаны из настоящих.
    group = next(g for g in snapshot.groups if g.name == "БП-926/1")
    day = dt.date(2026, 9, 11)
    bare = Snapshot(sheet_title=snapshot.sheet_title, groups=list(snapshot.groups),
                    dates=list(snapshot.dates))
    bare.schedule = {group.id: {day: [
        dataclasses.replace(x, url=None, room=None, online=True)
        for x in snapshot.schedule[group.id][day]
    ]}}
    body = schedule_payload(bare, group.id, day, 1, GENERATED)

    lessons = body["days"][0]["l"]
    assert lessons, "в фикстуре у этой группы 11 сентября четыре онлайн-пары"
    for lesson in lessons:
        assert lesson["o"] == 1
        assert "u" not in lesson, "ссылки у этих пар нет"
        assert "r" not in lesson, "вместо аудитории в ячейке стояло слово «онлайн»"


def test_offline_lesson_has_no_online_mark(snapshot):
    """И обратное: у очной пары признака нет вовсе, а не «o»: 0."""
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, START, 3, GENERATED)

    offline = [lesson for day in body["days"] for lesson in day["l"] if "r" in lesson]
    assert offline, "в окне должны быть пары с аудиторией"
    for lesson in offline:
        assert "o" not in lesson


def _filling(filled: dict[dt.date, int], groups: int = 10) -> Snapshot:
    """Лист, где в день `d` пары вписаны у `filled[d]` групп из `groups`."""

    refs = [GroupRef(name=f"Г-{n}", id=f"g-{n}", column=2 + 4 * n) for n in range(groups)]
    snap = Snapshot(sheet_title="лист", groups=refs, dates=sorted(filled))
    for n, ref in enumerate(refs):
        snap.schedule[ref.id] = {
            day: [Lesson(number=1, subject="Математика")] for day, count in filled.items() if n < count
        }
    return snap


# Понедельник 21.09 — суббота 03.10: неделя выложена, следующая только начата.
WEEK = [dt.date(2026, 9, 21) + dt.timedelta(days=d) for d in range(6)]
NEXT = [dt.date(2026, 9, 28) + dt.timedelta(days=d) for d in range(6)]
THURSDAY = dt.date(2026, 9, 24)


def test_unfilled_next_week_is_not_published():
    """24 сентября 2026: колледж вписал следующую неделю у двух групп из
    десяти, а остальным служба отдавала понедельник как «пар нет». Хвост,
    который колледж только начал, — «ещё не опубликовано», а не выходной."""
    from whensclass.api.payloads import meta_payload

    snap = _filling({**{d: 10 for d in WEEK}, **{d: 2 for d in NEXT}})
    empty = schedule_payload(snap, "g-5", dt.date(2026, 9, 21), 8, GENERATED, today=THURSDAY)
    assert [d["d"] for d in empty["days"]] == [d.isoformat() for d in WEEK]
    assert empty["cov"] == ["2026-09-21", "2026-09-26"]
    meta = meta_payload(snap, GENERATED, "ok", None, today=THURSDAY)
    assert meta["cov"] == ["2026-09-21", "2026-09-26"]

    # У кого пары уже вписаны — тому неделя выложена.
    filled = schedule_payload(snap, "g-0", dt.date(2026, 9, 21), 8, GENERATED, today=THURSDAY)
    assert filled["days"][-1] == {"d": "2026-09-28", "l": [{"n": 1, "s": "Математика"}]}
    assert filled["cov"] == ["2026-09-21", "2026-10-03"]


def test_filled_week_is_published_and_holiday_inside_stays_free():
    """Дописанная неделя выложена целиком; пустой день посреди неё —
    праздник — остаётся «пар нет», а не «ещё не опубликовано»."""
    filling = {**{d: 10 for d in WEEK}, **{d: 10 for d in NEXT}}
    filling[NEXT[2]] = 0
    snap = _filling(filling)
    body = schedule_payload(snap, "g-9", dt.date(2026, 9, 28), 6, GENERATED, today=THURSDAY)
    assert body["cov"] == ["2026-09-21", "2026-10-03"]
    assert {"d": "2026-09-30", "l": []} in body["days"]


def test_group_the_college_has_not_reached_sees_unpublished():
    """Колледж дописывает колонку группы сразу на
    месяц и группу за группой. Дописана больше половины групп — доля по листу
    пропускает неделю, и остальные получали её как «пар нет». Край у каждой
    группы свой."""
    snap = _filling({**{d: 10 for d in WEEK}, **{d: 6 for d in NEXT}})

    behind = schedule_payload(snap, "g-7", dt.date(2026, 9, 21), 13, GENERATED, today=THURSDAY)
    assert behind["cov"] == ["2026-09-21", "2026-09-26"]
    assert [d["d"] for d in behind["days"]] == [d.isoformat() for d in WEEK]

    done = schedule_payload(snap, "g-0", dt.date(2026, 9, 21), 13, GENERATED, today=THURSDAY)
    assert done["cov"] == ["2026-09-21", "2026-10-03"]


def test_unreached_group_is_cut_even_today():
    """Второй край: понедельник настал, а колонку группы колледж ещё не
    дописал. Сегодняшний день по листу не режется, но группе он — «ещё не
    опубликовано», а не «сегодня пар нет»."""
    snap = _filling({**{d: 10 for d in WEEK}, **{d: 2 for d in NEXT}})
    monday = NEXT[0]
    body = schedule_payload(snap, "g-5", monday, 6, GENERATED, today=monday)
    assert body["cov"] == ["2026-09-21", "2026-09-26"]
    assert body["days"] == []


def test_free_days_of_a_filled_group_are_not_holes():
    """За краем листа у дописанной группы
    приходили только дни с парами, а свободный четверг между ними пропадал.
    Теперь её покрытие — до её последнего дня, и четверг — «пар нет»."""
    filling = {**{d: 10 for d in WEEK}, **{d: 2 for d in NEXT}}
    snap = _filling(filling)
    del snap.schedule["g-0"][NEXT[3]]
    body = schedule_payload(snap, "g-0", NEXT[0], 6, GENERATED, today=THURSDAY)
    assert body["cov"] == ["2026-09-21", "2026-10-03"]
    assert {"d": NEXT[3].isoformat(), "l": []} in body["days"]


def test_saturday_off_is_free_not_unpublished():
    """Группа, которая по субботам не учится: суббота выложенной недели —
    «пар нет». Край группы — её пятница, но остаток недели ей тоже выложен."""
    snap = _filling({d: 10 for d in WEEK})
    del snap.schedule["g-3"][WEEK[5]]
    body = schedule_payload(snap, "g-3", WEEK[0], 6, GENERATED, today=THURSDAY)
    assert body["cov"] == ["2026-09-21", "2026-09-26"]
    assert body["days"][-1] == {"d": "2026-09-26", "l": []}


def test_group_with_no_lessons_at_all_has_nothing_published():
    """Пустая колонка — колледж до группы не дошёл: ни `cov`, ни дней."""
    snap = _filling({d: 5 for d in WEEK})
    body = schedule_payload(snap, "g-7", WEEK[0], 6, GENERATED, today=THURSDAY)
    assert "cov" not in body
    assert body["days"] == []


def test_teacher_day_waits_for_all_his_groups():
    """Вписана одна группа преподавателя из его
    двух — у него был «понедельник с одной парой» как целый день. День
    преподавателя выложен, только когда дописаны все его группы."""
    from whensclass.api.payloads import teacher_payload
    from whensclass.domain.models import Lesson
    from whensclass.domain.teachers import build_index

    snap = _filling({**{d: 10 for d in WEEK}, **{d: 6 for d in NEXT}})
    for gid in ("g-0", "g-7"):
        for day in snap.schedule[gid]:
            snap.schedule[gid][day] = [
                Lesson(number=1 if gid == "g-0" else 2, subject="Физика", teachers=("Иванов И. И.",))
            ]
    index = build_index(snap)
    tid = next(iter(index.names))
    body = teacher_payload(snap, index, tid, WEEK[0], 13, GENERATED, today=THURSDAY)
    assert body["cov"] == ["2026-09-21", "2026-09-26"]
    assert [d["d"] for d in body["days"]] == [d.isoformat() for d in WEEK]

    # Оба дописаны — неделя его.
    for day in NEXT:
        snap.schedule["g-7"][day] = [Lesson(number=2, subject="Физика", teachers=("Иванов И. И.",))]
    index = build_index(snap)
    body = teacher_payload(snap, index, tid, WEEK[0], 13, GENERATED, today=THURSDAY)
    assert body["cov"] == ["2026-09-21", "2026-10-03"]


def test_group_on_practice_does_not_hide_teacher_days():
    """Группа ушла на практику — колонка пуста до конца листа. Её край резал
    всем её преподавателям дни после себя, включая сегодняшние пары в других
    группах (В15 прогона 1 аудита 4). Край — по группам, у которых он ведёт
    пары с понедельника этой недели; сегодня не режется никогда."""
    from whensclass.api.payloads import teacher_payload
    from whensclass.domain.models import Lesson
    from whensclass.domain.teachers import build_index

    snap = _filling({**{d: 10 for d in WEEK}, **{d: 10 for d in NEXT}})
    for gid in ("g-0", "g-7"):
        for day in snap.schedule[gid]:
            snap.schedule[gid][day] = [
                Lesson(number=1 if gid == "g-0" else 2, subject="Физика", teachers=("Иванов И. И.",))
            ]
    for day in NEXT:
        del snap.schedule["g-7"][day]  # g-7 на практике с 28.09
    index = build_index(snap)
    tid = next(iter(index.names))
    tuesday = NEXT[1]
    body = teacher_payload(snap, index, tid, NEXT[0], 6, GENERATED, today=tuesday)
    assert body["cov"] == ["2026-09-21", "2026-10-03"]
    today_ = next(d for d in body["days"] if d["d"] == tuesday.isoformat())
    assert [x["n"] for x in today_["l"]] == [1]


def test_today_and_past_are_never_cut():
    """Сегодня недописано — всё равно выложено: за краем сегодняшнего дня
    виджет сказал бы «не опубликовано» про идущие пары."""
    snap = _filling({**{d: 10 for d in WEEK[:3]}, **{d: 1 for d in WEEK[3:]}})
    body = schedule_payload(snap, "g-5", dt.date(2026, 9, 21), 6, GENERATED, today=THURSDAY)
    assert body["cov"] == ["2026-09-21", "2026-09-24"]


def test_without_today_coverage_is_the_whole_sheet(snapshot):
    """Без `today` покрытие прежнее — весь лист (золотой файл, старые вызовы)."""
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, START, 3, GENERATED)
    assert body["cov"] == [snapshot.dates[0].isoformat(), snapshot.dates[-1].isoformat()]
