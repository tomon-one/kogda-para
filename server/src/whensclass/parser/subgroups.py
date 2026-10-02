"""Две подгруппы в одной ячейке: «X (Пр)/Y (Пр)».

Так колледж пишет подгруппы с разными языками, когда блок не делит пополам:
предметы, преподаватели и аудитории — через косую черту, в том же порядке
(аудитории — «281/269», «467/55/1»). Это две пары с одним номером, как у
половинок блока: одной целой парой отмена у одной подгруппы отменяла бы обе.
"""

from __future__ import annotations

from .cells import _HAS_LETTER, _KIND_ANY_RE, _ROOM_RE, _UNASSIGNED_ROOMS, _URL_RE, normalize


def split_subgroups(subject_raw: str, room_raw: str, teacher_raw: str) -> list[tuple[str, str, str]] | None:
    """Ячейки пары -> ячейки двух подгрупп; None, если подгруппа одна.

    Подгруппы — только если обе части названия с буквами и со своим типом
    занятия в скобках: «Информатика/программирование» — одна пара. Аудитории и
    преподаватели делятся, когда делятся однозначно, иначе достаются обеим.
    """
    subject = normalize(subject_raw)
    if _URL_RE.search(subject):
        return None
    parts = [p.strip() for p in subject.split("/")]
    if len(parts) != 2 or not all(_HAS_LETTER.search(p) and _KIND_ANY_RE.search(p) for p in parts):
        return None
    rooms = _split_rooms(normalize(room_raw)) or (room_raw, room_raw)
    names = normalize(teacher_raw).split("/")
    teachers = [n.strip() for n in names] if len(names) == 2 else [teacher_raw, teacher_raw]
    return [(parts[i], rooms[i], teachers[i]) for i in range(2)]


def _split_rooms(text: str) -> tuple[str, str] | None:
    """«281/269» -> («281», «269»); «257/» -> («257», «»); «467/55/1» ->
    («467», «55/1»): кабинет «55/1» бывает, а «1» — это «не назначен»."""
    if "/" not in text or _URL_RE.search(text):
        return None
    pieces = text.split("/")
    options = []
    for cut in range(1, len(pieces)):
        left, right = "/".join(pieces[:cut]).strip(), "/".join(pieces[cut:]).strip()
        if all(not p or _ROOM_RE.match(p) for p in (left, right)) and not (
            left in _UNASSIGNED_ROOMS or right in _UNASSIGNED_ROOMS
        ):
            options.append((left, right))
    return options[0] if len(options) == 1 else None
