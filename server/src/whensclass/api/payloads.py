"""Сборка тел ответов API.

Ключи короткие намеренно: ответ уезжает на телефон и разбирается виджетом при
каждом обновлении. Правило про отсутствующие поля — в docs/api.md: пустого
поля в JSON нет вовсе, чтобы не гонять null-ы.
"""

from __future__ import annotations

from collections import Counter
from datetime import date, datetime, timedelta, timezone

from ..config import settings
from ..domain.models import Lesson, SheetPlace, Snapshot, a1_column
from ..domain.teachers import TeacherIndex, spelling_twin

API_VERSION = 1


def sheet_url(gid: str | None = None) -> str:
    """Адрес таблицы колледжа — с листом, если известно, на каком мы.

    Адрес отсюда, а не из APK: переедет таблица — переживём правкой
    настроек, а не сборкой. Приложение дописывает к нему `&range=<колонка><строка>`
    из `col` и `row` того же ответа, и Sheets подводит к ячейке.
    """
    base = f"https://docs.google.com/spreadsheets/d/{settings.spreadsheet_id}/edit"
    return f"{base}#gid={gid}" if gid else base


# День после сегодняшнего, где пары есть меньше чем у половины групп против
# самого полного дня листа, колледж ещё не дописал. Будни — 0,85–1,0 от самого
# полного, субботы — 0,65–0,94 (архив 2–26 сентября 2026); недописанный
# понедельник 28.09 на 24 сентября — 0,03. Опора — весь лист, а не тот же
# день недели: в листе из одной недели каждый день недели один, и он сам себе
# опора.
FILLED_SHARE = 0.5


def published(snapshot: Snapshot, today: date | None) -> list[date]:
    """Дни листа, которые колледж уже выложил, а не только начал, — по листу.

    Новую неделю колледж пишет в лист постепенно: сначала даты, потом пары
    по группам. Такой день в `cov` дал бы группам, до которых колледж ещё не
    дошёл, «пар нет» вместо «ещё не опубликовано», и в пятницу человек прочтёт
    «в понедельник пар нет». 18 сентября 2026 в 19:20–21:20 так выглядела вся
    неделя 21–26.09, а 24 сентября — понедельник 28.09 (пары у 6 групп из 189).

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

    Каркас колледж вписал 24 сентября 2026 до 02.11. По последней дате память
    поиска листов сочла бы лист покрывающим весь октябрь и не прочла бы новую
    вкладку.
    """
    busy, fullest = filling(snapshot)
    dates = sorted(snapshot.dates)
    return next((d for d in reversed(dates) if busy[d] >= FILLED_SHARE * fullest), None)


def _group_edge(
    snapshot: Snapshot, group_id: str, sheet: list[date], marks: bool = True
) -> date | None:
    """Последний день, который колледж выложил группе; None — ни одного.

    Колледж дописывает колонку группы сразу на месяц и группу за группой
    (живой лист 24 сентября 2026: у 6 групп из 189 пары по 28.10, у
    остальных пусто). Доля по листу тут не судья: дописана половина групп —
    и прочие получат три недели «пар нет».
    Поэтому край — у каждой группы свой: её последний день с парами. Дальше
    него — только остаток той же недели и только внутри выложенного по листу
    (`sheet`): суббота без пар у группы, которая по субботам не учится, —
    «пар нет», а не «ещё не опубликовано».

    Ошибка — в безопасную сторону: группа на практике, чья колонка пуста до
    конца листа, увидит «ещё не опубликовано» вместо «пар нет». В сентябре
    2026 таких в дописанных неделях 0–3 из 189.
    """
    by_date = snapshot.schedule.get(group_id, {})
    unread = snapshot.unread.get(group_id, {})
    written = {day for day, lessons in by_date.items() if lessons}
    if marks:
        # Непрочитанный день — тоже запись колледжа в колонке группы: он в
        # покрытии со своей пометкой, а не «ещё не опубликовано».
        written |= set(unread)
    last = max(written, default=None)
    if last is not None and sheet:
        week_end = last + timedelta(days=6 - last.weekday())
        last = max(last, min(week_end, sheet[-1]))
    unknown = [day for day, kept in unread.items() if not kept]
    if not marks and unknown and last is not None:
        # Версии без пометок показали бы пустой день как «пар нет» и
        # «убрали»: им край — перед ним, вместе с общим stale.
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
    return _upto(snapshot, _group_edge(snapshot, group_id, sheet, marks))


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
    edges = [_group_edge(snapshot, group, sheet, marks) for group in current or sorted(groups)]
    edge = min(today, sheet[-1])
    if None not in edges:
        edge = max(edge, min(edges))
    return _upto(snapshot, edge)


