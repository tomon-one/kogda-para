"""Запись файлов состояния целиком или никак."""

from __future__ import annotations

import json
import pathlib


def write_json(path: pathlib.Path, data: object, indent: int | None = None) -> None:
    """JSON — во временный файл рядом и переименованием на место: читатель и
    перезапуск посреди записи не увидят половину файла."""
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=indent), "utf-8")
    tmp.replace(path)
