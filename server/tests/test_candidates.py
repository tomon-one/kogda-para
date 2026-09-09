"""Отбор кандидатов среди сотни листов книги.

В книге колледжа 104 листа: экзамены, пересдачи, аудитории, календарные
графики, прошлогоднее. Расписание групп ищется среди них по имени, и ошибка
здесь означает либо чужой лист вместо нашего, либо выгрузку впустую.

Скрытые листы — половина дела: колледж прячет прошедшее, и невидимый лист
не должен становиться кандидатом никогда.
"""

import datetime as dt

from whensclass.sources.gsheets import SheetInfo
from whensclass.sources.sheet_index import candidates

DAY = dt.date(2026, 9, 14)


def sheets(*rows):
    return [
        SheetInfo(title=title, gid=str(number), hidden=hidden)
        for number, (title, hidden) in enumerate(rows, 1)
    ]


def titles(picked):
    return [sheet.title for sheet in picked]


def test_hidden_sheets_never_become_candidates():
    """Скрытый лист колледж уже убрал с глаз — значит и для нас его нет."""
    book = sheets(
        ("расписание групп 14.-19.09", True),
        ("расписание групп 01.-05.09", False),
    )

    assert titles(candidates(book, DAY)) == ["расписание групп 01.-05.09"]


def test_sheets_that_are_not_schedules_are_dropped():
    book = sheets(
        ("Лист146", False),
        ("расписание групп 01.-05.09", False),
        ("2 курс", False),
    )

    assert titles(candidates(book, DAY)) == ["расписание групп 01.-05.09"]


def test_teachers_rooms_and_exams_are_not_ours():
    """Расписание есть и у преподавателей, и у аудиторий, и у экзаменов.

    Устроены они иначе, разбираются другим кодом — и попадать в кандидаты на
    расписание групп не должны, иначе каждый заход тратится на их выгрузку.
    """
    book = sheets(
        ("расписание преподавателей 01.-05.09", False),
        ("расписание аудиторий с 08-13.12", False),
        ("Расписание экзаменов", False),
        ("индивидуальные графики", False),
        ("расписание групп 01.-05.09", False),
    )

    assert titles(candidates(book, DAY)) == ["расписание групп 01.-05.09"]


def test_calendar_plan_stays_a_candidate():
    """Календарный график по имени похож на расписание — и это правильно.

    Отсеять его по имени нельзя: колледж вправе назвать недельный лист
    «графиком». Отвергается он уже разбором, и тревоги это не вызывает.
    """
    book = sheets(("Календарный график 2026-2027г.", False))

    assert titles(candidates(book, DAY)) == ["Календарный график 2026-2027г."]


def test_closest_by_numbers_goes_first():
    """Порядок — по близости чисел в имени к нужной дате.

    Имя листа врёт про даты, но не настолько, чтобы им пренебречь: перебор
    начинается с правдоподобного, и обычно первый же кандидат подходит.
    """
    book = sheets(
        ("расписание групп 01.-05.09", False),
        ("расписание групп 13-18.09", False),
        ("расписание групп 22-27.09", False),
    )

    assert titles(candidates(book, DAY))[0] == "расписание групп 13-18.09"


def test_name_without_numbers_is_tried_last_but_is_tried():
    """Заготовка «расписание групп» без дат в книге и правда лежит.

    Чисел в имени нет, встать в очередь ей не с чем — но выбрасывать её нельзя:
    новый недельный лист вполне может выйти под таким именем.
    """
    book = sheets(
        ("расписание групп", False),
        ("расписание групп 13-18.09", False),
    )
    picked = titles(candidates(book, DAY))

    assert picked[-1] == "расписание групп"
    assert len(picked) == 2
