"""Что колледж уже выложил: край листа, группы и преподавателя.

Новую неделю колледж пишет постепенно: даты, потом пары по группам. Отсюда
берут границу `cov` ответы API, сверка с листом преподавателей и поиск листа.
"""

from __future__ import annotations

from collections import Counter
from datetime import date, timedelta

from ..domain.models import Snapshot
from ..domain.teachers import TeacherIndex

# День после сегодняшнего, где пары есть меньше чем у половины групп против
# самого полного дня листа, колледж ещё не дописал: будни — 0,85–1,0 от
# самого полного, субботы — от 0,65, недописанный день — около 0,03. Опора —
# весь лист, а не тот же день недели: в листе из одной недели он сам себе опора.
FILLED_SHARE = 0.5


# День, дописанный почти всем группам: кто там пуст — не учится, а не ждёт.
ALMOST_ALL_SHARE = 0.9


def published(snapshot: Snapshot, today: date | None) -> list[date]:
    """Дни листа, которые колледж уже выложил, а не только начал, — по листу.

    Недописанный день в `cov` дал бы группам, до которых колледж ещё не
    дошёл, «пар нет» вместо «ещё не опубликовано»: в пятницу человек прочтёт
    «в понедельник пар нет».

    Отрезается только хвост: цепочка недописанных дней в конце листа, после
    сегодняшнего. Пустой день посреди выложенной недели — праздник — так и
    остаётся «пар нет»; сегодня и прошлое тут не отрезаются. Ошибка в другую
    сторону безопасна: праздник в самом конце листа покажется «ещё не
    опубликовано».

    Это `cov` в /v1/meta и граница для остатка недели у группы. Группе и
    преподавателю край считается свой — `group_published`,
    `teacher_published`: доля по листу не видит, до кого колледж не дошёл.
    """
    dates = sorted(snapshot.dates)
    if today is None:
        return dates
    busy, fullest = filling(snapshot)
    end = len(dates)
    while end:
        day = dates[end - 1]
        if day <= today or busy[day] >= FILLED_SHARE * fullest:
            break
        end -= 1
    return dates[:end]


def filling(snapshot: Snapshot) -> tuple[Counter[date], int]:
    """(у скольких групп в дне есть пары; столько же у самого полного дня)."""
    busy: Counter[date] = Counter()
    for by_date in snapshot.schedule.values():
        for day, lessons in by_date.items():
            if lessons:
                busy[day] += 1
    return busy, max((busy[day] for day in snapshot.dates), default=0)


def filled_until(snapshot: Snapshot) -> date | None:
    """Последний день листа, дописанный хотя бы наполовину против самого
    полного, — без пустого каркаса дат на месяц вперёд.

    Колледж вписывает каркас дат на месяц вперёд: по последней дате память
    поиска сочла бы лист покрывающим весь месяц и не прочла бы новую вкладку.
    """
    busy, fullest = filling(snapshot)
    dates = sorted(snapshot.dates)
    return next((d for d in reversed(dates) if busy[d] >= FILLED_SHARE * fullest), None)


