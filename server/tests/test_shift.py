"""Детектор сдвига: обе стороны порогов — абсолютными числами.

Раньше сдвиги проходили при ok, а тесты держали детектор с одной стороны и
от его же констант:
все атаки были «сосед справа», окно считалось от SHIFT_RUN_REJECT, а честная
сторона не мерилась вовсе. Здесь пороги — числами, атаки — в обе стороны, а
честная сторона — на паре живых версий листа 18.09.2026 (17:00 и 21:20, в
21:20 впервые пришла неделя 21–26.09).
"""

import copy
import csv
import dataclasses
import datetime as dt
import gzip
import io

import pytest

from conftest import FIXTURES
from test_invariants import synthetic_sheet
from whensclass.domain.models import Lesson, SourceFormatChanged
from whensclass.parser.csv_schedule import (
    FIXTURE,
    check_shift,
    collapse_export,
    parse_export,
    parse_sheet,
    read_csv,
    shift_seed,
)

# Строки листа с неделей 21–26.09 в версии 18.09 21:20 (как в Sheets).
NEW_WEEK = (214, 285)
BLOCKS = list(range(2, 683, 4))


def shifted(honest, window, k, since=7):
    """Группы `window` (индексы по колонкам) с дня `since` получают пары группы через k."""
    out = copy.deepcopy(honest)
    ids = [g.id for g in honest.groups]
    for i in window:
        for day in honest.dates[since:]:
            out.schedule[ids[i]][day] = list(honest.schedule[ids[i + k]][day])
    return out


@pytest.mark.parametrize("k", [1, -1, 3, -4])
def test_history_rejects_six_groups_and_passes_five(k):
    """Голоса по истории: 5 групп подряд — честный разброс, 6 — сдвиг."""
    honest = synthetic_sheet()
    check_shift(shifted(honest, range(8, 13), k))
    side = "правее" if k > 0 else "левее"
    with pytest.raises(SourceFormatChanged, match=f"на {abs(k)} колонк\\w+ {side}"):
        check_shift(shifted(honest, range(8, 14), k))


def test_left_neighbour_is_the_block_insertion():
    """Вставка блока — самая частая правка колледжа — даёт группе пары соседа
    слева. До 26.09 в тестах её не было вовсе."""
    honest = synthetic_sheet()
    ids = [g.id for g in honest.groups]
    tail = shifted(honest, range(10, len(ids)), -1)
    with pytest.raises(SourceFormatChanged, match="левее"):
        check_shift(tail)


def test_previous_version_rejects_three_groups_and_passes_two():
    """Правка уже выложенной недели сверяется с прежней версией: окно в три
    группы — сдвиг, в две — ещё нет (честный максимум по архиву — одна)."""
    honest = synthetic_sheet()
    check_shift(shifted(honest, range(8, 10), 1), previous=honest)
    with pytest.raises(SourceFormatChanged, match="против прежней версии"):
        check_shift(shifted(honest, range(8, 11), 1), previous=honest)
    with pytest.raises(SourceFormatChanged, match="против прежней версии"):
        check_shift(shifted(honest, range(8, 11), -2), previous=honest)


def test_shared_lectures_of_subgroups_are_not_votes():
    """Подгруппы с общими парами: у соседа то же, что у своей — не голос ни
    по истории, ни против прежней версии."""
    honest = synthetic_sheet()
    for i in range(0, len(honest.groups) - 1, 2):
        a, b = honest.groups[i].id, honest.groups[i + 1].id
        for day in honest.dates:
            honest.schedule[b][day] = list(honest.schedule[a][day])
    check_shift(honest, previous=honest)


def _vertical(honest, gid, days, step=1):
    out = copy.deepcopy(honest)
    for day in days:
        out.schedule[gid][day] = [
            dataclasses.replace(x, number=x.number - step)
            for x in honest.schedule[gid][day]
            if x.number - step >= 1
        ]
    return out


def test_vertical_shift_on_three_days_is_rejected():
    """«удалить ячейки, сдвиг вверх» в блоке
    группы — её пары съезжают на номер до конца листа. Один-два дня —
    законная перестановка, три — сдвиг."""
    honest = synthetic_sheet()
    gid = honest.groups[4].id
    check_shift(_vertical(honest, gid, honest.dates[5:7]), previous=honest)
    with pytest.raises(SourceFormatChanged, match="по вертикали"):
        check_shift(_vertical(honest, gid, honest.dates[5:8]), previous=honest)


def test_teacher_names_in_place_of_subjects_are_a_row_shift():
    """Сдвиг на одну строку меняет местами строки пар и преподавателей:
    вместо названия — ФИО. Честно такого нет ни одной пары в день."""
    honest = synthetic_sheet()
    day = honest.dates[6]
    two = copy.deepcopy(honest)
    for g in honest.groups[:2]:
        first, *rest = two.schedule[g.id][day]
        two.schedule[g.id][day] = [
            dataclasses.replace(first, subject="Иванов Иван Иванович"), *rest
        ]
    check_shift(two)
    three = copy.deepcopy(two)
    first, *rest = three.schedule[honest.groups[2].id][day]
    three.schedule[honest.groups[2].id][day] = [
        dataclasses.replace(first, subject="Петрова А. С."), *rest
    ]
    with pytest.raises(SourceFormatChanged, match="вместо названия ФИО"):
        check_shift(three)


