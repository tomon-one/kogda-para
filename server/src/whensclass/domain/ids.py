"""Стабильные идентификаторы групп.

Слаг группы попадает в настройки на телефоне пользователя и в URL API.
Менять правила транслитерации после первого релиза нельзя: у всех, кто уже
выбрал группу, идентификатор в DataStore перестанет совпадать с сервером.
Правила зафиксированы в docs/api.md.
"""

from __future__ import annotations

import re

_TRANSLIT = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e",
    "ж": "zh", "з": "z", "и": "i", "й": "y", "к": "k", "л": "l", "м": "m",
    "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "h", "ц": "c", "ч": "ch", "ш": "sh", "щ": "sch",
    "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
}


def group_id(name: str) -> str:
    """'ИСП-924/2' -> 'isp-924-2', '01-26.РКИ.ОФ.9' -> '01-26-rki-of-9'."""
    out = []
    for ch in name.strip().lower():
        if ch in _TRANSLIT:
            out.append(_TRANSLIT[ch])
        elif ch.isascii() and ch.isalnum():
            out.append(ch)
        else:
            out.append("-")
    slug = re.sub(r"-{2,}", "-", "".join(out)).strip("-")
    if not slug:
        raise ValueError(f"группа {name!r} не даёт непустого идентификатора")
    return slug