def _group_edge(
    snapshot: Snapshot, group_id: str, sheet: list[date], today: date, marks: bool = True
) -> date | None:
    """Последний день, который колледж выложил группе; None — ни одного.

    Колледж дописывает колонку группы сразу на месяц и группу за группой.
    Доля по листу тут не судья: дописана половина групп — и прочие получат
    три недели «пар нет».
    Поэтому край — у каждой группы свой: её последний день с парами. Дальше
    него — только остаток той же недели и только внутри выложенного по листу
    (`sheet`): суббота без пар у группы, которая по субботам не учится, —
    «пар нет», а не «ещё не опубликовано».

    Ошибка — в безопасную сторону: группа на практике, чья колонка пуста до
    конца листа, увидит «ещё не опубликовано» вместо «пар нет».
    """
    by_date = snapshot.schedule.get(group_id, {})
    unread = snapshot.unread.get(group_id, {})
    written = {day for day, lessons in by_date.items() if lessons}
    last = max(written, default=None)
    if last is not None and sheet:
        week_end = last + timedelta(days=6 - last.weekday())
        last = max(last, min(week_end, sheet[-1]))
    if marks and last is not None:
        # Непрочитанный день — тоже запись колледжа в колонке группы: он в
        # покрытии со своей пометкой, а не «ещё не опубликовано». Только
        # вплотную к выложенному и без остатка недели после него: `cov` —
        # сплошной отрезок, а колонку недели колледж мог ещё не дописать.
        # «Вплотную» — следующий день листа, а не календаря: воскресенья в
        # листе нет, и между двумя непрочитанными неделями цепочка не рвётся.
        for day in sorted(unread):
            after = next((d for d in sheet if d > last), last + timedelta(days=1))
            if last < day <= max(after, last + timedelta(days=1)) and (not sheet or day <= sheet[-1]):
                last = day
    # Версии без пометок показали бы день, которого прежний снимок не знал,
    # как «пар нет»: им край — перед ним, вместе с общим stale. Знакомый день
    # им — как прежде, прежними парами или пустым; прошедший не режет
    # сегодняшний.
    unknown = [day for day, known in unread.items() if not known and day >= today]
    if not marks and unknown and last is not None:
        last = min(last, min(unknown) - timedelta(days=1))
    return last


def _upto(snapshot: Snapshot, edge: date | None) -> list[date]:
    return [day for day in sorted(snapshot.dates) if edge is not None and day <= edge]


def group_published(
    snapshot: Snapshot, group_id: str, today: date | None, marks: bool = True
) -> list[date]:
    """Дни листа, которые колледж уже выложил этой группе (`cov` группы)."""
    sheet = published(snapshot, today)
    if today is None:
        return sheet
    return _upto(snapshot, _group_edge(snapshot, group_id, sheet, today, marks))


def teacher_published(
    snapshot: Snapshot, index: TeacherIndex, teacher_id: str, today: date | None,
    marks: bool = True,
) -> list[date]:
    """Дни, выложенные преподавателю: дописаны все его нынешние группы.

    День преподавателя собран из чужих колонок: вписана одна группа из его
    шести — у него «одна пара в понедельник» без признака, что остальное не
    выложено. Поэтому его край — самый ранний из краёв его групп.

    Группы — его, чья колонка жива: у группы есть пары (у кого угодно) с
    понедельника этой недели. Иначе группа, ушедшая на практику (колонка пуста
    до конца листа), отрежет всем её преподавателям дни после своего края. Брать
    только группы, где он ведёт пары на этой неделе, мало: если пары у него лишь
    в группе, заполненной вперёд, недописанные недели выйдут выложенными — с
    «Пар нет». Живых групп нет — берутся все. Сегодня и прожитые дни не
    отрезаются никогда, как в `published`.
    """
    sheet = published(snapshot, today)
    groups = index.groups.get(teacher_id)
    if today is None or not groups or not sheet:
        return sheet
    monday = today - timedelta(days=today.weekday())
    current = [g for g in sorted(groups) if _alive_since(snapshot, g, monday)]
    edges = [_group_edge(snapshot, group, sheet, today, marks) for group in current or sorted(groups)]
    # Дни, где пары уже у почти всех групп, колледж дописал: пустая там колонка
    # — практика, а не «ещё не дошёл». Группа, уходящая на практику со
    # следующей недели, на этой ещё жива и иначе отрезала бы преподавателю
    # выложенную неделю.
    busy, fullest = filling(snapshot)
    done = next((d for d in reversed(sheet) if busy[d] >= ALMOST_ALL_SHARE * fullest), None)
    if done is not None:
        edges = [max(edge, done) if edge is not None else None for edge in edges]
    edge = min(today, sheet[-1])
    if None not in edges:
        edge = max(edge, min(edges))
    return _upto(snapshot, edge)


def _alive_since(snapshot: Snapshot, group: str, since: date) -> bool:
    """Есть ли у группы пары в `since` или позже — колонка не опустела."""
    return any(lessons for day, lessons in snapshot.schedule.get(group, {}).items() if day >= since)
