"""ETag по телу ответа.

Считаем от самих байтов, а не от времени разбора: если таблицу перечитали, но
она не изменилась, телефон не должен качать те же два килобайта заново.
"""

from __future__ import annotations

from hashlib import blake2b


def etag_for(body: bytes) -> str:
    return 'W/"' + blake2b(body, digest_size=12).hexdigest() + '"'


def matches(header: str | None, etag: str) -> bool:
    """Проверяет If-None-Match, в том числе список значений и «*»."""
    if not header:
        return False
    candidates = {x.strip() for x in header.split(",")}
    return "*" in candidates or etag in candidates
