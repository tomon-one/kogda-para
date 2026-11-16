"""Короткие названия предметов: словарь, хвосты и поле `sn` в ответе."""

import datetime as dt
import json

from whensclass.api.payloads import schedule_payload
from whensclass.domain.subjects import short_name, split_suffix
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.parser.export import parse_csv

GENERATED = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.UTC)


def test_словарь_знает_частые_предметы():
    assert short_name("Основы алгоритмизации и программирования") == "алгоритмы"
    assert short_name("Физическая культура") == "физра"
    assert short_name("Безопасность жизнедеятельности") == "БЖД"


def test_регистр_и_ё_не_важны():
    assert short_name("ОСНОВЫ АЛГОРИТМИЗАЦИИ И ПРОГРАММИРОВАНИЯ") == "алгоритмы"
    assert short_name("  физическая   культура ") == "физра"


def test_хвост_типа_занятия_остаётся():
    assert split_suffix("Информатика (Пр)") == ("Информатика", "(Пр)")
    assert short_name("Физическая культура (Спт)") == "физра (Спт)"


def test_незнакомый_предмет_короткого_имени_не_получает():
    # Лучше полное, чем выдуманное.
    assert short_name("Теория вероятностей и математическая статистика") is None
    assert short_name("История ЗАЧЕТ") is None


def test_в_паре_появляется_sn_рядом_с_полным(fixture_csv):
    snapshot = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, dt.date(2026, 9, 7), 3, GENERATED)
    lessons = [lesson for day in body["days"] for lesson in day["l"]]
    long = next(x for x in lessons if x["s"] == "Основы алгоритмизации и программирования")
    assert long["sn"] == "алгоритмы"
    # Полное имя остаётся: старые клиенты читают его как раньше.
    assert long["s"] == "Основы алгоритмизации и программирования"
    # У предметов без словаря поля нет — пустых полей в ответе не бывает.
    assert all("sn" not in x for x in lessons if x["s"] == "Физика")


def test_старые_поля_не_изменились(fixture_csv):
    """Добавка `sn` — только добавка: без него ответ прежний."""
    snapshot = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, dt.date(2026, 9, 7), 3, GENERATED)
    for day in body["days"]:
        for lesson in day["l"]:
            assert {"n", "s"} <= set(lesson)
            # Ключи короткие: ответ едет на телефон при каждом обновлении.
            assert set(lesson) <= {"n", "s", "sn", "k", "t", "r", "u", "x", "c", "o"}
    json.dumps(body, ensure_ascii=False)