def _alive_since(snapshot: Snapshot, group: str, since: date) -> bool:
    """Есть ли у группы пары в `since` или позже — колонка не опустела."""
    return any(lessons for day, lessons in snapshot.schedule.get(group, {}).items() if day >= since)


def _unread_mark(kept: bool) -> str:
    """`un` у дня: пары прежние (`kept`) или их нет вовсе (`missing`)."""
    return "kept" if kept else "missing"


def _teacher_unread(
    snapshot: Snapshot, index: TeacherIndex, teacher_id: str, day: date
) -> tuple[str, list[str]] | None:
    """Пометка дня преподавателя и группы, из-за которых она стоит.

    Прежние пары — если среди его пар этого дня есть пары такой группы.
    Не прочитан — если он ведёт у этой группы на той же неделе: был ли он
    в её непрочитанном дне, неизвестно. Остальные его группы листа ни при чём.
    """
    names = {g.id: g.name for g in snapshot.groups}
    today_groups = {
        part
        for entry in index.days(teacher_id).get(day, [])
        for part in entry.group_name.split(", ")
    }
    monday = day - timedelta(days=day.weekday())
    week_groups = {
        part
        for offset in range(7)
        for entry in index.days(teacher_id).get(monday + timedelta(days=offset), [])
        for part in entry.group_name.split(", ")
    }
    kept, missing = [], []
    for gid in sorted(index.groups.get(teacher_id, ())):
        mark = snapshot.unread.get(gid, {}).get(day)
        name = names.get(gid)
        if mark is None or name is None:
            continue
        if mark and name in today_groups:
            kept.append(name)
        elif not mark and name in week_groups:
            missing.append(name)
    if missing:
        return "missing", missing
    if kept:
        return "kept", kept
    return None


def _cov(dates: list[date]) -> list[str] | None:
    return [dates[0].isoformat(), dates[-1].isoformat()] if dates else None


def _place_days(
    snapshot: Snapshot, start: date, days: int
) -> tuple[str | None, dict[date, SheetPlace]]:
    """Лист окна и строки его дней.

    Окно может перешагнуть границу листа, а `range` в ссылке относится к
    одному листу: строки отдаём только для дней с того листа, на который
    ведёт ссылка, — с первого покрытого дня окна.
    """
    places = snapshot.places
    window = [start + timedelta(days=offset) for offset in range(days)]
    gid = next((places[d].gid for d in window if d in places and places[d].gid), None)
    if gid is None:
        return None, {}
    return gid, {d: places[d] for d in window if d in places and places[d].gid == gid}


def _lesson(lesson: Lesson) -> dict:
    out: dict = {"n": lesson.number, "s": lesson.subject}
    if lesson.kind:
        out["k"] = lesson.kind
    if lesson.teachers:
        out["t"] = list(lesson.teachers)
    if lesson.room:
        out["r"] = lesson.room
    if lesson.url:
        out["u"] = lesson.url
    if lesson.online:
        # Онлайн без ссылки — обычное дело: ссылку дают позже. Поэтому
        # признак отдельный, а не выводится из наличия «u». Ссылка при
        # кабинете — очная пара, где преподаватель на связи по ссылке, а
        # студенты в кабинете.
        out["o"] = 1
    if lesson.cancelled:
        out["x"] = 1
    if lesson.note:
        out["c"] = lesson.note
    return out


def teachers_payload(index: TeacherIndex, generated: datetime) -> dict:
    return {
        "v": API_VERSION,
        "gen": _iso(generated),
        "teachers": [
            {"id": tid, "name": name}
            for tid, name in sorted(index.names.items(), key=lambda x: x[1])
        ],
    }


