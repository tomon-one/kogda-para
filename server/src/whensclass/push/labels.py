"""Подписи уведомлений сайта: день, аудитория, тип занятия, вебинар, сравнение
названий. Общие для изменений и напоминаний; правила — как в приложении.
"""

from __future__ import annotations

import datetime as dt
import re
import urllib.parse

# Дни и месяцы для «вт, 29 сентября», как formatDayDate в приложении.
WEEKDAYS = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]


MONTHS = [
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
]


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
    """«вместо: X» — в любом месте приписки: разбор дописывает его через «; »
    к причине отмены или к тексту рядом со ссылкой."""
    for part in (lesson.get("c") or "").split(";"):
        part = part.strip()
        if part.startswith(INSTEAD):
            return part[len(INSTEAD):].strip() or None
    return None


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


MAX_FUZZY = 200
# Пометка зачёта после типа пары («… (Пр) ЗАЧЕТ») остаётся в названии, а пара та же.
_MARK = re.compile(r" (зачет|зачёт|зачеты|экзамен)$")


def same_subject(a: str, b: str) -> bool:
    """Та же пара под чуть другим названием: исправили опечатку, регистр или
    точку («Обествознание» → «Обществознание»), или у одной записи полное
    «А / Б», у другой — только «А», или колледж дописал к паре «ЗАЧЕТ»: это не
    «убрали» и «добавилась». ScheduleDiff.sameSubject."""
    if a == b:
        return True
    na, nb = _norm(a), _norm(b)
    if na == nb or _MARK.sub("", na) == _MARK.sub("", nb):
        return True
    head_a, head_b = _norm(a.split("/")[0]), _norm(b.split("/")[0])
    if head_a and head_a == head_b:
        return True
    # Расстояние правки — квадрат длины: на названии в десятки тысяч знаков
    # это минуты внутри захода. Опечатки правят в обычных названиях.
    if max(len(na), len(nb)) > MAX_FUZZY or abs(len(na) - len(nb)) > 2:
        return False
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
