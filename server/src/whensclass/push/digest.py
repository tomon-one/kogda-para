"""Вечерняя сводка: первая пара завтра — одной строкой.

Считается по тому же ответу, что уходит клиентам (push.service._payload), и
только когда день прочитан: по непрочитанному дню «завтра к 3-й» было бы
выдумкой. Нет пар — нет уведомления.
"""

from __future__ import annotations

import datetime as dt

# Время сводки — минута дня по часам колледжа; 0 — выключено. Вечером: раньше
# 16:00 «завтра» ещё не думают, позже 23:59 не бывает.
DIGEST_MIN = 16 * 60
DIGEST_MAX = 23 * 60 + 59
DEFAULT_DIGEST = 20 * 60


def evening(today: dt.date) -> bool:
    """Приходит ли сводка вечером этого дня: накануне учебного дня. В субботу
    вечером — нет: в воскресенье пар не бывает."""
    return (today + dt.timedelta(days=1)).weekday() != 6


def text(payload: dict | None, tomorrow: dt.date) -> tuple[str, str] | None:
    """Заголовок и текст сводки; None — сказать нечего (дня нет, он не
    прочитан или пар в нём нет)."""
    if not isinstance(payload, dict):
        return None
    day = next((d for d in payload.get("days", []) if d.get("d") == tomorrow.isoformat()), None)
    if day is None or day.get("un"):
        return None
    bells = day.get("bl") or payload.get("bells") or {}
    lessons = [lesson for lesson in day.get("l", []) if not lesson.get("x")]
    if not lessons:
        return None

    def start(lesson: dict) -> str | None:
        range_ = bells.get(str(lesson.get("n"))) or []
        return range_[0] if range_ else None

    # Раньше всех начинается — она и первая; без звонка (не бывает, но) — по номеру.
    first = min(lessons, key=lambda lesson: (start(lesson) or "99:99", lesson.get("n", 0)))
    parts = [
        (first.get("sn") or first.get("s") or "").strip(),
        start(first) or "",
        _place(first) or "",
    ]
    body = ", ".join(part for part in parts if part)
    return f"Завтра к {first.get('n')}-й паре", body


def _place(lesson: dict) -> str | None:
    """«ауд. 214», «онлайн»; пусто у пары без места."""
    room = (lesson.get("r") or "").strip()
    online = bool(lesson.get("o")) or (lesson.get("u") and not room)
    if online:
        return "онлайн · " + room if room else "онлайн"
    if not room or room in ("0", "1"):
        return None
    return "ауд. " + room
