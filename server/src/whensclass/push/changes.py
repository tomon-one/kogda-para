"""Что сказать уведомлением сайта: изменения и напоминания о паре.

Правила — те же, что у приложения, и перенесены из него построчно:
изменения — `ScheduleDiff.kt` и `announceChanges` в `ScheduleRepository.kt`,
напоминания — `LessonAlarms.plan` и `LessonAlarms.text`. Расхождение с
приложением — ошибка: человек с приложением и с сайтом должен получать одно
и то же. Работают по телам ответов API (`schedule_payload`, `teacher_payload`)
— ровно по тому, что видит приложение.
"""

from __future__ import annotations

import datetime as dt
import urllib.parse

from ..service.bells import BELLS

# Дни и месяцы для «вт, 29 сентября», как formatDayDate в приложении.
WEEKDAYS = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
MONTHS = [
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
]

# Больше строк шторка всё равно не покажет развёрнутой (MAX_CHANGE_LINES).
MAX_LINES = 8

# Площадки вебинаров колледжа (WebinarLink.kt).
WEBINAR_DOMAINS = ("mts-link.ru", "webinar.ru", "zoom.us")

INSTEAD = "вместо: "

KINDS = {
    "лек": "Лекция",
    "пр": "Практика",
    "лаб": "Лабораторная",
    "сем": "Семинар",
    "конс": "Консультация",
    "экз": "Экзамен",
    "зач": "Зачёт",
    "диф.зач": "Диф. зачёт",
    "курс.р.": "Курсовая",
}


def day_label(day: dt.date) -> str:
    return f"{WEEKDAYS[day.weekday()]}, {day.day} {MONTHS[day.month - 1]}"


def _cancelled(lesson: dict) -> bool:
    return bool(lesson.get("x"))


def _online(lesson: dict) -> bool:
    # Ссылка без признака и без аудитории — онлайн (LessonDto.isOnline).
    return bool(lesson.get("o")) or (lesson.get("u") is not None and lesson.get("r") is None)


def _replaces(lesson: dict) -> str | None:
    note = lesson.get("c")
    if not note or not note.startswith(INSTEAD):
        return None
    return note[len(INSTEAD):].strip() or None


def _split(url: str) -> urllib.parse.SplitResult | None:
    """Разбор адреса, как у браузера: «\\» — это «/» (WHATWG URL). Кривой адрес
    («https://[…», полноширинная «／») — None, а не исключение: оно уронило бы
    сводку изменений всем подписчикам."""
    try:
        parts = urllib.parse.urlsplit(url.strip().replace("\\", "/"))
        parts.hostname, parts.port  # noqa: B018 — бросают на кривом хосте и порте
    except ValueError:
        return None
    return parts


def known_webinar(url: str) -> bool:
    # «\» браузер читает как «/» и ведёт на хост до неё, а java.net.URI в
    # приложении такой адрес не разбирает вовсе: и там и тут он чужой
    # (format.js).
    if "\\" in url:
        return False
    parts = _split(url)
    if parts is None or parts.scheme.lower() != "https" or not parts.hostname:
        return False
    host = parts.hostname.lower().rstrip(".")
    return any(host == d or host.endswith("." + d) for d in WEBINAR_DOMAINS)


def url_host(url: str) -> str:
    """Хост, куда поведёт ссылка, — для текста «чужой адрес: …»."""
    parts = _split(url)
    return (parts.hostname if parts else None) or url


def _norm(subject: str) -> str:
    return " ".join("".join(c if c.isalnum() else " " for c in subject.casefold()).split())


def _distance(a: str, b: str) -> int:
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        prev, row[0] = row[0], i
        for j, y in enumerate(b, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (x != y))
    return row[-1]


def same_subject(a: str, b: str) -> bool:
    """Та же пара под чуть другим названием: исправили опечатку, регистр или
    точку («Обествознание» → «Обществознание»), или у одной записи полное
    «А / Б», у другой — только «А»: это не «убрали» и «добавилась».
    ScheduleDiff.sameSubject."""
    if a == b:
        return True
    na, nb = _norm(a), _norm(b)
    if na == nb:
        return True
    head_a, head_b = _norm(a.split("/")[0]), _norm(b.split("/")[0])
    if head_a and head_a == head_b:
        return True
    return min(len(na), len(nb)) >= 8 and _distance(na, nb) <= 2


