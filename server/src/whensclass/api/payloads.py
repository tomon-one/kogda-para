"""Сборка тел ответов API.

Ключи короткие намеренно: ответ уезжает на телефон и разбирается виджетом при
каждом обновлении. Правило про отсутствующие поля — в docs/api.md: пустого
поля в JSON нет вовсе, чтобы не гонять null-ы.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

from ..config import settings
from ..domain.models import Lesson, SheetPlace, Snapshot, a1_column
from ..domain.teachers import TeacherIndex, same_person, spelling_twin
from ..service.bells import own_bells
from .published import group_published, published, teacher_published

API_VERSION = 1


def sheet_url(gid: str | None = None) -> str:
    """Адрес таблицы колледжа — с листом, если известно, на каком мы.

    Адрес отсюда, а не из APK: переедет таблица — переживём правкой
    настроек, а не сборкой. Приложение дописывает к нему `&range=<колонка><строка>`
    из `col` и `row` того же ответа, и Sheets подводит к ячейке.
    """
    base = f"https://docs.google.com/spreadsheets/d/{settings.spreadsheet_id}/edit"
    return f"{base}#gid={gid}" if gid else base


def _unread_mark(snapshot: Snapshot, group_id: str, day: date) -> str | None:
    """`un` у дня группы: пары прежние (`kept`) или их нет вовсе (`missing`).

    Прежний снимок знал день, но пар в нём у группы не было — тоже `missing`:
    «пар нет» тут было бы неправдой, колледж мог в этот день что-то вписать.
    """
    known = snapshot.unread.get(group_id, {}).get(day)
    if known is None:
        return None
    return "kept" if known and snapshot.schedule.get(group_id, {}).get(day) else "missing"


def _teacher_unread(
    snapshot: Snapshot, index: TeacherIndex, teacher_id: str, day: date
) -> tuple[str, list[str], list[str]] | None:
    """Пометка дня преподавателя, группы, из-за которых она стоит, и группы с
    прежними парами, если пометка — `missing`.

    Прежние пары — если среди его пар этого дня есть пары такой группы.
    Не прочитан — если он ведёт у этой группы на той же неделе или в тот же
    день недели в другие недели листа: был ли он в её непрочитанном дне,
    неизвестно, а пара раз в неделю обычно в один и тот же день. Остальные
    его группы листа ни при чём. Обе пометки в одном дне бывают: `missing`
    главнее, но и прежние пары других групп называются.
    """
    names = {g.id: g.name for g in snapshot.groups}
    by_date = index.days(teacher_id)

    def groups_on(days: list[date]) -> set[str]:
        return {
            part
            for d in days
            for entry in by_date.get(d, [])
            for part in entry.group_name.split(", ")
        }

    today_groups = groups_on([day])
    monday = day - timedelta(days=day.weekday())
    week = [monday + timedelta(days=offset) for offset in range(7)]
    likely = groups_on(week + [d for d in by_date if d.weekday() == day.weekday()])
    kept, missing = [], []
    for gid in sorted(index.groups.get(teacher_id, ())):
        mark = _unread_mark(snapshot, gid, day)
        name = names.get(gid)
        if mark is None or name is None:
            continue
        if mark == "kept" and name in today_groups:
            kept.append(name)
        elif mark == "missing" and name in likely:
            missing.append(name)
    if missing:
        return "missing", missing, kept
    if kept:
        return "kept", kept, []
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
    if not name:
        return None
    if same := same_person(index, name):
        return build(same)
    if spelling_twin(index, name):
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
        if bells and (own := own_bells(snapshot, day, bells)):
            out_day["bl"] = own
        if marks and (mark := _teacher_unread(snapshot, index, teacher_id, day)):
            out_day["un"], out_day["ug"], kept = mark
            if kept:
                out_day["uk"] = kept
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
        if (moved := snapshot.day_columns.get(group_id, {}).get(day)) is not None:
            # День прочитан по повторному заголовку с другой раскладкой: ссылка
            # ведёт в колонку дня — и в пустом дне, где пар с колонкой нет.
            # У пар — для клиентов, что колонку дня не знают.
            out_day["col"] = a1_column(moved)
            for item in out_day["l"]:
                item["col"] = out_day["col"]
        if bells and (own := own_bells(snapshot, day, bells)):
            out_day["bl"] = own
        if marks and (mark := _unread_mark(snapshot, group_id, day)):
            out_day["un"] = mark
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
    editing: bool = False,
) -> dict:
    """`status` — для всех версий; `refresh` — состояние обновления без
    непрочитанных дней (их список — `unread`), его читают новые. `editing` —
    владелец сказал, что колледж правит таблицу: `refresh` тогда `editing`."""
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
    if editing:
        # Прежние версии знают только `stale` и `ok` — у них остаётся сбой.
        out["refresh"] = "editing"
    elif snapshot.unread:
        out["refresh"] = refresh or status
    if snapshot.unread:
        # Только даты: у какой группы — в её расписании. День без даты в
        # листе задевает все 190 групп, а /v1/meta спрашивают чаще всего.
        out["unread"] = sorted({day.isoformat() for days in snapshot.unread.values() for day in days})
    return out


def _iso(value: datetime) -> str:
    return value.astimezone(UTC).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )
