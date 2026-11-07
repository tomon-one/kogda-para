"""Строки дня: пары блока, строка преподавателей, текст не на своём месте.

Всё, что читает строку пары и её соседей, без обхода листа целиком.
"""

from __future__ import annotations

import logging
import re
from datetime import date

from ..domain.models import GroupRef, Lesson, Snapshot, SourceFormatChanged, a1_column
from .cells import _HAS_LETTER, _ROOM_RE, _online_room, parse_lesson
from .groups import _warn_once
from .subgroups import split_subgroups

log = logging.getLogger(__name__)


# Ячейка с двумя числами через точку, дробь или дефис — это дата, которую
# не смогли прочитать, а не «нет даты»: иначе день молча приклеится к
# предыдущему.
_LOOKS_LIKE_DATE = re.compile(r"\d{1,2}\s*[.,/-]\s*\d{1,2}")


_LOOKS_LIKE_DATE = re.compile(r"\d{1,2}\s*[.,/-]\s*\d{1,2}")
_LESSON_NO_RE = re.compile(r"^([1-9])$")


_LESSON_NO_RE = re.compile(r"^([1-9])$")
# Строка под парой несёт время звонка в колонке номера: «9-00-10.30».
_TIME_RE = re.compile(r"^(\d{1,2})[.:-](\d{2})\s*[-–]\s*(\d{1,2})[.:-](\d{2})$")


_TIME_RE = re.compile(r"^(\d{1,2})[.:-](\d{2})\s*[-–]\s*(\d{1,2})[.:-](\d{2})$")
# Сдвиг не на целый блок («вставить 1–3 ячейки»): колонки +1 и +2 блока,
# всегда пустые, получают текст. Сдвиг строки задевает каждый блок правее
# вставки — у сдвига хоть на один день таких ячеек от 70. Текст в одном-двух
# блоках — запись колледжа, которую разбор не знает: день этих групп не
# прочитан, остальным лист принимается.
SPILL_BLOCKS = 3


SPILL_BLOCKS = 3
# Одна буква в пустой колонке — опечатка, а не сдвиг: за 350 версий листа
# такая была одна. Счётом, а не регуляркой: ^\W*\w?\W*$ на длинном ряду
# точек с буквами в конце перебирает за квадратичное время.
def _noise(text: str) -> bool:
    return sum(1 for ch in text if ch.isalnum() or ch == "_") <= 1


def _cell(row: list[str], col: int) -> str:
    return row[col] if col < len(row) else ""


def _lessons_at(row: list[str], teacher_row: list[str], col: int, number: int) -> list[Lesson]:
    """Пары блока, который начинается в колонке `col`."""
    out: list[Lesson] = []
    # Половинки — две пары с одним номером: каждая со своей аудиторией.
    halved = _halves(row, col, teacher_row)
    parts = [(col, col + 1), (col + 2, col + 3)] if halved else [(col, col + 3)]
    for subject_col, room_col in parts:
        cells = (
            _cell(row, subject_col),
            _cell(row, room_col),
            _cell(teacher_row, subject_col) if teacher_row else "",
        )
        for subject_raw, room_raw, teacher_raw in split_subgroups(*cells) or [cells]:
            lesson = parse_lesson(
                number=number, subject_raw=subject_raw, room_raw=room_raw,
                teacher_raw=teacher_raw,
            )
            if lesson is not None:
                out.append(lesson)
    return out


def _blank_day(rows: list[list[str]], start: int) -> range | None:
    """Строки дня с датой в `start` до следующей даты, если в них, кроме
    номеров и времени, ничего нет; иначе None."""
    end = start + 1
    while end < len(rows) and not _LOOKS_LIKE_DATE.search(_cell(rows[end], 0)):
        end += 1
    if any(c.strip() for row in rows[start:end] for c in row[2:]):
        return None
    return range(start, end)


def _halves(row: list[str], col: int, teacher_row: list[str]) -> bool:
    """Блок поделён пополам: две пары рядом, у каждой своё название.

    Так колледж пишет подгруппы с разными языками: «немецкий» и «55/1» слева,
    «английский» и «467» справа, преподаватели — под каждым названием. От
    вставки ячеек отличает то, что в +2 название, а не номер аудитории соседа,
    и в +3 есть аудитория: вставка на две ячейки оставляет там пусто. Аудитории
    справа ещё может не быть — тогда половинки узнаются по названию слева и
    ФИО под правым: у вставки +0 пуст.

    Половинка бывает и одна: правая (слева пусто) или левая — название в +0,
    аудитория в +1, справа пусто и в строке преподавателей тоже. У вставки на
    одну ячейку наоборот: в +0 аудитория соседа, название — в +1.
    """
    left = _cell(row, col).strip()
    right = _cell(row, col + 2).strip()
    if _ROOM_RE.match(left):
        return False
    if not right:
        return bool(
            _HAS_LETTER.search(left)
            and _ROOM_RE.match(_cell(row, col + 1).strip())
            and not _cell(row, col + 3).strip()
            and not _cell(teacher_row, col + 2).strip()
        )
    if not _HAS_LETTER.search(right) or _ROOM_RE.match(right):
        return False
    if _cell(row, col + 3).strip():
        return True
    return bool(_HAS_LETTER.search(left) and _HAS_LETTER.search(_cell(teacher_row, col + 2)))