def room_label(room: str | None) -> str | None:
    """«каб. 275» для номера, словесное место — как есть (roomLabel)."""
    text = (room or "").strip()
    if not text:
        return None
    looks_like_number = len(text) <= 8 and all(c.isdigit() or c in "/-.абвгАБВГ" for c in text)
    return f"каб. {text}" if looks_like_number else text


def kind_name(kind: str | None) -> str | None:
    key = (kind or "").strip().lower()
    if not key:
        return None
    return KINDS.get(key, kind)


def compare(old: dict | None, fresh: dict) -> list[tuple[str, str]]:
    """(день, что изменилось) — ScheduleDiff.compare для одного и того же субъекта."""
    if old is None or old.get("g") != fresh.get("g"):
        return []
    teacher = fresh.get("kind") == "teacher"
    name = fresh.get("gn")
    old_days = {d["d"]: d for d in old.get("days", [])}
    changes: list[tuple[str, str]] = []
    for day in fresh.get("days", []):
        before = old_days.get(day["d"])
        if before is None:
            continue
        numbers = sorted({x["n"] for x in before["l"]} | {x["n"] for x in day["l"]})
        for number in numbers:
            _compare_number(
                day["d"], number,
                [x for x in before["l"] if x["n"] == number],
                [x for x in day["l"] if x["n"] == number],
                teacher, name, changes,
            )
    return changes