def teacher_answer(
    snapshot: Snapshot,
    index: TeacherIndex,
    known_teacher,
    teacher_id: str,
    start: date,
    days: int,
    generated: datetime,
    bells: dict[str, list[str]] | None = None,
    today: date | None = None,
    marks: bool = True,
) -> dict | None:
    """Ответ /v1/teacher. None — такого преподавателя нет.

    Краткая запись «Фамилия И. О.» или имя с опечаткой, сведённые к
    полному имени, отвечают расписанием полного. Кого нет в этом листе, но кто был в прежних
    (`known_teacher`, 60 дней), — днями без пар: отпуск, неделя без часов, а не
    «вас больше нет». Колледж исправил опечатку в имени — 404: пустые дни
    говорили бы «пар нет», а человек найдёт себя под верным именем. Тем же
    путём считают уведомления сайта.
    """
    def build(tid: str, known_name: str | None = None) -> dict | None:
        return teacher_payload(snapshot, index, tid, start, days, generated,
                               bells=bells, known_name=known_name, today=today, marks=marks)

    body = build(teacher_id)
    if body is None and (full := index.aliases.get(teacher_id)):
        body = build(full)
    if body is not None:
        return body
    name = known_teacher(teacher_id)
    if not name or spelling_twin(index, name):
        return None
    return build(teacher_id, known_name=name)


def teacher_payload(
    snapshot: Snapshot,
    index: TeacherIndex,
    teacher_id: str,
    start: date,
    days: int,
    generated: datetime,
    bells: dict[str, list[str]] | None = None,
    known_name: str | None = None,
    today: date | None = None,
    marks: bool = True,
) -> dict | None:
    """Расписание преподавателя. None, если такого в таблице нет.

    `known_name` — имя преподавателя, которого в этом снимке нет, но который
    был в прошлых: ему отвечаем днями без пар, а не 404. `marks` — клиент
    знает пометку непрочитанного дня (`un`, `ug`).
    """
    name = index.names.get(teacher_id) or known_name
    if name is None:
        return None

    by_date = index.days(teacher_id)
    shown = teacher_published(snapshot, index, teacher_id, today, marks)
    covered = set(shown)
    gid, placed = _place_days(snapshot, start, days)

    out_days = []
    for offset in range(days):
        day = start + timedelta(days=offset)
        # За краем выложенного дня нет, даже если часть его пар вписана:
        # остальные его группы колледж ещё не дописал.
        if day not in covered:
            continue
        lessons = []
        for entry in by_date.get(day, []):
            item = _lesson(entry.lesson)
            # Кому именно читается пара — то, чего нет в расписании группы.
            item["gr"] = entry.group_name
            # Преподаватель тут очевиден, его имя только занимает место.
            item.pop("t", None)
            if entry.column is not None:
                # У преподавателя каждая пара в своей колонке — колонке группы.
                item["col"] = a1_column(entry.column)
            lessons.append(item)
        out_day = {"d": day.isoformat(), "l": lessons}
        if day in placed:
            out_day["row"] = placed[day].row
        if marks and (mark := _teacher_unread(snapshot, index, teacher_id, day)):
            out_day["un"], out_day["ug"] = mark
        out_days.append(out_day)

    payload = {
        "v": API_VERSION,
        "g": teacher_id,
        "gn": name,
        "kind": "teacher",
        "gen": _iso(generated),
        "src": snapshot.sheet_title,
        "days": out_days,
    }
    if gid:
        payload["src_url"] = sheet_url(gid)
    if cov := _cov(shown):
        payload["cov"] = cov
    if bells:
        payload["bells"] = bells
    return payload


def groups_payload(snapshot: Snapshot, generated: datetime) -> dict:
    return {
        "v": API_VERSION,
        "gen": _iso(generated),
        "groups": [{"id": g.id, "name": g.name} for g in snapshot.groups],
    }