def _leftover(rows: list[list[str]], i: int, col: int, text: str) -> bool:
    """Одна аудитория («онлайн», «255») в пустой колонке блока, у которого ни в
    строке пары, ни в строке преподавателей рядом нет предмета, — остаток
    удалённой пары. Пары из неё не собрать, и вставка ячеек так не выглядит:
    та уводит из колонки предмета сам предмет."""
    if _online_room(text) is None and not _ROOM_RE.match(text):
        return False
    return not any(_cell(rows[j], col).strip() for j in (i - 1, i, i + 1) if 0 <= j < len(rows))


def _spill(
    rows: list[list[str]], groups: list[GroupRef], skip: set[int], where
) -> list[tuple[int, int, int]]:
    """Текст в пустых колонках блоков (+1, +2): (строка, колонка блока, ячейка).

    Так выглядит «вставить ячейки» не на ширину блока: предмет уезжает в +1
    или +2, а аудитория соседа — в колонку предмета. Без проверки это прошло
    бы при ok: на две ячейки — «пар нет» у всех групп, на одну — номера
    аудиторий вместо предметов. Задеты SPILL_BLOCKS блоков и больше — отказ
    листа. Блоки, поделённые пополам ([_halves]), и их строки преподавателей
    не в счёт.
    """
    columns = sorted({g.column for g in groups})
    halved: set[tuple[int, int]] = set()
    for i, row in enumerate(rows):
        if i in skip or not _LESSON_NO_RE.match(_cell(row, 1).strip()):
            continue
        teacher_row = _teacher_row(rows, i, skip, where)
        cols = [col for col in columns if _halves(row, col, teacher_row)]
        if not cols:
            continue
        teacher_i = next((j for j in range(i + 1, len(rows)) if rows[j] is teacher_row), None)
        for col in cols:
            halved.add((i, col))
            if teacher_i is not None:
                halved.add((teacher_i, col))
    found: list[tuple[int, int, int]] = []
    for i, row in enumerate(rows):
        if i in skip:
            continue
        for col in columns:
            if (i, col) in halved:
                continue
            for extra in (1, 2):
                text = _cell(row, col + extra).strip()
                if text and not _noise(text) and not _leftover(rows, i, col, text):
                    found.append((i, col, col + extra))
    if len({col for _, col, _ in found}) >= SPILL_BLOCKS:
        i, _, col = found[0]
        raise SourceFormatChanged(
            f"в {len(found)} ячейках пустых колонок блоков есть текст, первая — "
            f"{where(i)}, колонка {a1_column(col)}: похоже на вставку ячеек не на ширину блока"
        )
    return found


def _mark_unread(
    snapshot: Snapshot, spill: list[tuple[int, int, int]], row_date: dict[int, date], where
) -> None:
    """День группы с лишним текстом в блоке — не прочитан: его пары прочь.

    Что в них, разбор не знает: половинка без аудитории, сдвиг у края строки.
    Служба отдаст по этому дню прежнее и скажет владельцу.
    """
    first: dict[tuple[int, date], tuple[int, int]] = {}
    for i, block, col in spill:
        day = row_date.get(i)
        if day is None:
            raise SourceFormatChanged(
                f"текст в пустой колонке блока раньше первой даты: {where(i)}, "
                f"колонка {a1_column(col)}"
            )
        first.setdefault((block, day), (i, col))
    for (block, day), (i, col) in sorted(first.items(), key=lambda item: item[1]):
        names = [g for g in snapshot.groups if g.column == block]
        for group in names:
            snapshot.schedule.get(group.id, {}).pop(day, None)
            snapshot.unread.setdefault(group.id, {})[day] = False
        snapshot.unread_why.append(
            f"{', '.join(g.name for g in names)} {day:%d.%m}: текст в пустой колонке "
            f"блока, {where(i)}, колонка {a1_column(col)}"
        )


def _teacher_row(rows: list[list[str]], i: int, skip: set[int], where) -> list[str]:
    """Строка преподавателей пары в строке `i`.

    Это строка под парой со временем звонка в колонке номеров, а не просто
    следующая: между ними бывает вставлена строка с припиской («перенос с
    23.09» у одной группы), и она отняла бы преподавателей у всей строки пары.
    Строки без номера и времени между ними пропускаются. Времени нет вовсе —
    берём следующую.
    """
    between: list[int] = []
    j = i + 1
    while j < len(rows) and j not in skip:
        marker = _cell(rows[j], 1).strip()
        if _LESSON_NO_RE.match(marker):
            break
        if _TIME_RE.match(marker):
            if between:
                _warn_once(
                    ("между", where(between[0])),
                    "между строкой пары и строкой времени в %s лишняя строка — пропускаю",
                    where(between[0]), logger=log,
                )
            return rows[j]
        between.append(j)
        j += 1
    return rows[between[0]] if between else []
