"""Карта колонок таблицы и поиск строк-заголовков.

Лист устроен блоками по четыре колонки на группу:
    col   — предмет (в строке пары) и ФИО преподавателя (в строке под ней)
    col+1, col+2 — пустые
    col+3 — аудитория, ссылка на вебинар или пометка отмены

Заголовок встречается не только в первой строке: внутри листа попадается
повторный, набранный «столбиком» (Дисциплина / Преподаватель / имя группы).
"""

from __future__ import annotations

import logging
import re
from collections.abc import Callable

from ..domain.ids import group_id
from ..domain.models import GroupRef, SourceFormatChanged, a1_column

log = logging.getLogger(__name__)

_HEADER_PREFIX = "Дисциплина Преподаватель "
# Латинские буквы, неотличимые от кириллицы на глаз: «Дисциплинa» с «a».
_LOOKALIKE = str.maketrans("aceopxykABCEHKMOPTXY", "асеорхукАВСЕНКМОРТХУ")
_ROOM_MARK = "Ауд."
_BLOCK_WIDTH = 4
_SPLIT_RE = re.compile(r"\s+и\s+")

MIN_GROUPS = 100


def split_group_names(tail: str) -> list[str]:
    """'ДП-923 и ДП-1124' -> обе группы: одна колонка обслуживает две.

    Имя, из которого не выходит идентификатора («-», «?», «…»), — это не
    группа, а незаполненная ячейка: колонка считается безымянной, а не роняет
    разбор ValueError.
    """
    parts = [p.strip() for p in _SPLIT_RE.split(tail.strip())]
    return [p for p in parts if p and _has_id(p)]


def _has_id(name: str) -> bool:
    try:
        group_id(name)
    except ValueError:
        # Раз на процесс: это состояние листа, а не событие.
        _warn_once(
            ("без id", name), "вместо имени группы %r — считаю колонку безымянной", name
        )
        return False
    return True


def _distance(a: str, b: str) -> int:
    """Расстояние правки — для коротких слов, построчно."""
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        prev, row[0] = row[0], i
        for j, y in enumerate(b, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (x != y))
    return row[-1]


def _near(word: str, target: str) -> bool:
    """Слово шапки с опечаткой — то же слово: до двух правок, регистр и
    латинские двойники не в счёт."""
    if abs(len(word) - len(target)) > 2:
        return False
    word = word.translate(_LOOKALIKE).casefold()
    return word == target or _distance(word, target) <= 2


def _block_head(text: str) -> tuple[bool, str | None]:
    """Ячейка главного заголовка: (шапка ли блока, хвост с именами или None).

    «Дисциплина Преподаватель ИСП-924/1». Опечатка в слове шапки — «Дисциплна»,
    «дисциплина», «Дисциплины», латинская «a», «Преподаватели» — та же шапка:
    иначе группа молча уйдёт в 404 при ok.
    """
    parts = text.split(None, 2)
    if not parts or not _near(parts[0], "дисциплина"):
        return False, None
    if len(parts) < 3 or not _near(parts[1], "преподаватель"):
        return True, None
    if parts[0] != "Дисциплина" or not parts[1].startswith("Преподавател"):
        _warn_once(("шапка", parts[0], parts[1]),
                   "в главном заголовке «%s %s» — считаю шапкой блока", parts[0], parts[1])
    return True, parts[2]


def _row_columns(row: list[str]) -> dict[int, list[str]]:
    """Колонка -> имена групп, объявленные в этой строке."""
    found: dict[int, list[str]] = {}
    for col, cell in enumerate(row):
        _, tail = _block_head((cell or "").replace("\xa0", " ").strip())
        if tail:
            names = split_group_names(tail)
            if names:
                found[col] = names
    return found


def header_blocks(rows: list[list[str]], min_groups: int = MIN_GROUPS) -> set[int]:
    """Колонки главного заголовка, где начинается блок, — с именем и без.

    Блок без имени («Дисциплина Преподаватель», «… —») бывает и законным —
    колонка под будущую группу, — и сломанным: имя стёрли или опечатались.
    Отличает их то, есть ли под ним пары (`Snapshot.unnamed`).
    """
    for row in rows:
        if len(_row_columns(row)) >= min_groups:
            cells = [(cell or "").replace("\xa0", " ").strip() for cell in row]
            heads = {col for col, cell in enumerate(cells) if _block_head(cell)[0]}
            # Ячейку шапки стёрли или испортили сильнее двух правок («Дисц.»,
            # другое слово), а колонка аудитории блока на месте: блок есть, без
            # шапки. Место — на шаге блоков, не внутри соседнего.
            if heads:
                for col in range(min(heads) % _BLOCK_WIDTH, len(cells) - 3, _BLOCK_WIDTH):
                    if cells[col + 3].casefold().startswith("ауд") and \
                            all(abs(col - head) >= _BLOCK_WIDTH for head in heads):
                        heads.add(col)
            return heads
    return set()


def build_column_map(rows: list[list[str]], min_groups: int = MIN_GROUPS) -> list[GroupRef]:
    """Собирает список групп из главного заголовка листа.

    Бросает SourceFormatChanged, если блоки перестали идти с шагом 4 или
    исчезла колонка аудитории: гадать о том, чьи это пары, нельзя.
    """
    for row in rows:
        columns = _row_columns(row)
        if len(columns) >= min_groups:
            break
    else:
        raise SourceFormatChanged(
            f"не нашёл строку заголовка минимум с {min_groups} группами"
        )

    starts = sorted(columns)
    steps = {b - a for a, b in zip(starts, starts[1:])}
    if any(step % _BLOCK_WIDTH for step in steps):
        # Шаг 3 или 5 — вставили или удалили колонку, карта поехала.
        raise SourceFormatChanged(
            f"блоки групп идут с шагом {sorted(steps)}, ожидался {_BLOCK_WIDTH}"
        )
    if steps != {_BLOCK_WIDTH}:
        # Шаг 8 — блок без имени группы: пропуск, не сдвиг. Колонка
        # аудитории соседа остаётся на своём месте.
        _warn_once(
            ("шаги", tuple(sorted(steps))), "между блоками групп есть пропуски: шаги %s",
            sorted(steps),
        )

    for col in starts:
        mark = (row[col + 3] if col + 3 < len(row) else "").strip()
        if not mark.startswith(_ROOM_MARK):
            if mark.casefold().startswith("ауд"):
                # «Ауд», «ауд.», «АУД.» — то же слово, набранное иначе.
                _warn_once(("ауд", col, mark), "в колонке %s «%s» вместо «%s»",
                           a1_column(col + 3), mark, _ROOM_MARK)
                continue
            raise SourceFormatChanged(
                f"в колонке {a1_column(col + 3)} ожидалась «{_ROOM_MARK}», а там {mark!r}"
            )

    # Имя в двух колонках — опечатка в одной из них («ИСП-924/1» над
    # ИСП-924/2), а в какой, главный заголовок не скажет. Отвергать лист у всех
    # незачем: обе колонки остаются без имени, имена им даёт повторный
    # заголовок (`parse_sheet`), а нет его — пропажу группы с парами ловит
    # служба (`_check_lost_names`).
    where: dict[str, list[int]] = {}
    for col in starts:
        for name in columns[col]:
            where.setdefault(group_id(name), []).append(col)
    groups: list[GroupRef] = []
    for col in starts:
        for name in columns[col]:
            gid = group_id(name)
            if len(where[gid]) > 1:
                _warn_once(
                    ("дважды", gid, tuple(where[gid])),
                    "группа %r объявлена в главном заголовке дважды — в колонках %s; "
                    "обе считаю безымянными", name, where[gid],
                )
                continue
            groups.append(GroupRef(name=name, id=gid, column=col))
    return groups


def find_header_rows(
    rows: list[list[str]],
    groups: list[GroupRef],
    min_groups: int = MIN_GROUPS,
    where: Callable[[int], str] = lambda i: f"строке {i}",
    adopt: dict[int, list[str]] | None = None,
) -> set[int]:
    """Индексы строк, которые надо пропустить при обходе.

    Однострочные заголовки узнаём по префиксу «Дисциплина Преподаватель»,
    трёхстрочные — по ячейке ровно «Дисциплина» с «Преподаватель» под ней.
    Если повторный заголовок объявляет другую раскладку колонок, считаем
    формат изменившимся: показать чужое расписание хуже, чем упасть.

    В `adopt` складываются имена, которые повторный заголовок даёт колонке,
    безымянной в главном: их подбирает `parse_sheet`.
    """
    # Раскладка — по главному заголовку, а не по группам: колонка с именем,
    # повторённым в двух местах, группой не стала (`build_column_map`), но
    # в заголовке стоит.
    expected: set[int] | None = None
    skip: set[int] = set()

    for i, row in enumerate(rows):
        columns = _row_columns(row)
        if columns:
            skip.add(i)
            if len(columns) >= min_groups:
                if expected is None:
                    expected = set(columns)
                elif set(columns) != expected:
                    raise SourceFormatChanged(
                        f"повторный заголовок в {where(i)} задаёт другие колонки"
                    )
            continue

        cells = {c for c, v in enumerate(row) if (v or "").strip() == "Дисциплина"}
        if len(cells) < min_groups:
            # «Дисциплина» / «Преподаватель» в клетке одной группы — заготовка
            # шаблона, а не заголовок, и выкидывать три строки у всех групп
            # нельзя. Такую клетку разбор ячеек считает пустой.
            continue
        below = rows[i + 1] if i + 1 < len(rows) else []
        if any(
            (below[c] or "").strip() == "Преподаватель"
            for c in cells
            if c < len(below)
        ):
            # Заголовок «столбиком»: Дисциплина / Преподаватель / имя группы.
            _check_columnar(
                where(i), cells, rows[i + 2] if i + 2 < len(rows) else [], groups, adopt
            )
            skip.update({i, i + 1, i + 2})

    return skip