def _compare_number(
    day: str,
    number: int,
    was: list[dict],
    now: list[dict],
    teacher: bool,
    name: str | None,
    changes: list[tuple[str, str]],
) -> None:
    def whose(lesson: dict) -> str:
        groups = lesson.get("gr")
        if groups is None or (groups == name and not teacher):
            return ""
        return f" ({groups})"

    def say(text: str) -> None:
        changes.append((day, text))

    def groups_of(lesson: dict) -> set[str]:
        value = lesson.get("gr") or (name if not teacher else None)
        return {p.strip() for p in (value or "").split(",") if p.strip()}

    def overlap(a: dict, b: dict) -> bool:
        return bool(groups_of(a) & groups_of(b))

    def listed(lesson: dict, keep: set[str]) -> str:
        """Только эти группы записи — в её порядке."""
        value = lesson.get("gr") or ""
        return ", ".join(p.strip() for p in value.split(",") if p.strip() in keep)

    # Группы на этом номере до и после: у преподавателя записи склеены из
    # групп, и к паре присоединяется или уходит группа — «добавилась» и
    # «убрали» только о ней, а не о всей записи.
    before_groups = set().union(*(groups_of(x) for x in was)) if was else set()
    after_groups = set().union(*(groups_of(x) for x in now)) if now else set()

    unmatched = list(was)

    def kept_as_is(old: dict) -> bool:
        return any(_replaces(x) is None and x["s"] == old["s"] and x.get("t") == old.get("t")
                   and x.get("r") == old.get("r") for x in now)

    # Замена — одной строкой, сервер помечает её «вместо: X».
    replaced: list[dict] = []
    for lesson in now:
        instead = _replaces(lesson)
        if instead is None:
            continue
        if _cancelled(lesson) or any(x["s"] == lesson["s"] for x in was):
            continue
        # Из двух пар с этим названием заменили ту, у которой в новом листе
        # нет точной копии: другая половинка блока осталась как была.
        index = next((i for i, x in enumerate(unmatched)
                      if x["s"] == instead and not kept_as_is(x)), -1)
        if index < 0:
            index = next((i for i, x in enumerate(unmatched) if x["s"] == instead), -1)
        if index < 0:
            index = next((i for i, x in enumerate(unmatched) if same_subject(x["s"], instead)), -1)
        if index < 0:
            continue
        unmatched.pop(index)
        replaced.append(lesson)
        say(f"замена {number} пары{whose(lesson)}: {instead} → {lesson['s']}")

    for lesson in now:
        if any(lesson is r for r in replaced):
            continue
        # Две пары одного названия на номере (подгруппы одного языка) —
        # сначала та, что с тем же преподавателем и кабинетом, иначе по порядку
        # выйдут «другой преподаватель» и «переехала» вместо «убрали».
        ranked = sorted(
            (x.get("t") != lesson.get("t"), x.get("r") != lesson.get("r"), i)
            for i, x in enumerate(unmatched)
            if x["s"] == lesson["s"] and groups_of(x) == groups_of(lesson)
        )
        index = ranked[0][2] if ranked else -1
        if index < 0:
            index = next((i for i, x in enumerate(unmatched)
                          if x["s"] == lesson["s"] and overlap(x, lesson)), -1)
        if index < 0:
            index = next((i for i, x in enumerate(unmatched) if x["s"] == lesson["s"]), -1)
        if index < 0:
            # Та же пара под чуть другим названием — у тех же групп (у
            # преподавателя — пересекающихся) и с тем же преподавателем.
            index = next((i for i, x in enumerate(unmatched)
                          if same_subject(x["s"], lesson["s"]) and overlap(x, lesson)
                          and x.get("t") == lesson.get("t")), -1)
        if index < 0:
            source = next((x for x in was if x["s"] == lesson["s"] and overlap(x, lesson)), None) \
                or next((x for x in was if x["s"] == lesson["s"]), None)
            if source is not None and not _cancelled(source) and _cancelled(lesson):
                say(f"отменили {number} пару{whose(lesson)}: {lesson['s']}")
            elif source is not None and _cancelled(source) and not _cancelled(lesson):
                say(f"вернули {number} пару{whose(lesson)}: {lesson['s']}")
            elif source is not None:
                pass
            elif _cancelled(lesson):
                say(f"отменили {number} пару{whose(lesson)}: {lesson['s']}")
            else:
                say(f"добавилась {number} пара{whose(lesson)}: {lesson['s']}")
            continue

        previous = unmatched.pop(index)
        tag = whose(lesson)
        if teacher:
            added = groups_of(lesson) - before_groups
            if added:
                say(f"добавилась {number} пара ({listed(lesson, added)}): {lesson['s']}")
            left = groups_of(previous) - after_groups
            if left:
                say(f"убрали {number} пару ({listed(previous, left)}): {previous['s']}")
        teachers = lesson.get("t") or []
        if not _cancelled(previous) and _cancelled(lesson):
            say(f"отменили {number} пару{tag}: {lesson['s']}")
        elif _cancelled(previous) and not _cancelled(lesson):
            say(f"вернули {number} пару{tag}: {lesson['s']}")
        elif not _online(previous) and _online(lesson):
            say(f"{number} пара{tag} стала онлайн")
        elif _online(previous) and not _online(lesson):
            say(f"{number} пара{tag} снова очная")
        elif teachers and (previous.get("t") or []) != teachers:
            say(f"у {number} пары{tag} другой преподаватель: {', '.join(teachers)}")

        room = lesson.get("r")
        if not _cancelled(lesson) and room is not None and previous.get("r") != room:
            if _online(lesson):
                say(f"у {number} пары{tag} онлайн-комната {room}")
            else:
                place = room_label(room) or room
                say(f"{number} пара{tag} переехала в {place}" if place.startswith("каб.")
                    else f"{number} пара{tag} переехала: {place}")

        url = lesson.get("u")
        if url is None or url == previous.get("u"):
            pass
        elif not known_webinar(url):
            verb = "появилась" if previous.get("u") is None else "сменилась"
            say(f"у {number} пары{tag} {verb} ссылка — чужой адрес: {url_host(url)}")
        elif previous.get("u") is None:
            say(f"у {number} пары{tag} появилась ссылка")
        else:
            say(f"у {number} пары{tag} сменилась ссылка")

    for gone in unmatched:
        # Та же пара слилась с записью других групп — не новость. С записью тех
        # же групп (половинка блока одного языка) — убрали.
        if any(x["s"] == gone["s"] and groups_of(x) != groups_of(gone) for x in now):
            continue
        if teacher and gone.get("gr"):
            # Все её группы на номере остались при той же паре — запись просто
            # склеилась с другой. При другом предмете у тех же групп это
            # замена, и «убрали» прежний нужен.
            kept = set().union(*(groups_of(x) for x in now if same_subject(x["s"], gone["s"]))) \
                if now else set()
            left = groups_of(gone) - kept
            if not left:
                continue
            say(f"убрали {number} пару ({listed(gone, left)}): {gone['s']}")
            continue
        say(f"убрали {number} пару{whose(gone)}: {gone['s']}")


