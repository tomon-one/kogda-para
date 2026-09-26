"""Расписание звонков.

В таблице колледжа времени нет — только номера пар. Держим сетку здесь, а не
в приложении: поправить время на сервере можно за минуту, а обновление APK у
всех одногруппников — история на неделю.

Сетка ниже — с официальной картинки колледжа от 7 сентября 2026 года. Если
её поменяют, достаточно поправить здесь и перезапустить службу либо положить
JSON того же вида в файл из переменной WHENSCLASS_BELLS — пересобирать APK и
раздавать его заново не придётся.
"""

from __future__ import annotations

import json
import logging
import os
import pathlib
import re

log = logging.getLogger(__name__)

# Номер пары -> ["начало", "конец"] в местном времени, 24 часа.
BELLS: dict[str, list[str]] = {
    "1": ["09:00", "10:30"],
    "2": ["10:40", "12:10"],
    "3": ["12:30", "14:00"],
    "4": ["14:20", "15:50"],
    "5": ["16:00", "17:30"],
    "6": ["17:40", "19:10"],
}


def load_bells() -> dict[str, list[str]]:
    path = os.environ.get("WHENSCLASS_BELLS")
    if not path:
        return dict(BELLS)
    try:
        data = json.loads(pathlib.Path(path).read_text("utf-8"))
    except (OSError, ValueError) as exc:
        log.error("не смог прочитать звонки из %s: %s", path, exc)
        return dict(BELLS)
    problem = _shape_problem(data)
    if problem:
        # JSON правильный, но не того вида: массив или null валили запуск
        # службы и отдавали 500 на каждый запрос, а строка вместо пары
        # времён раздавалась телефонам посимвольно (третий аудит, В18
        # прогона 2). Сетка — та, что в коде.
        log.error("звонки в %s не того вида (%s) — беру сетку из кода", path, problem)
        return dict(BELLS)
    return {str(k): list(v) for k, v in data.items()}


_TIME = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


def _shape_problem(data: object) -> str | None:
    """Что не так с сеткой из файла: «номер пары -> [начало, конец]»."""
    if not isinstance(data, dict) or not data:
        return f"ждал непустой объект, а там {type(data).__name__}"
    for key, value in data.items():
        if not str(key).isdigit():
            return f"номер пары {key!r} — не число"
        if not isinstance(value, list) or len(value) != 2:
            return f"у пары {key} не пара времён: {value!r}"
        if not all(isinstance(x, str) and _TIME.match(x) for x in value) or value[0] >= value[1]:
            return f"у пары {key} время не «ЧЧ:ММ» по порядку: {value!r}"
    return None
