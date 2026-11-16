"""Текст ответа бота ВКонтакте.

Сборка — только из существующего `schedule_payload`: те же звонки, пометки
непрочитанного и границы выложенного, что отдаёт API. Своих правил о том, что
показывать, здесь нет — только раскладка по строкам сообщения.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from ..api.payloads import schedule_payload
from ..domain.models import GroupRef, Snapshot
from ..service.bells import BELLS

# Одно сообщение ВКонтакте — не больше 4096 знаков.
MESSAGE_LIMIT = 4096

WEEKDAYS = ("пн", "вт", "ср", "чт", "пт", "сб", "вс")

SOURCE_LINE = "Источник: kogda-para-nsk.ru"

HELP_TEXT = (
    "Напишите группу — и бот ответит парами на сегодня и завтра.\n"
    "Как удобно: ИСП-924/2, исп 924 2 или ИСП 924-2.\n"
    "Регистр, пробелы и дефисы не важны."
)

AMBIGUOUS_PREFIX = "Найдено несколько групп — уточните:"
MANY_TEXT = "Найдено слишком много групп — напишите точнее."

# Приветствия, на которые отвечают инструкцией, а не поиском группы.
_GREETINGS = {"начать", "start", "расписание", "пары"}


def normalize(text: str) -> str:
    """Ключ для сравнения: строчные, ё -> е, не-буквенно-цифровое -> «-».

    «исп 924 2» и имя «ИСП-924/2» сходятся к одному ключу. Кириллица и
    латиница остаются разными — их сравнивают отдельно по id и по name.
    """
    out = []
    for ch in text.strip().lower():
        ch = "е" if ch == "ё" else ch
        out.append(ch if ch.isalnum() else "-")
    return re.sub(r"-{2,}", "-", "".join(out)).strip("-")


@dataclass(frozen=True)
class Lookup:
    """Итог поиска группы: одна группа, несколько, слишком много или помощь."""

    kind: str  # group | ambiguous | many | help
    group: GroupRef | None = None
    names: tuple[str, ...] = ()


def find_group(snapshot: Snapshot, text: str) -> Lookup:
    """Ищет группу по сообщению, не запоминая никого и ничего.

    Сначала точное совпадение с id или с нормализованным именем, затем
    подстрока по имени/id без учёта регистра. Несколько совпадений — список
    имён, больше десяти — просьба уточнить, ничего — инструкция.
    """
    key = normalize(text)
    if not key:
        return Lookup("help")

    for group in snapshot.groups:
        if group.id == key or normalize(group.name) == key:
            return Lookup("group", group=group)

    if key in _GREETINGS:
        return Lookup("help")

    matched = [
        group
        for group in snapshot.groups
        if key in normalize(group.name) or key in normalize(group.id)
    ]
    if not matched:
        return Lookup("help")
    if len(matched) == 1:
        return Lookup("group", group=matched[0])
    if len(matched) <= 10:
        return Lookup("ambiguous", names=tuple(g.name for g in matched))
    return Lookup("many")


def reply_text(
    snapshot: Snapshot,
    text: str,
    generated: datetime,
    today: date,
) -> str:
    """Полный ответ на сообщение человека: от инструкции до расписания."""
    lookup = find_group(snapshot, text)
    if lookup.kind == "help":
        return HELP_TEXT
    if lookup.kind == "many":
        return MANY_TEXT
    if lookup.kind == "ambiguous":
        return AMBIGUOUS_PREFIX + "\n" + "\n".join(f"· {name}" for name in lookup.names)
    return schedule_text(snapshot, lookup.group, generated, today)


def schedule_text(
    snapshot: Snapshot,
    group: GroupRef,
    generated: datetime,
    today: date,
) -> str:
    """Расписание группы на сегодня и завтра, строками сообщения."""
    payload = schedule_payload(
        snapshot, group.id, today, 2, generated, bells=BELLS, today=today, marks=True
    )
    if payload is None:
        return HELP_TEXT

    days = {day["d"]: day for day in payload.get("days", [])}
    blocks: list[list[str]] = [[group.name]]
    for offset, label in ((0, "Сегодня"), (1, "Завтра")):
        day = today + timedelta(days=offset)
        entry = days.get(day.isoformat())
        if entry is None:
            block = [_heading(label, day), "Расписание ещё не опубликовано"]
        else:
            block = [_heading(label, day), *_day_lines(entry)]
        blocks.append(block)
    blocks.append([SOURCE_LINE])
    return _fit(blocks, MESSAGE_LIMIT)


def _heading(label: str, day: date) -> str:
    return f"{label}, {WEEKDAYS[day.weekday()]} {day.day}.{day.month}"


def _day_lines(entry: dict) -> list[str]:
    """Строки одного дня: пометка непрочитанного, пары или «Пар нет»."""
    mark = entry.get("un")
    if mark == "missing":
        return ["Не прочитан"]
    lines = ["Не прочитан, показано прежнее"] if mark == "kept" else []
    bells = entry.get("bl") or BELLS
    for lesson in entry.get("l", []):
        lines.append(_lesson_line(lesson, bells))
        if lesson.get("u"):
            lines.append(lesson["u"])
    if not entry.get("l") and mark is None:
        lines.append("Пар нет")
    return lines


def _lesson_line(lesson: dict, bells: dict[str, list[str]]) -> str:
    """«1) 09:00–10:30 Математика (Лек) · каб. 305 · Иванов И.И.»."""
    number = lesson.get("n")
    span = bells.get(str(number)) if number is not None else None
    time = "–".join(span) + " " if span else ""
    parts = [f"{number}) {time}{lesson.get('s', '')}"]
    if lesson.get("k"):
        parts[0] += f" ({lesson['k']})"
    if lesson.get("o"):
        parts.append("онлайн")
    elif lesson.get("r"):
        parts.append(f"каб. {lesson['r']}")
    if lesson.get("t"):
        parts.append(", ".join(lesson["t"]))
    if lesson.get("x"):
        parts.append("отменена")
    if lesson.get("c"):
        parts.append(lesson["c"])
    return " · ".join(parts)


_LESSON_RE = re.compile(r"^\d+\)\s")


def _droppable(line: str) -> bool:
    """Строка пары или ссылки: её можно обрезать, заголовки и «Пар нет» — нет."""
    return _LESSON_RE.match(line) is not None or line.startswith(("http://", "https://"))


def _fit(blocks: list[list[str]], limit: int) -> str:
    """Собирает сообщение и, если не влезает, обрезает пары с конца.

    Обрезается завтра раньше сегодняшнего (он идёт позже в списке строк),
    заголовки, «Пар нет» и пометки непрочитанного остаются.
    """
    lines: list[str] = []
    for block in blocks:
        if lines:
            lines.append("")
        lines.extend(block)

    text = "\n".join(lines)
    if len(text) <= limit:
        return text

    cut = False
    while len("\n".join(lines)) + 2 > limit:
        for i in range(len(lines) - 1, -1, -1):
            if _droppable(lines[i]):
                del lines[i]
                cut = True
                break
        else:
            break
    if cut:
        lines.insert(len(lines) - 1, "…")
    return "\n".join(lines)