def change_lines(old: dict | None, fresh: dict, today: dt.date) -> list[list[str]]:
    """Строки уведомления: только сегодня и завтра, «вт, 29 сентября: …».

    День — словами и датой, а не «Завтра»: уведомление висит, и наутро
    «Завтра» читалось бы как новость о послезавтра (announceChanges).
    """
    soon = {today.isoformat(), (today + dt.timedelta(days=1)).isoformat()}
    out: list[list[str]] = []
    for day, text in compare(old, fresh):
        if day in soon:
            out.append([day, f"{day_label(dt.date.fromisoformat(day))}: {text}"])
    # Строки идут по дням, сначала сегодня: обрезка внутри одной правки
    # режет завтрашний хвост, а не срочное сегодняшнее.
    return out[:MAX_LINES]


def reminders(payload: dict, minutes: int, day: dt.date) -> list[dict]:
    """Что и когда напомнить в этот день (LessonAlarms.plan).

    О первой паре дня — всегда; о следующих — только если напоминание
    приходится на перемену, а не на предыдущую пару. Отменённые не в счёт.
    """
    today = next((d for d in payload.get("days", []) if d["d"] == day.isoformat()), None)
    if today is None or minutes <= 0:
        return []
    teacher = payload.get("kind") == "teacher"
    own = None if teacher else payload.get("gn")
    out: list[dict] = []
    busy_until: dt.datetime | None = None
    # Две пары одного номера — подгруппы в разных кабинетах (какая своя,
    # неизвестно) или у преподавателя одна пара в двух залах: напоминание одно
    # и называет все места.
    by_number: dict[int, list[dict]] = {}
    for lesson in today["l"]:
        if not _cancelled(lesson):
            by_number.setdefault(lesson["n"], []).append(lesson)
    for number in sorted(by_number):
        same = by_number[number]
        lesson = same[0]
        bells = BELLS.get(str(number))
        if not bells:
            continue
        start = dt.datetime.combine(day, dt.time.fromisoformat(bells[0]))
        fire = start - dt.timedelta(minutes=minutes)
        during_previous = busy_until is not None and fire <= busy_until
        busy_until = dt.datetime.combine(day, dt.time.fromisoformat(bells[1]))
        if during_previous:
            continue
        names = _distinct(x["s"] for x in same)
        subject = " / ".join(names)
        out.append({
            "at": fire,
            "start": start,
            "end": busy_until,
            "number": number,
            "title": f"{start:%H:%M} — {subject}",
            "subject": subject,
            "text": reminder_text(lesson, own) if len(same) == 1
            else "\n".join([_number_line(same)] + [_pair_line(x, own, len(names) > 1) for x in same]),
        })
    return out


def _place(lesson: dict) -> str | None:
    if _online(lesson):
        room = (lesson.get("r") or "").strip()
        return "Онлайн" + (f", комната {room}" if room else "")
    label = room_label(lesson.get("r"))
    return label[0].upper() + label[1:] if label else None


def _groups(lesson: dict, own_group: str | None) -> str | None:
    groups = (lesson.get("gr") or "").strip()
    return groups if groups and groups != own_group else None


def _sentences(parts: list[str | None]) -> str:
    text = ""
    for part in (p for p in parts if p):
        text = part if not text else text + (" " if text.endswith(".") else ". ") + part
    return text


def reminder_text(lesson: dict, own_group: str | None) -> str:
    """Место первым: напоминание читают по пути (LessonAlarms.text)."""
    kind = kind_name(lesson.get("k"))
    return _sentences([
        _place(lesson),
        f"{lesson['n']} пара" + (f", {kind.lower()}" if kind else ""),
        (lesson.get("t") or [None])[0],
        _groups(lesson, own_group),
    ])


def _distinct(subjects) -> list[str]:
    """Названия без повторов: «Физическая культура» и «Физическая культура.» — одно."""
    seen: dict[str, str] = {}
    for subject in subjects:
        seen.setdefault(subject.strip(" .").casefold(), subject.strip(" ."))
    return list(seen.values())


def _number_line(lessons: list[dict]) -> str:
    """«3 пара, практика» — тип, если он у всех пар номера один."""
    kinds = {kind_name(x.get("k")) for x in lessons}
    kind = kinds.pop() if len(kinds) == 1 else None
    return f"{lessons[0]['n']} пара" + (f", {kind.lower()}" if kind else "")


def _pair_line(lesson: dict, own_group: str | None, with_subject: bool) -> str:
    """Строка одной из пар номера: место, предмет (если предметы разные),
    преподаватель, группы; номер — строкой выше, время — в заголовке."""
    head = " — ".join(p for p in (_place(lesson), lesson["s"] if with_subject else None) if p)
    return _sentences([head, (lesson.get("t") or [None])[0], _groups(lesson, own_group)])
