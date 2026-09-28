"""Сведения о последней сборке приложения.

Приложение раздаётся файлом, а не через магазин, поэтому о новой версии оно
узнаёт отсюда: сравнивает свой номер сборки с тем, что лежит на сервере, и
предлагает обновиться. Сам файл раздаёт nginx из того же каталога.

Данные кладёт скрипт выкладки сборки — руками этот файл писать не нужно.
"""

from __future__ import annotations

import json
import logging
import pathlib

log = logging.getLogger(__name__)

LATEST = "latest.json"
# Канал — какое приложение спрашивает: основное или tested-сборка, на которой
# автор проверяет новое до выкладки всем (docs/versions.md, «Tested»).
CHANNELS = {"main": LATEST, "tested": "latest-tested.json"}


def latest_release(state_dir: pathlib.Path, channel: str = "main") -> dict | None:
    """Читает описание последней выложенной сборки канала. None, если её нет."""
    path = state_dir / "apk" / CHANNELS[channel]
    try:
        data = json.loads(path.read_text("utf-8"))
    except (OSError, ValueError) as exc:
        log.debug("описание сборки не прочиталось: %s", exc)
        return None

    required = ("versionCode", "versionName", "file")
    if not all(key in data for key in required):
        log.warning("в %s нет обязательных полей %s", path, required)
        return None
    return data
