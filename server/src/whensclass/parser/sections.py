"""Дни под повторным заголовком, который расставил группы иначе, чем главный.

Такой день разбирается дважды — по главному заголовку и по повторному, — а
какой разбор верен, решает содержимое: группы получают свои предметы.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from datetime import date

from ..domain.ids import group_id
from ..domain.models import GroupRef, Lesson, Snapshot
from .groups import HEAD_WORDS, SUBGROUP_RE, _warn_once, split_group_names
from .rows import _cell
from .settle import _topic

log = logging.getLogger(__name__)


@dataclass
class _Doubt:
    """День под повторным заголовком, который расставил группы иначе, чем главный."""

    # Группа -> её колонка по повторному заголовку; группы там нет — нет и здесь.
    columns: dict[str, int]
    why: str
    # Подгруппы, на которые заголовок разделил группу: id -> (имя, колонка).
    new: dict[str, tuple[str, int]] = field(default_factory=dict)
    by_main: dict[str, list[Lesson]] = field(default_factory=dict)
    by_section: dict[str, list[Lesson]] = field(default_factory=dict)
    by_new: dict[str, list[Lesson]] = field(default_factory=dict)


def _numbers(gid: str) -> tuple[str, ...]:
    """Числа в имени группы: курс, год набора, подгруппа."""
    return tuple(re.findall(r"\d+", gid))


def _section_columns(
    rows: list[list[str]], i: int, names_i: int, groups: list[GroupRef]
) -> tuple[dict[str, int], dict[str, tuple[str, int]]]:
    """Где какая группа по повторному заголовку в строке `i` (имена — в `names_i`),
    и подгруппы, на которые он разделил группу: id -> (имя, колонка).

    Имя, которого главный заголовок не знает («СИС(а)-926/1» у «СИС-926/1»), —
    прежнее имя группы: её место видно по соседям. Если слева и справа группы
    стоят с одним и тем же шагом от своих колонок, с тем же шагом стоит и она —
    когда номера в именах те же. Иначе это другая группа: колонку вырезали во
    всём листе, и в старом шаблоне ушла не та группа, что в главном заголовке,
    а на её месте осталось имя ушедшей из главного. Пары под ней — не наши.

    Шаг не сошёлся, а имя — «группа/N» той группы, которой в заголовке нет, —
    колледж разделил группу на подгруппы («С-926» на «С-926/1» и «С-926/2»):
    их пары — под их именами. Другие незнакомые имена так не читаются: старое
    имя из чужого шаблона дало бы группу-двойника.
    """
    names_row = rows[names_i]
    main_col = {g.id: g.column for g in groups}
    at_main: dict[int, list[str]] = {}
    for g in groups:
        at_main.setdefault(g.column, []).append(g.id)
    heads = sorted(c for c, v in enumerate(rows[i]) if (v or "").strip() in HEAD_WORDS)
    placed: dict[str, int] = {}
    step: dict[int, int] = {}
    unknown: list[int] = []
    for col in heads:
        ids = [group_id(name) for name in split_group_names(_cell(names_row, col))]
        known = [gid for gid in ids if gid in main_col]
        if known:
            for gid in known:
                placed.setdefault(gid, col)
            step[col] = col - main_col[known[0]]
        elif ids:
            unknown.append(col)
    unplaced: list[int] = []
    for col in unknown:
        left = next((step[c] for c in reversed(heads[: heads.index(col)]) if c in step), None)
        right = next((step[c] for c in heads[heads.index(col) + 1:] if c in step), None)
        if left is None or left != right:
            unplaced.append(col)
            continue
        numbers = {_numbers(gid) for gid in map(group_id, split_group_names(_cell(names_row, col)))}
        same = [gid for gid in at_main.get(col - left, []) if _numbers(gid) in numbers]
        if not same:
            unplaced.append(col)
        for gid in same:
            placed.setdefault(gid, col)
    new: dict[str, tuple[str, int]] = {}
    for col in unplaced:
        for name in split_group_names(_cell(names_row, col)):
            m = SUBGROUP_RE.fullmatch(name)
            if not m:
                continue
            try:
                base = group_id(m.group(1))
            except ValueError:
                continue
            gid = group_id(name)
            if base in main_col and base not in placed and gid not in main_col:
                new.setdefault(gid, (name, col))
    return placed, new


# Запасной способ верит разбору дня, только если предметы в основном знакомы
# группам (по дням выше заголовка), а у другого разбора — заметно реже. Со
# временем в неделях появляются новые предметы и практика: у верного разбора
# доля падает к 0,9, у неверного не поднимается выше 0,55.
SETTLE_MIN_LESSONS = 40


SETTLE_MIN_LESSONS = 40
SETTLE_KNOWN = 0.8


SETTLE_KNOWN = 0.8
SETTLE_GAP = 0.2


def _settle_moved(
    snapshot: Snapshot, doubt: dict[date, _Doubt], previous: Snapshot | None = None
) -> None:
    """Дни под повторным заголовком с другой раскладкой: чьи в них пары.

    Основной способ — главный заголовок — здесь не годится: повторный с ним
    спорит. Запасной: день разобран обоими способами, и верен тот, где группы
    получили свои предметы — те, что у них были в днях выше заголовка. Сдвиг
    блока даёт группе предметы соседа, и на сотне групп разница видна сразу.
    Неделя, вставленная со сдвигом, кончается без заголовка: дальше дни снова
    стоят по главному. Поэтому решение — по каждому дню, а после первого дня
    по главному заголовку повторному больше не верим. Не решилось — день не
    прочитан, как и у группы, которой в повторном заголовке нет.

    Новый лист над заголовком может держать всего неделю — на ней доля своих
    предметов порога не наберёт. Тогда в «свои» идут и предметы прежнего
    снимка, только до спорных дней: спорные он сам решал этим же способом.
    """
    if not doubt:
        return
    first = min(doubt)
    known: dict[str, set[str]] = {}
    for source in (previous, snapshot):
        if source is None:
            continue
        for gid, by_date in source.schedule.items():
            known.setdefault(gid, set()).update(
                _topic(x.subject) for day, lessons in by_date.items()
                if source is snapshot or day < first for x in lessons
            )

    def share(parsed: dict[str, list[Lesson]]) -> tuple[int, float]:
        total = sum(len(lessons) for lessons in parsed.values())
        hits = sum(
            1 for gid, lessons in parsed.items() for x in lessons
            if _topic(x.subject) in known.get(gid, ())
        )
        return total, (hits / total if total else 0.0)

    back_to_main = False
    added: dict[str, GroupRef] = {}
    own: dict[str, set[date]] = {}
    read: dict[str, list[date]] = {"section": [], "main": [], "": []}
    whys: dict[str, None] = {}
    absent: dict[tuple[str, str], list[date]] = {}
    names = {g.id: g.name for g in snapshot.groups}
    column_of = {g.id: g.column for g in snapshot.groups}
    for day in sorted(doubt):
        d = doubt[day]
        n_main, main = share(d.by_main)
        n_sect, sect = share(d.by_section)
        others = any(by.get(day) for gid, by in snapshot.schedule.items() if gid not in d.by_main)
        if not n_main and not n_sect and not others and not any(d.by_new.values()):
            # В строках дня пусто при любом чтении: спорить не о чем, пар нет.
            # Пусто только у спорных групп — другое: их колонки могли уехать.
            for gid in d.by_main:
                snapshot.schedule[gid].pop(day, None)
            continue
        verdict = ""
        if n_main >= SETTLE_MIN_LESSONS and main >= SETTLE_KNOWN and main - sect >= SETTLE_GAP:
            verdict = "main"
        elif (
            n_sect >= SETTLE_MIN_LESSONS and sect >= SETTLE_KNOWN and sect - main >= SETTLE_GAP
            and not back_to_main
        ):
            verdict = "section"
        back_to_main = back_to_main or verdict == "main"
        read[verdict].append(day)
        parsed = {"main": d.by_main, "section": d.by_section}.get(verdict, {})
        for gid in d.by_main:
            snapshot.schedule[gid].pop(day, None)
            if verdict == "main" or (verdict == "section" and gid in d.columns):
                if parsed.get(gid):
                    snapshot.schedule[gid][day] = list(parsed[gid])
                if verdict == "section" and d.columns[gid] != column_of.get(gid):
                    snapshot.day_columns.setdefault(gid, {})[day] = d.columns[gid]
            else:
                snapshot.unread.setdefault(gid, {})[day] = False
                if verdict == "section":
                    absent.setdefault((gid, d.why), []).append(day)
                else:
                    whys[d.why] = None
        if verdict != "section":
            continue
        for gid, (name, col) in d.new.items():
            lessons = d.by_new.get(gid, [])
            if gid not in added and not lessons:
                # Пустая колонка шаблона — ещё не группа.
                continue
            if gid not in added:
                added[gid] = GroupRef(name=name, id=gid, column=col)
                # В списке выбора — сразу за группой, которую разделили.
                base = group_id(SUBGROUP_RE.fullmatch(name).group(1))
                at = max(
                    (k for k, g in enumerate(snapshot.groups) if g.id == base or g.id in added),
                    default=len(snapshot.groups) - 1,
                )
                snapshot.groups.insert(at + 1, added[gid])
            own.setdefault(gid, set()).add(day)
            if lessons:
                snapshot.schedule.setdefault(gid, {})[day] = list(lessons)
            if col != added[gid].column:
                snapshot.day_columns.setdefault(gid, {})[day] = col
            names[gid] = name
    if previous is not None and read[""]:
        # Ни один день разделения не решён — подгруппы прежнего снимка остаются:
        # иначе у выбравших их 404 и «выберите заново», а через заход всё вернётся.
        have = {g.id for g in snapshot.groups}
        for g in previous.groups:
            m = SUBGROUP_RE.fullmatch(g.name)
            if g.id in have or not m or (base := group_id(m.group(1))) not in have:
                continue
            added[g.id] = g
            at = max(k for k, x in enumerate(snapshot.groups) if x.id in (base, *added))
            snapshot.groups.insert(at + 1, g)
            have.add(g.id)
            names[g.id] = g.name
    for gid, ref in added.items():
        fill_subgroup(snapshot, ref, own.get(gid, set()))
    for (gid, why), days in absent.items():
        snapshot.unread_why.append(
            f"{names.get(gid, gid)} {days[0]:%d.%m}–{days[-1]:%d.%m}: группы нет в повторном "
            f"заголовке, по которому прочитаны эти дни ({why})"
        )
    snapshot.unread_why.extend(whys)

    def span(days: list[date]) -> str:
        return f"{days[0]:%d.%m}–{days[-1]:%d.%m} ({len(days)})" if days else "нет"

    if added:
        _warn_once(
            ("подгруппы", tuple(sorted(added))),
            "повторный заголовок разделил группы на подгруппы — читаю их пары под ним: %s",
            ", ".join(g.name for g in added.values()), logger=log,
        )
    _warn_once(
        ("запасной", tuple(sorted(doubt)), tuple(read["section"]), tuple(read["main"])),
        "повторный заголовок спорит с главным: по повторному прочитаны дни %s, по главному — "
        "%s, не прочитаны — %s", span(read["section"]), span(read["main"]), span(read[""]),
        logger=log,
    )


def fill_subgroup(snapshot: Snapshot, ref: GroupRef, own: set[date]) -> None:
    """Подгруппа — часть группы: в дни, где лист пишет группу целиком (вне
    недели разделения или день не решён), у неё пары и пометки группы."""
    base = group_id(SUBGROUP_RE.fullmatch(ref.name).group(1))
    parent = next((g for g in snapshot.groups if g.id == base), None)
    for day, lessons in snapshot.schedule.get(base, {}).items():
        if day not in own:
            snapshot.schedule.setdefault(ref.id, {})[day] = list(lessons)
            snapshot.day_columns.setdefault(ref.id, {})[day] = snapshot.day_columns.get(base, {}).get(
                day, parent.column if parent else ref.column)
    for day, known_before in snapshot.unread.get(base, {}).items():
        if day not in own:
            snapshot.unread.setdefault(ref.id, {})[day] = known_before
