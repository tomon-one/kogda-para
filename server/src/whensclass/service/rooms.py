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
# Кабинет, встретившийся один раз, — скорее опечатка, чем аудитория.
MIN_SEEN = 2

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


def _clash(entries: list[dict]) -> bool:
    """Разные пары в одном кабинете: две строки у разных групп с разными
    преподавателями (без преподавателя — с разными предметами). Разные
    преподаватели у одной группы — её подгруппы, а не накладка."""
    for i, a in enumerate(entries):
        for b in entries[i + 1:]:
            if a["groups"] & b["groups"]:
                continue
            if (a["t"] or b["t"]) and set(a["t"]) != set(b["t"]):
                return True
            if not a["t"] and not b["t"] and a["s"] != b["s"]:
                return True
    return False


def rooms_payload(snapshot: Snapshot, day: dt.date, bells: dict[str, list[str]]) -> dict:
    # Встречи кабинета — разные (день, пара): общая пара нескольких групп —
    # одна ячейка листа, а не несколько.
    seen: dict[tuple[str, str], set] = defaultdict(set)
    for by_date in snapshot.schedule.values():
        for day_, lessons in by_date.items():
            for x in lessons:
                if not x.online and (place := room_of(x.room)):
                    seen[place].add((day_, x.number))
    known = {place for place, slots in seen.items() if len(slots) >= MIN_SEEN}

    names = {g.id: g.name for g in snapshot.groups}
    busy: dict[int, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    unread = []
    for gid, by_date in snapshot.schedule.items():
        if day in snapshot.unread.get(gid, {}):
            unread.append(names.get(gid, gid))
            continue
        for x in by_date.get(day, []):
            # Пара со ссылкой, но не онлайн («преподаватель на онлайн, студенты
            # в кабинете 269») кабинет занимает: онлайн ли она, решает `online`.
            if x.cancelled or x.online:
                continue
            place = room_of(x.room)
            if place is None:
                continue
            entry = {"g": names.get(gid, gid), "s": x.subject, "t": list(x.teachers)}
            if entry not in busy[x.number][place[1]]:
                busy[x.number][place[1]].append(entry)

    # Поток — одна пара на несколько групп: тот же предмет у того же
    # преподавателя — одной строкой, группы через запятую.
    for rooms in busy.values():
        for room, entries in rooms.items():
            merged: dict[tuple, dict] = {}
            for e in entries:
                key = (e["s"], tuple(e["t"]))
                if key in merged:
                    merged[key]["g"] += ", " + e["g"]
                    merged[key]["groups"].add(e["g"])
                else:
                    merged[key] = {**e, "groups": {e["g"]}}
            rooms[room] = list(merged.values())

    clashes = []
    for number in sorted(busy):
        for room, entries in sorted(busy[number].items()):
            if _clash(entries):
                clashes.append({"n": number, "room": room, "lessons": entries})
    for rooms in busy.values():
        for entries in rooms.values():
            for e in entries:
                e.pop("groups", None)

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
