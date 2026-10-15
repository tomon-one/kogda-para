"""Аудитории: какие заняты на каждой паре дня, какие свободны по расписанию, накладки.

Считается из того же снимка, что и расписание групп. «Свободна» — только по
таблице: ключей, устных переносов и чужих мероприятий она не знает, поэтому
клиенты так и пишут — «по расписанию».

Кабинеты — те, что встречались в листе: номер («269», «55/2», «262А») — в
главном корпусе, «Восход 222» — в корпусе на Восходе. Спортзалы, актовый зал,
фойе, музеи и адреса — не кабинеты: свободными их не показываем, а общая пара
в них — не накладка.
"""

from __future__ import annotations

import datetime as dt
import re
from collections import defaultdict

from ..domain.models import Snapshot

MAIN = "Главный корпус"
VOSKHOD = "Восход"
# Кабинет, встретившийся реже, — опечатка или разовое место, а не аудитория.
MIN_SEEN = 5

_NUMBER = re.compile(r"\d{2,3}(?:/\d)?[А-ЯA-Z]?", re.IGNORECASE)
_VOSKHOD = re.compile(r"Восход\s+(\d{3})", re.IGNORECASE)


def room_of(raw: str | None) -> tuple[str, str] | None:
    """«269» -> (главный корпус, «269»), «Восход 222» -> (Восход, «Восход 222»); иное — None."""
    text = " ".join((raw or "").split())
    if _NUMBER.fullmatch(text):
        return MAIN, text.upper()
    if m := _VOSKHOD.fullmatch(text):
        return VOSKHOD, f"Восход {m[1]}"
    return None


def _floor(building: str, room: str) -> str:
    digits = re.sub(r"\D", "", room.split("/")[0])
    return digits[0] if len(digits) == 3 else ""


def rooms_payload(snapshot: Snapshot, day: dt.date, bells: dict[str, list[str]]) -> dict:
    seen: dict[tuple[str, str], int] = defaultdict(int)
    for by_date in snapshot.schedule.values():
        for lessons in by_date.values():
            for x in lessons:
                if not (x.online or x.url) and (place := room_of(x.room)):
                    seen[place] += 1
    known = {place for place, n in seen.items() if n >= MIN_SEEN}

    names = {g.id: g.name for g in snapshot.groups}
    busy: dict[int, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    unread = []
    for gid, by_date in snapshot.schedule.items():
        if day in snapshot.unread.get(gid, {}):
            unread.append(names.get(gid, gid))
            continue
        for x in by_date.get(day, []):
            if x.cancelled or x.online or x.url:
                continue
            place = room_of(x.room)
            if place is None:
                continue
            entry = {"g": names.get(gid, gid), "s": x.subject, "t": list(x.teachers)}
            if entry not in busy[x.number][place[1]]:
                busy[x.number][place[1]].append(entry)

    clashes = []
    for number in sorted(busy):
        for room, entries in sorted(busy[number].items()):
            # Общая пара (поток): тот же преподаватель — одна пара на несколько групп.
            teachers = {tuple(e["t"]) for e in entries if e["t"]}
            subjects = {e["s"] for e in entries}
            if len(entries) > 1 and (len(teachers) > 1 or (not teachers and len(subjects) > 1)):
                clashes.append({"n": number, "room": room, "lessons": entries})

    buildings = []
    for building in (MAIN, VOSKHOD):
        rooms = sorted(
            (room for b, room in known if b == building),
            key=lambda r: (int(re.sub(r"\D", "", r.split("/")[0]) or 0), r),
        )
        if rooms:
            buildings.append({
                "name": building,
                "rooms": [{"room": r, "floor": _floor(building, r)} for r in rooms],
            })
    return {
        "date": day.isoformat(),
        "bells": bells,
        "buildings": buildings,
        "busy": {str(n): {room: entries for room, entries in sorted(rooms.items())} for n, rooms in sorted(busy.items())},
        "clashes": clashes,
        # Группы, у которых день не прочитан: их кабинеты не учтены.
        "unread": sorted(unread),
    }
