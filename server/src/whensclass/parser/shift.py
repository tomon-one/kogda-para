"""Сдвиг колонок и ячеек по содержимому листа.

Ищется по самим парам — против прежней версии листа и по истории групп. Зовут
его служба (с историей прежнего снимка) и канарейка, а не обход листа.
"""

from __future__ import annotations

import dataclasses
import logging
from datetime import date

from ..domain.models import ChangedAgainstPrevious, Lesson, Snapshot, SourceFormatChanged
from .cells import FIO_RE

log = logging.getLogger(__name__)

# Сдвиг блока строк, под которым нет повторного заголовка, не видит ни одна
# сверка колонок: каждая группа молча получает пары соседа. Видно его по
# содержимому, и признак — именно «пары соседа», а не «незнакомые пары».
#
# Две сверки, обе по группам в порядке колонок и по смещению k колонок:
# - с прежней версией листа — на датах, что есть в обеих: пара группы — та,
#   что в прежней версии стояла у соседа через k, а не своя. Так видна правка
#   уже выложенной недели. Отказ — серия в PREV_RUN_REJECT групп подряд;
# - с историей групп — для новой недели, где прежней версии нет: группа за
#   день голосует за k, если её пар, знакомых истории соседа и не знакомых
#   своей, больше, чем наоборот. Отказ — серия в SHIFT_RUN_REJECT голосов.
# Группа, у которой своего больше, серию рвёт; прочие её не трогают.
#
# Доля групп с незнакомыми парами ошибается в обе стороны, поэтому она только
# подозрение. Голосует день группы, а не строка пары: подгруппы с общими
# лекциями и пустые пары не голосуют, и построчная серия не набирает порога
# даже у явного сдвига. Смещения — до ±4: сдвиг на три блока при ±2 не виден.
#
# Пороги — по 225 парам соседних версий живого листа за 14–24.09.2026 и
# подделанным листам недели 21–26.09 (хвосты вставкой и удалением 1–4 блоков,
# окна 2–30 колонок, сдвиг на неделе, выложенной сразу со сдвигом): честный
# максимум сверки с прежней версией — 1, атаки — от 2 у окна в 2–3 колонки до
# 150; голосов по истории — 3 честно, 4–7 у хвоста в один блок у правого края
# и 20–140 у прочих хвостов.
PREV_RUN_REJECT = 3
SHIFT_RUN_REJECT = 6
SHIFT_OFFSETS = (-4, -3, -2, -1, 1, 2, 3, 4)
# Сдвиг ячеек группы по вертикали («удалить ячейки, сдвиг вверх»). На одну
# строку — ФИО преподавателя встаёт на место названия: честно 0 пар в день,
# у сдвига — по паре на каждую задетую группу. На две — пары группы съезжают
# на номер через границы дней: честно так меняется не больше одного дня
# группы за версию, у сдвига — 4–6.
NAME_SUBJECTS_REJECT = 3
VERTICAL_DAYS_REJECT = 3
# Съехала неделя одной колонки — не отказ, а подозрение с тревогой: 25.09
# колледж так переставил неделю КП-923 (с КП-1124 у них одна колонка) и
# трое суток правил её поверх, а отказ залип бы на всём листе. Отказ — когда
# так съехали хотя бы две колонки.
VERTICAL_SHIFTED_COLUMNS_REJECT = 2
# То же на одном дне у многих групп: честно — до 4 групп за день (14.09
# 13:40), у сдвига в последний день листа — 21 и 135.
VERTICAL_GROUPS_REJECT = 10
# Доля незнакомых — только подозрение: законные правки задевают её так же,
# как сдвиг.
SHIFT_WARN_SHARE = 0.25
SHIFT_ROW_WARN_SHARE = 0.35
SHIFT_MIN_GROUPS = 20
SHIFT_MIN_LESSONS_TODAY = 3
SHIFT_MIN_HISTORY = 10


def _trace(lesson: Lesson) -> tuple[str, tuple[str, ...]]:
    return " ".join(lesson.subject.lower().split()), lesson.teachers


Seed = dict[str, set[tuple[str, tuple[str, ...]]]]


