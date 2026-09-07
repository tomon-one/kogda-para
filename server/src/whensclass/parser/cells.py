"""Разбор одной ячейки таблицы колледжа.

Здесь собраны все грязные частности источника, чтобы при очередной переделке
таблицы править приходилось в одном файле. Наблюдённые случаи описаны в
docs/source-format.md.
"""

from __future__ import annotations

import logging
import re

from ..domain.models import Lesson

log = logging.getLogger(__name__)

# Тип занятия таблица пишет в хвосте названия: «Информатика (Лек)».
_KIND_RE = re.compile(r"\s*\(\s*([^()]{1,12}?)\s*\)\s*$")

# Заполняют руками, поэтому один и тот же тип встречается в разном регистре
# («Лек», «лек», «ПР»). Приводим к одному написанию, чтобы виджет не показывал
# две разные пометки для одного и того же.
_KNOWN_KINDS = {
    k.casefold(): k
    for k in ("Лек", "Пр", "Лаб", "Конс", "Экз", "Зач", "Сем", "Диф.зач", "Курс.р.")
}

# Отмену дописывают как придётся: отдельной строкой внутри ячейки,
# в конце названия предмета («Безопасность жизнедеятельности (Лек) Отмена»),
# в колонке аудитории («ОТМЕНА 279») и с причиной следом («Отмена
# преподаватель заболел»). Ищем слово где угодно, но целиком.
_CANCEL_RE = re.compile(r"\bотмена\b", re.IGNORECASE)


def normalize(raw: str | None) -> str:
    """Чистит пробелы, но сохраняет переводы строк: в них смысл.

    Внутри ячейки вторая строка несёт отдельное сообщение — чаще всего отмену.
    """
    if not raw:
        return ""
    text = raw.replace("\xa0", " ").replace("\r\n", "\n").replace("\r", "\n")
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in text.split("\n")]
    return "\n".join(line for line in lines if line)


def _extract_cancellation(text: str, tail: str) -> tuple[str, bool, str | None]:
    """Убирает пометку отмены, возвращает остаток, признак и приписку.

    Что делать с текстом после слова «отмена», зависит от колонки: в колонке
    аудитории это сама аудитория, в колонке предмета — причина.

        _extract_cancellation("ОТМЕНА 279", tail="keep")
            -> ("279", True, None)
        _extract_cancellation("БЖД (Лек) Отмена преподаватель заболел", "note")
            -> ("БЖД (Лек)", True, "преподаватель заболел")
    """
    kept: list[str] = []
    note_parts: list[str] = []
    cancelled = False

    for line in text.split("\n"):
        m = _CANCEL_RE.search(line)
        if not m:
            kept.append(line)
            continue
        cancelled = True
        head = line[: m.start()].strip()
        rest = line[m.end():].strip(" .,;:-")
        if head:
            kept.append(head)
        if rest:
            (kept if tail == "keep" else note_parts).append(rest)

    note = " ".join(note_parts).strip() or None
    return "\n".join(kept).strip(), cancelled, note


def _split_kind(subject: str) -> tuple[str, str | None]:
    m = _KIND_RE.search(subject)
    if not m:
        return subject.strip(), None
    raw = m.group(1).strip()
    kind = _KNOWN_KINDS.get(raw.casefold())
    if kind is None:
        # Новый вид занятия не должен ронять весь день — запоминаем как есть.
        log.warning("незнакомый тип занятия %r в %r", raw, subject)
        kind = raw
    return subject[: m.start()].strip(), kind


def parse_lesson(
    number: int,
    subject_raw: str | None,
    room_raw: str | None,
    teacher_raw: str | None,
) -> Lesson | None:
    """Собирает пару из трёх ячеек. None, если пары нет."""
    subject, cancel_a, note = _extract_cancellation(normalize(subject_raw), tail="note")
    room_text, cancel_b, _ = _extract_cancellation(normalize(room_raw), tail="keep")
    teacher_text = normalize(teacher_raw)

    if not subject and not room_text and not teacher_text:
        return None

    subject, kind = _split_kind(subject.replace("\n", " ").strip())

    url = None
    room: str | None = None
    if room_text:
        first = room_text.split("\n")[0].strip()
        if first.lower().startswith(("http://", "https://")):
            url = first
        else:
            room = room_text.replace("\n", " ").strip()

    teachers = tuple(t for t in teacher_text.split("\n") if t)

    return Lesson(
        number=number,
        subject=subject,
        kind=kind,
        teachers=teachers,
        room=room,
        url=url,
        cancelled=cancel_a or cancel_b,
        note=note,
    )
