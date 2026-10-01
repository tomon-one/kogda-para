"""Запись файлов состояния целиком или никак."""

from __future__ import annotations

import json
import os
import pathlib
import tempfile


def write_json(path: pathlib.Path, data: object, indent: int | None = None) -> None:
    """JSON — во временный файл рядом и переименованием на место: читатель и
    перезапуск посреди записи не увидят половину файла.

    Временный — свой у каждой записи: служба и канарейка пишут одни файлы, и с
    общим именем `*.tmp` одна усекала бы файл, который другая переименовывает.
    Права — 0600, как у mkstemp: в push.json адреса подписок и их ключи, а
    машина общая.
    """
    fd, name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            out.write(json.dumps(data, ensure_ascii=False, indent=indent))
        os.replace(name, path)
    except BaseException:
        pathlib.Path(name).unlink(missing_ok=True)
        raise