def shift_seed(previous: Snapshot | None, current: Snapshot) -> Seed:
    """История из прошлого снимка — для первой недели листа, где своей ещё нет.

    Каждый новый лист начинается без истории, и первые дни сдвиг был бы
    невидим — а это как раз дни, которые только что набрали руками. Прошлый
    снимок годится в историю, когда это тот же лист (даты пересекаются) или
    следующий за ним через выходные. После каникул — нет: у половины групп
    сменились предметы, и первый день семестра выглядел бы как сдвиг.
    """
    if previous is None or not previous.dates or not current.dates:
        return {}
    same = set(previous.dates) & set(current.dates)
    gap = (min(current.dates) - max(previous.dates)).days
    if not same and not 0 <= gap <= 3:
        return {}
    return {
        gid: {_trace(x) for lessons in by_date.values() for x in lessons}
        for gid, by_date in previous.schedule.items()
    }


def check_shift(
    snapshot: Snapshot, seed: Seed | None = None, previous: Snapshot | None = None
) -> list[str]:
    """Сдвиг колонок или ячеек по содержимому (см. SHIFT_*, PREV_*, выше).

    История копится по дням, а не собирается заново для каждого дня: иначе
    квадратично по числу дат, а колледж дописывает недели в один лист. Порядок
    колонок — этого снимка, поэтому склеенный снимок двух листов сюда не
    годится: проверять каждый лист отдельно. `previous` — прежний принятый
    снимок: с ним сверяются даты, что есть в обоих.

    Возвращает подозрения, которые отказом не стали: «у многих групп
    незнакомые предметы» (`_warn_strangers`) и неделя одной группы, съехавшая
    по вертикали (`_check_vertical`), — их служба шлёт тревогой.
    """
    seed = seed or {}
    first: dict[int, str] = {}
    for group in snapshot.groups:
        first.setdefault(group.column, group.id)
    order = [first[c] for c in sorted(first)]
    names = {g.id: g.name for g in snapshot.groups}
    _check_name_subjects(snapshot)
    suspicions: list[str] = []
    if previous is not None:
        _judge_against_previous(snapshot, previous, order, names)
        suspicions += _check_vertical(snapshot, previous, names)
    history = {gid: set(seed.get(gid, ())) for gid in snapshot.schedule}
    for day in snapshot.dates:
        _judge_neighbours(snapshot, day, history, order, names)
        suspicions += _warn_strangers(snapshot, day, history)
        for gid, by_date in snapshot.schedule.items():
            history[gid].update(_trace(x) for x in by_date.get(day, []))
    return suspicions


def _shift_message(votes: int, start: str, day: date, k: int, how: str) -> str:
    side = "правее" if k > 0 else "левее"
    word = "колонку" if abs(k) == 1 else "колонки"
    return (
        f"{day} похоже на сдвиг колонок: у {votes} групп подряд, начиная с {start}, "
        f"пары соседа на {abs(k)} {word} {side} ({how})"
    )


def _series(
    order: list[str], k: int, score, threshold: int, names: dict[str, str], day: date, how: str,
    error: type[SourceFormatChanged] = SourceFormatChanged,
) -> None:
    """Серия групп подряд, у которых `score(i, j)` — «чужого больше своего»."""
    votes, start = 0, None
    for i, gid in enumerate(order):
        j = i + k
        if not 0 <= j < len(order):
            continue
        theirs, own = score(gid, order[j])
        if theirs > own:
            votes += 1
            start = start or gid
            if votes >= threshold:
                raise error(_shift_message(votes, names.get(start, start), day, k, how))
        elif own > theirs:
            votes, start = 0, None


def _judge_neighbours(
    snapshot: Snapshot, day: date, history: dict[str, set], order: list[str], names: dict[str, str]
) -> None:
    """Отказ, если подряд идущие группы получили за день пары соседа — по истории."""
    traces = {
        gid: [_trace(x) for x in snapshot.schedule.get(gid, {}).get(day, [])] for gid in order
    }

    def score(gid: str, neighbour: str) -> tuple[int, int]:
        mine, theirs = history[gid], history[neighbour]
        day_traces = traces[gid]
        return (
            sum(1 for x in day_traces if x in theirs and x not in mine),
            sum(1 for x in day_traces if x in mine and x not in theirs),
        )

    for k in SHIFT_OFFSETS:
        _series(order, k, score, SHIFT_RUN_REJECT, names, day, "по истории групп")


def _slots(snapshot: Snapshot, gid: str, day: date) -> dict[int, Lesson]:
    return {x.number: x for x in snapshot.schedule.get(gid, {}).get(day, [])}