def schedule_payload(
    snapshot: Snapshot,
    group_id: str,
    start: date,
    days: int,
    generated: datetime,
    bells: dict[str, list[str]] | None = None,
    today: date | None = None,
    marks: bool = True,
) -> dict | None:
    """Тело для виджета. None, если такой группы в листе нет.

    `today` — сегодня по часам колледжа: от него считается недописанный
    хвост (`group_published`). Без него покрытие — весь лист.
    """
    group = next((g for g in snapshot.groups if g.id == group_id), None)
    if group is None:
        return None

    by_date = snapshot.schedule.get(group_id, {})
    unread = snapshot.unread.get(group_id, {})
    shown = group_published(snapshot, group_id, today, marks)
    covered = set(shown)
    gid, placed = _place_days(snapshot, start, days)

    out_days = []
    for offset in range(days):
        day = start + timedelta(days=offset)
        if day not in covered:
            # Дня нет в ответе вовсе — виджет отличит «пар нет» от
            # «расписание ещё не опубликовано». Дни с парами группы и её
            # непрочитанные в покрытие входят всегда.
            continue
        out_day = {
            "d": day.isoformat(),
            "l": [_lesson(x) for x in by_date.get(day, [])],
        }
        if day in placed:
            out_day["row"] = placed[day].row
        if marks and (kept := unread.get(day)) is not None:
            out_day["un"] = _unread_mark(kept)
        out_days.append(out_day)

    payload = {
        "v": API_VERSION,
        "g": group.id,
        "gn": group.name,
        "gen": _iso(generated),
        "src": snapshot.sheet_title,
        "days": out_days,
    }
    # Колонка группы в листе, на который ведёт ссылка: вместе с `row` дня
    # даёт ячейку, к которой ссылка «открыть таблицу» подводит человека. Если
    # в этом листе группы нет, колонки нет тоже: колонка другого листа ведёт в
    # чужую группу.
    in_sheet = snapshot.sheet_columns.get(gid) if gid else None
    if in_sheet is None or group.id in in_sheet:
        payload["col"] = a1_column(snapshot.column_of(group, gid=gid))
    if gid:
        payload["src_url"] = sheet_url(gid)
    if cov := _cov(shown):
        payload["cov"] = cov
    if bells:
        payload["bells"] = bells
    return payload


def meta_payload(
    snapshot: Snapshot,
    generated: datetime,
    status: str,
    checked: datetime | None,
    today: date | None = None,
    failing_since: datetime | None = None,
    error: str | None = None,
    refresh: str | None = None,
) -> dict:
    """`status` — для всех версий; `refresh` — состояние обновления без
    непрочитанных дней (их список — `unread`), его читают новые."""
    # Куда идти, когда мы подвели: на лист, где лежит сегодняшний день, а
    # без него — просто в книгу. Ближайший известный день годится тоже:
    # в воскресенье это завтрашний понедельник.
    place = None
    if today is not None and snapshot.places:
        place = snapshot.places.get(today) or min(
            snapshot.places.items(), key=lambda item: abs((item[0] - today).days)
        )[1]
    out = {
        "v": API_VERSION,
        "gen": _iso(generated),
        "src": snapshot.sheet_title,
        "groups": len(snapshot.groups),
        "status": status,
        "src_url": sheet_url(place.gid if place else None),
    }
    if cov := _cov(published(snapshot, today)):
        out["cov"] = cov
    if checked:
        out["checked"] = _iso(checked)
    if settings.min_build > 0:
        # Сборки ниже этой служба не обслуживает (426): приложение скажет
        # «обновите», не дожидаясь отказа.
        out["min"] = settings.min_build
    if status != "ok":
        # С какого часа и почему: двое суток stale не должны выглядеть как
        # минута. Текст ошибки уже без адресов — его чистит refresher.
        if failing_since:
            out["since"] = _iso(failing_since)
        if error:
            out["err"] = error
    if snapshot.unread:
        out["refresh"] = refresh or status
        # Только даты: у какой группы — в её расписании. День без даты в
        # листе задевает все 190 групп, а /v1/meta спрашивают чаще всего.
        out["unread"] = sorted({day.isoformat() for days in snapshot.unread.values() for day in days})
    return out


def _iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )
