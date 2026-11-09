"""Правки разобранного вне самого обхода: копия недели внизу листа, имена
безымянным блокам, чьё ФИО у замены.
"""

from __future__ import annotations

import dataclasses
import logging
import re
from datetime import date, timedelta

from ..domain.ids import group_id
from ..domain.models import GroupRef, Lesson, Snapshot, SourceFormatChanged, a1_column
from .cells import replaced_of
from .dates import _parse_date
from .groups import _warn_once, build_column_map, find_header_rows, header_blocks
from .rows import _cell

log = logging.getLogger(__name__)


def _draft_tail(rows: list[list[str]]) -> int | None:
    """Начало черновика внизу листа: с первой повторной даты до конца — только
    даты, которые выше уже есть. Так колледж набирает неделю заново, не трогая
    выложенную; выше лежит то, что видят люди, его и читаем. Повтор посреди
    листа или новая дата после повтора — не черновик, а отказ, как раньше."""
    seen: set[date] = set()
    start = None
    for i, row in enumerate(rows):
        try:
            day = _parse_date(_cell(row, 0))
        except SourceFormatChanged:
            day = None
        if day is None:
            continue
        if day in seen:
            start = i if start is None else start
        elif start is not None:
            return None
        else:
            seen.add(day)
    return start


RETYPED_FULL = 0.8


def _retyped_order(rows: list[list[str]], start: int, min_groups: int) -> list[int] | None:
    """Порядок строк, в котором дни, набранные заново внизу листа, стоят на
    месте прежних; None — копия внизу ещё черновик.

    Колледж вставляет неделю заново целиком (05.11: за одну правку, все дни) и
    старую не стирает сразу; верна новая — она сходится с листом преподавателей.
    Черновик отличает полнота: внизу все дни недели, какие выше, и каждый не
    беднее `RETYPED_FULL` прежнего. Несколько дней блоками под листом — заготовка
    другой недели со старыми датами: правка в ней не для людей. Шапка между копиями — отказ от подмены: под ней
    могли смениться колонки групп, и новая копия читалась бы чужими."""
    def day_at(i: int) -> date | None:
        try:
            return _parse_date(_cell(rows[i], 0))
        except SourceFormatChanged:
            return None

    marks = [(i, d) for i in range(len(rows)) if (d := day_at(i)) is not None]
    tail = [(i, d) for i, d in marks if i >= start]
    days = {d for _, d in tail}
    old = [(i, d) for i, d in marks if i < start and d in days]
    if not old or len(tail) != len(days):
        return None
    lo = old[0][0]
    after = [i for i, d in marks if lo < i < start and d not in days]
    hi = after[0] if after else start
    if [d for i, d in old] != [d for _, d in tail] or any(i >= hi for i, _ in old):
        return None
    weeks = {d - timedelta(d.weekday()) for d in days}
    if any(d not in days for i, d in marks if i < start and d - timedelta(d.weekday()) in weeks):
        return None
    # Шапка ниже прежней недели, расставившая группы иначе, чем главная, —
    # отказ: копия встала бы под главную и читалась бы чужими колонками.
    # Согласная шапка (её колледж ставит перед новой неделей) не мешает.
    moved: dict[int, tuple[set[int], str]] = {}
    try:
        find_header_rows(rows, build_column_map(rows, min_groups), min_groups, distrust=moved)
    except SourceFormatChanged:
        return None
    if any(cols for i, (cols, _) in moved.items() if i >= lo):
        return None

    def fill(at: int) -> int:
        nxt = next((i for i, _ in marks if i > at), len(rows))
        return sum(1 for r in rows[at:nxt] for c in r[2:] if c.strip())

    if any(fill(n) < RETYPED_FULL * fill(o) for (o, _), (n, _) in zip(old, tail)):
        return None
    return list(range(lo)) + list(range(start, len(rows))) + list(range(hi, start))


def _topic(subject: str) -> str:
    """Первые два слова названия: «Иностранный язык .Английский ячзык» и
    «Иностранный язык, Английский» — один предмет."""
    return " ".join(re.findall(r"[а-яёa-z]+", subject.casefold())[:2])


def _settle_replacements(snapshot: Snapshot) -> None:
    """Одно ФИО у замены — чьё: заменённой пары или новой?

    Два ФИО колледж пишет как «прежний, новый», а одно бывает и тем, и другим.
    Судим по остальным ячейкам листа: если преподаватель ведёт заменённый
    предмет и не ведёт новый, это прежний — у новой пары его нет. Иначе в его
    расписании окажется чужая пара: тому, кто ведёт «Коммуникативный тренинг»,
    — «Кураторский час» вместо него.
    """
    teaches: dict[str, set[str]] = {}
    pending: list[tuple[list[Lesson], int, str]] = []
    for by_date in snapshot.schedule.values():
        for lessons in by_date.values():
            for i, lesson in enumerate(lessons):
                old = replaced_of(lesson)
                if old is None:
                    for name in lesson.teachers:
                        teaches.setdefault(name, set()).add(_topic(lesson.subject))
                elif len(lesson.teachers) == 1:
                    pending.append((lessons, i, old))
    for lessons, i, old in pending:
        lesson = lessons[i]
        known = teaches.get(lesson.teachers[0], set())
        if _topic(old) in known and _topic(lesson.subject) not in known:
            lessons[i] = dataclasses.replace(lesson, teachers=())


def _adopt_names(
    rows: list[list[str]], groups: list[GroupRef], adopt: dict[int, list[str]], min_groups: int
) -> tuple[list[GroupRef], dict[int, int]]:
    """Имена безымянным блокам главного заголовка — из повторного.

    Возвращает группы (с подобранными) и оставшиеся безымянные блоки со
    счётчиком пар, который набирает обход.
    """
    taken = {g.column for g in groups}
    known = {g.id for g in groups}
    blocks = header_blocks(rows, min_groups) - taken
    groups = list(groups)
    for col in sorted(adopt):
        if col not in blocks:
            # Главный заголовок про этот блок не знает вовсе — не с чего
            # подбирать: его ставят и законно, под будущую группу.
            _warn_once(
                ("gap", col, tuple(adopt[col])),
                "повторный заголовок: в колонке %s стоит %s, а в главном заголовке этой "
                "колонки нет и такого имени нет нигде — считаю блок безымянным",
                a1_column(col), adopt[col], logger=log,
            )
            continue
        for name in adopt[col]:
            gid = group_id(name)
            if gid in known:
                continue
            _warn_once(
                ("adopt", col, name),
                "в главном заголовке у колонки %s нет имени, а повторный называет её %r — "
                "беру имя оттуда", a1_column(col), name, logger=log,
            )
            known.add(gid)
            groups.append(GroupRef(name=name, id=gid, column=col))
        blocks.discard(col)
    groups.sort(key=lambda g: g.column)
    return groups, dict.fromkeys(blocks, 0)