def _judge_against_previous(
    snapshot: Snapshot, previous: Snapshot, order: list[str], names: dict[str, str]
) -> None:
    """Отказ, если на общей с прежней версией дате группы подряд получили
    пары, которые там стояли у соседа, — пара в пару, по номерам."""
    for day in sorted(set(snapshot.dates) & set(previous.dates)):
        new = {gid: _slots(snapshot, gid, day) for gid in order}
        old = {gid: _slots(previous, gid, day) for gid in order}

        def score(gid: str, neighbour: str) -> tuple[int, int]:
            mine, theirs = old[gid], old[neighbour]
            lessons = new[gid].items()
            return (
                sum(1 for n, x in lessons if theirs.get(n) == x and mine.get(n) != x),
                sum(1 for n, x in lessons if mine.get(n) == x and theirs.get(n) != x),
            )

        for k in SHIFT_OFFSETS:
            _series(
                order, k, score, PREV_RUN_REJECT, names, day, "против прежней версии",
                ChangedAgainstPrevious,
            )


def neighbour_runs(snapshot: Snapshot, previous: Snapshot, since: date, min_run: int = 2) -> set[str]:
    """Группы в сериях подряд (от `min_run`), получивших на выложенном дне (от
    `since`) пары соседа против прежней версии, — та же мерка, что у отказа
    (`_judge_against_previous`), но с порогом ниже.

    Сдвиг у двух–пяти соседних групп отказом не ловится и ушёл бы им
    уведомлением сайта с парами соседа. На честном архиве 14–28.09 такая
    серия длиннее одной группы не встретилась ни разу — рассылка таким группам
    не шлёт, владельцу тревога.
    """
    first: dict[int, str] = {}
    members: dict[int, list[str]] = {}
    for group in snapshot.groups:
        first.setdefault(group.column, group.id)
        members.setdefault(group.column, []).append(group.id)
    order = [first[c] for c in sorted(first)]
    found: set[str] = set()
    for day in sorted(set(snapshot.dates) & set(previous.dates)):
        if day < since:
            continue
        new = {gid: _slots(snapshot, gid, day) for gid in order}
        old = {gid: _slots(previous, gid, day) for gid in order}
        for k in SHIFT_OFFSETS:
            run: list[str] = []
            for i, gid in enumerate(order):
                j = i + k
                if not 0 <= j < len(order):
                    continue
                mine, theirs = old[gid], old[order[j]]
                lessons = new[gid].items()
                t = sum(1 for n, x in lessons if theirs.get(n) == x and mine.get(n) != x)
                o = sum(1 for n, x in lessons if mine.get(n) == x and theirs.get(n) != x)
                if t > o:
                    run.append(gid)
                elif o > t:  # ничья серию не рвёт, как в `_series`
                    if len(run) >= min_run:
                        found.update(run)
                    run = []
            if len(run) >= min_run:
                found.update(run)
    # Колонку делят несколько групп («ДП-923 и ДП-1124») — пары соседа у всех
    # них, а не только у первой.
    column_of = {gid: c for c, gid in first.items()}
    return {gid for f in found for gid in members[column_of[f]]}


def vertical_groups(snapshot: Snapshot, previous: Snapshot) -> set[str]:
    """Группы колонки, чьи пары съехали по вертикали, — то, что
    `_check_vertical` в одной колонке пропускает подозрением. Лист принят, а
    уведомление с парами не на своих номерах ушло бы в том же заходе, раньше,
    чем владелец прочтёт тревогу."""
    column = {g.id: g.column for g in snapshot.groups}
    shifted = {column.get(gid) for gid in _vertical_shifts(snapshot, previous)[1]}
    return {g.id for g in snapshot.groups if g.column in shifted}


def _vertical_shifts(
    snapshot: Snapshot, previous: Snapshot
) -> tuple[dict[tuple[date, int], list[str]], dict[str, tuple[int, list[date]]]]:
    """(день и шаг → группы, съехавшие в этот день; группа → шаг и дни, если
    съехала не меньше чем на VERTICAL_DAYS_REJECT днях)."""
    common = sorted(set(snapshot.dates) & set(previous.dates))
    same_day: dict[tuple[date, int], list[str]] = {}
    shifted: dict[str, tuple[int, list[date]]] = {}
    for gid in snapshot.schedule:
        for step in (-2, -1, 1, 2):
            days = []
            for day in common:
                new, old = _slots(snapshot, gid, day), _slots(previous, gid, day)
                if new == old or len(new) < 2:
                    continue
                # Номер у съехавшей пары другой — сверяется всё, кроме него.
                hit = sum(
                    1 for n, x in new.items()
                    if n + step in old and _same_but_number(old[n + step], x)
                )
                if hit >= 2 and hit >= len(new) - 1:
                    days.append(day)
                    same_day.setdefault((day, step), []).append(gid)
            if len(days) >= VERTICAL_DAYS_REJECT:
                shifted[gid] = (step, days)
                break  # одна группа — одна строка, даже если сошлись два шага
    return same_day, shifted


