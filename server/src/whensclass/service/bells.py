"""Расписание звонков.

В таблице колледжа времени нет — только номера пар. Держим сетку здесь, а не
в приложении: поправить время на сервере можно за минуту, а обновление APK у
всех одногруппников — история на неделю.

Пока сетка пустая: выдумывать время занятий нельзя, человек придёт не в тот
час. Виджет в этом случае показывает только номера пар. Как только владелец
пришлёт настоящие звонки, они кладутся в BELLS или в файл, указанный
переменной окружения WHENSCLASS_BELLS (тот же формат, JSON).
"""

from __future__ import annotations

import json
import logging
import os
import pathlib

log = logging.getLogger(__name__)

# Номер пары -> ["начало", "конец"] в местном времени, 24 часа.
# Пример заполнения: {"1": ["08:30", "10:00"], "2": ["10:10", "11:40"]}
BELLS: dict[str, list[str]] = {}


def load_bells() -> dict[str, list[str]]:
    path = os.environ.get("WHENSCLASS_BELLS")
    if not path:
        return dict(BELLS)
    try:
        data = json.loads(pathlib.Path(path).read_text("utf-8"))
    except (OSError, ValueError) as exc:
        log.error("не смог прочитать звонки из %s: %s", path, exc)
        return dict(BELLS)
    return {str(k): list(v) for k, v in data.items()}