# Сколько имён повторного заголовка должны стоять не в своих колонках, чтобы
# это был сдвиг. Сдвиг переставляет все имена правее вставки, а одно чужое
# имя — опечатка: «ИСП-924/1» вместо «ИСП-924/2».
COLUMNAR_STRANGERS_TO_REJECT = 2

# Что уже сказано в журнал: терпимая неувязка листа — это состояние, а не
# событие, и на каждом разборе она дала бы десятки строк в день.
_warned: set[tuple] = set()


def _warn_once(key: tuple, message: str, *args, logger: logging.Logger | None = None) -> None:
    """Предупреждение раз на процесс; `logger` — журнал модуля, откуда оно."""
    if key in _warned:
        return
    _warned.add(key)
    (logger or log).warning(message, *args)


def _check_columnar(
    where: str,
    cells: set[int],
    names_row: list[str],
    groups: list[GroupRef],
    adopt: dict[int, list[str]] | None = None,
) -> None:
    """Сверяет колонки повторного заголовка с главным.

    Если ниже повторного заголовка добавить хоть одну группу, весь хвост листа
    съезжает на блок — и разбор проходит без единой ошибки, просто каждая
    группа получает расписание соседа. Это худшее, что может случиться:
    приложение уверенно показывает чужие пары при статусе «ok».

    Поэтому имена групп из третьей строки заголовка сверяются с картой колонок.
    Сдвиг узнаётся по именам, которые по главному заголовку живут в других
    колонках, — и не по одному (`COLUMNAR_STRANGERS_TO_REJECT`). Тогда считаем
    формат изменившимся: упасть и остаться на прежнем снимке лучше, чем
    отправить человека не в ту аудиторию.

    А имя, которого нет ни в одной колонке главного заголовка, — не сдвиг, а
    переименование: 11 сентября 2026 колледж поправил имя одной группы в
    главном заголовке и не тронул его в двух повторных. Колонка та же, пары под
    ней те же: пишем в журнал и верим главному заголовку. Колонке, у которой в
    главном заголовке имени нет (стёрли, «—»), имя даёт повторный заголовок
    (`adopt`), если её имени там нет нигде: отвергать из-за одной ячейки весь
    лист нельзя, а считать блок безымянным — значит молча потерять группу.
    """
    by_column: dict[int, set[str]] = {}
    column_of: dict[str, int] = {}
    for group in groups:
        by_column.setdefault(group.column, set()).add(group.id)
        column_of[group.id] = group.column

    strangers: list[tuple[int, list[str], str, set[str] | None]] = []
    for col in sorted(cells):
        declared = split_group_names((names_row[col] if col < len(names_row) else "") or "")
        if not declared:
            # Имя не написали — сверять нечего, это не повод падать.
            continue
        ids = {group_id(name) for name in declared}
        known = by_column.get(col)
        elsewhere = sorted(gid for gid in ids if gid in column_of and column_of[gid] != col)
        if elsewhere:
            strangers.append((col, declared, elsewhere[0], known))
            continue
        if known is None:
            # Имя стёрли или испортили в главном заголовке, а здесь оно цело:
            # колонка та же, берём имя отсюда.
            if adopt is not None:
                adopt.setdefault(col, declared)
            continue
        if ids == known:
            continue
        _warn_once(
            ("rename", col, tuple(declared)),
            "повторный заголовок: в колонке %s стоит %s, а по главному заголовку там "
            "%s — такого имени нет больше нигде, считаю переименованием и верю "
            "главному заголовку",
            a1_column(col), declared, sorted(known),
        )

    if len(strangers) >= COLUMNAR_STRANGERS_TO_REJECT:
        col, declared, gid, known = strangers[0]
        here = f"здесь же {sorted(known)}" if known else "здесь в главном заголовке пусто"
        raise SourceFormatChanged(
            f"повторный заголовок в {where}: в колонке {a1_column(col)} стоит {declared}, "
            f"а по главному заголовку {gid!r} живёт в колонке {a1_column(column_of[gid])}, "
            f"{here}; "
            f"таких колонок {len(strangers)}"
        )
    for col, declared, gid, known in strangers:
        _warn_once(
            ("stranger", col, tuple(declared)),
            "повторный заголовок: в колонке %s стоит %s, а по главному заголовку %r "
            "живёт в колонке %s — одно такое имя считаю опечаткой, не сдвигом",
            a1_column(col), declared, gid, a1_column(column_of[gid]),
        )