def _check_vertical(snapshot: Snapshot, previous: Snapshot, names: dict[str, str]) -> list[str]:
    """Пары групп на нескольких днях съехали на номер-два против прежней
    версии: так выглядит «удалить ячейки, сдвиг вверх» в блоке. Отказ — в
    двух колонках и больше или у многих групп в один день; в одной колонке
    (у групп, которые её делят, это одна правка) — подозрение, строкой."""
    column = {g.id: g.column for g in snapshot.groups}
    same_day, found = _vertical_shifts(snapshot, previous)
    shifted: dict[object, str] = {}
    for gid, (step, days) in found.items():
        shifted.setdefault(
            column.get(gid, gid),
            f"у группы {names.get(gid, gid)} пары съехали на {abs(step)} "
            f"{'номер' if abs(step) == 1 else 'номера'} "
            f"{'вверх' if step > 0 else 'вниз'} на {len(days)} днях с {days[0]} — "
            "похоже на сдвиг ячеек по вертикали"
        )
    for (day, step), gids in sorted(same_day.items()):
        if len(gids) >= VERTICAL_GROUPS_REJECT:
            raise ChangedAgainstPrevious(
                f"{day} у {len(gids)} групп, начиная с {names.get(gids[0], gids[0])}, пары "
                f"съехали на {abs(step)} {'номер' if abs(step) == 1 else 'номера'} — похоже "
                "на сдвиг ячеек по вертикали"
            )
    if len(shifted) >= VERTICAL_SHIFTED_COLUMNS_REJECT:
        raise ChangedAgainstPrevious("; ".join(list(shifted.values())[:2]))
    for message in shifted.values():
        log.warning("%s — в одной колонке, лист принят, но присмотреться", message)
    return list(shifted.values())


def _same_but_number(a: Lesson, b: Lesson) -> bool:
    return dataclasses.replace(a, number=b.number) == b


def _check_name_subjects(snapshot: Snapshot) -> None:
    """Отказ, если в дне у нескольких пар вместо названия — ФИО: строки пар и
    преподавателей поменялись местами (сдвиг ячеек на одну строку)."""
    per_day: dict[date, list[str]] = {}
    for gid, by_date in snapshot.schedule.items():
        for day, lessons in by_date.items():
            for lesson in lessons:
                if FIO_RE.fullmatch(lesson.subject):
                    per_day.setdefault(day, []).append(lesson.subject)
    for day, found in sorted(per_day.items()):
        if len(found) >= NAME_SUBJECTS_REJECT:
            raise SourceFormatChanged(
                f"{day} у {len(found)} пар вместо названия ФИО ({found[0]!r}…) — похоже на "
                "сдвиг ячеек на строку"
            )


def _warn_strangers(snapshot: Snapshot, day: date, history: dict[str, set]) -> list[str]:
    """Много незнакомых пар — не отказ, а подозрение: так выглядит и неделя
    практики, и классный час, и сдвиг, которого признак соседа не увидел.
    На честном архиве 14–24.09 она не сработала ни разу, а сдвиг на три блока
    видела, поэтому подозрения уходят наверх, и служба шлёт по ним тревогу."""
    found: list[str] = []
    compared = 0
    strangers: list[str] = []
    rows: dict[int, list[int]] = {}
    for gid, by_date in snapshot.schedule.items():
        today = by_date.get(day, [])
        known = history[gid]
        if len(known) < SHIFT_MIN_HISTORY:
            continue
        for lesson in today:
            cell = rows.setdefault(lesson.number, [0, 0])
            cell[0] += 1
            if _trace(lesson) not in known:
                cell[1] += 1
        if len(today) < SHIFT_MIN_LESSONS_TODAY:
            continue
        compared += 1
        if not ({_trace(x) for x in today} & known):
            strangers.append(gid)
    for number, (seen, unknown) in sorted(rows.items()):
        if seen >= SHIFT_MIN_GROUPS and unknown / seen > SHIFT_ROW_WARN_SHARE:
            found.append(
                f"{number}-я пара {day}: у {unknown} групп из {seen} незнакомый предмет"
            )
    if compared >= SHIFT_MIN_GROUPS and len(strangers) / compared > SHIFT_WARN_SHARE:
        found.append(
            f"{day}: у {len(strangers)} групп из {compared} ни одного знакомого предмета "
            f"({', '.join(strangers[:3])})"
        )
    for message in found:
        log.warning("%s — не сдвиг по соседям, но присмотреться", message)
    return found
