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


def test_незнакомое_длинное_сокращается_само_короткое_нет():
    # Словаря на все отделения не хватит: длинное сокращаем первыми словами,
    # короткое оставляем как есть.
    assert short_name("Теория вероятностей и математическая статистика") == "Теория вероятностей"
    assert short_name("Учебная графика") is None
    assert short_name("Математика") is None


def test_пометки_таблицы_убираются():
    # «ЗАЧЕТ» и пояснения в ячейке — не часть названия.
    assert short_name("Экономика организации ЗАЧЕТ") == "Экономика организации"
    assert short_name("Обществознание Зачет") == "Обществознание"
    assert short_name("Биология преподаватель на онлайн, студенты в 467 кабинете") == "Биология"


def test_профессия_в_кавычках_остаётся_одна():
    assert short_name('Технология выполнения работ по профессии "Графический дизайнер"') == "Графический дизайнер"
    assert short_name('Технология выполнения работ в должности "Вожатый"') == "Вожатый"


def test_опечатки_колледжа_сводятся_к_тому_же_короткому():
    # В таблице их много: сравниваем по близости строк, а не по буквам.
    assert short_name("Производственная прнактика") == "практика"
    assert short_name("Основы безопасности и зашиты Родины") == "ОБЗР"
    assert short_name("Безопасность жизнедеятелности") == "БЖД"
    assert short_name("Основы алгоритмизации и програмирования") == "алгоритмы"
    assert short_name("Теория государсва и права") == "ТГП"


def test_приписка_после_названия_не_мешает():
    # «Основы материаловедения Замкна Компьютерная графика (Пр)» — склеенная
    # ячейка таблицы: предмет тот, что в начале.
    assert short_name("Основы материаловедения Замкна Компьютерная графика (Пр)") == "материаловедение (Пр)"


def test_короткие_имена_не_склеиваются_по_похожести():
    # «Учебная графика» — не «Учебная практика»: близость ниже порога.
    assert short_name("Учебная графика") is None
    assert short_name("Дизайн упаковки") is None


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