def test_cells_in_the_empty_columns_of_a_block_are_a_shift(fixture_csv):
    """Вставка ячеек не на ширину блока уводит
    предмет в пустые колонки +1/+2. Одна случайная ячейка там бывает, пять —
    нет."""
    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    lesson_rows = [i for i, r in enumerate(rows) if len(r) > 1 and r[1].strip().isdigit()]
    one = [list(r) for r in rows]
    one[lesson_rows[0]][3] = "з"
    parse_sheet(one, "фикстура", FIXTURE)
    for i in lesson_rows[:10]:
        rows[i][2:2] = [""]
    with pytest.raises(SourceFormatChanged, match="пустых колонок"):
        parse_sheet(rows, "фикстура", FIXTURE)


# --- Живой лист -------------------------------------------------------------

def _live(name: str) -> str:
    return gzip.decompress((FIXTURES / f"live-2026-09-18-{name}.csv.gz").read_bytes()).decode()


def _text(rows) -> str:
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    return buf.getvalue()


def _insert(text: str, first: int, last: int, column: int, width: int) -> str:
    """«Вставить ячейки, сдвиг вправо» на строках листа first..last."""
    rows = read_csv(text)
    for i in range(first - 1, last):
        rows[i][column:column] = [""] * width
    return _text(rows)


@pytest.fixture(scope="module")
def live():
    today = dt.date(2026, 9, 18)
    before = parse_export(_live("1700"), "лист", "656498718", around=today)
    after_text = _live("2120")
    after = parse_export(after_text, "лист", "656498718", around=today)
    return before, after, after_text, today


def test_live_versions_are_honest(live):
    """Честная сторона на живом листе: версия, в которой впервые пришла
    неделя 21–26.09, против прежней — не сдвиг."""
    before, after, _, _ = live
    check_shift(after, seed=shift_seed(before, after), previous=before)


def test_live_new_week_with_three_inserted_blocks_is_rejected(live):
    """Неделя пришла сразу со вставкой
    трёх блоков — 98 групп с чужими парами при ok. Прежней версии у этих дат
    нет, ловит история."""
    before, _, text, today = live
    with pytest.raises(SourceFormatChanged, match="по истории групп"):
        # Уже разбор зовёт ту же проверку — без прежней версии.
        bad = parse_export(
            _insert(text, *NEW_WEEK, BLOCKS[84], 12), "лист", "656498718", around=today
        )
        check_shift(bad, seed=shift_seed(before, bad), previous=before)


def test_live_edit_with_one_inserted_block_is_rejected(live):
    """Случай «а»: вставка одного блока у правого края на уже выложенной
    неделе — 18 групп. По истории — 4–5 голосов, против прежней версии — 15."""
    _, after, text, today = live
    bad = parse_export(
        _insert(text, *NEW_WEEK, BLOCKS[153], 4), "лист", "656498718", around=today
    )
    with pytest.raises(SourceFormatChanged, match="против прежней версии"):
        check_shift(bad, seed=shift_seed(after, bad), previous=after)


def test_live_one_cell_insertion_is_rejected_by_the_parse(live):
    """Вставка одной ячейки на строках одного дня — все группы получали
    номера аудиторий соседа вместо предметов."""
    _, _, text, today = live
    with pytest.raises(SourceFormatChanged, match="пустых колонок"):
        parse_export(_insert(text, 214, 225, 2, 1), "лист", "656498718", around=today)


def test_lesson_numbers_are_part_of_the_vertical_check():
    """Сверка по вертикали сравнивает пары без номера: у съехавшей он другой."""
    a = Lesson(number=3, subject="Физика", teachers=("Иванов И. И.",), room="272")
    from whensclass.parser.csv_schedule import _same_but_number

    assert _same_but_number(a, dataclasses.replace(a, number=2))
    assert not _same_but_number(a, dataclasses.replace(a, number=2, room="273"))


def test_vertical_shift_of_many_groups_on_one_day_is_rejected():
    """Сдвиг по вертикали только в последнем дне листа: у группы один день, но
    групп много. Честно — до четырёх групп за день, десять — сдвиг."""
    honest = synthetic_sheet()
    day = honest.dates[8:9]
    few, many = copy.deepcopy(honest), copy.deepcopy(honest)
    for g in honest.groups[:9]:
        few = _vertical(few, g.id, day)
    check_shift(few, previous=honest)
    for g in honest.groups[:10]:
        many = _vertical(many, g.id, day)
    with pytest.raises(SourceFormatChanged, match="у 10 групп"):
        check_shift(many, previous=honest)
