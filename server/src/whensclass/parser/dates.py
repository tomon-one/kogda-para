"""Дата в колонке A листа: «02.09.2026 среда».

Дату пишут руками; день недели рядом с ней — только сверка числа, а
расхождение — отказ. Номера строк дней нужны ссылке «открыть таблицу».
"""

from __future__ import annotations

import re
from datetime import date

from ..domain.models import SourceFormatChanged


# Дату пишут руками, поэтому берём и «2.09.2026 среда», и «02.09.2026, среда»,
# и «07/09/2026», и «понедельник 07.09.2026», и запись без дня недели. День
# недели всё равно только сверяем, доверяя числу.
_DATE_RE = re.compile(r"(\d{1,2})[./](\d{1,2})[./](\d{2,4})")

_WEEKDAYS = (
    "понедельник", "вторник", "среда",
    "четверг", "пятница", "суббота", "воскресенье",
)


def _parse_date(cell: str) -> date | None:
    """'02.09.2026 среда' -> date. День недели служит только сверкой."""
    text = (cell or "").replace("\xa0", " ").strip()
    m = _DATE_RE.search(text)
    if not m:
        return None
    day, month, year = m.groups()
    # День недели — любое слово рядом с датой, до или после неё.
    words = [w.strip(" ,.;") for w in (text[: m.start()] + " " + text[m.end():]).split()]
    weekday = next((w for w in words if w.lower() in _WEEKDAYS), None)
    number = int(year)
    if number < 100:
        # «02.09.26» тоже встречается: век дописываем сами.
        number += 2000
    try:
        value = date(number, int(month), int(day))
    except ValueError:
        # Числа есть, а даты не выходит — «32.13.2026». Не наше дело гадать,
        # что имелось в виду: пусть обход решает, что формат поехал.
        return None
    if weekday is None:
        # День недели пишут не всегда. Он всё равно только сверка.
        return value
    expected = _WEEKDAYS[value.weekday()]
    if weekday.lower() != expected:
        # Раньше — «верю числу» в журнал: одна цифра, и понедельник уезжал в
        # воскресенье, а неделя без пропуска воскресенья раздавала всем пары
        # следующего дня при ok. В архиве 14–24.09
        # слово и число не расходились ни разу.
        raise SourceFormatChanged(
            f"дата {value:%d.%m.%Y} помечена как {weekday.lower()}, а это {expected}"
        )
    return value


def date_rows(first_column: list[str]) -> dict[date, int]:
    """Номера строк дней по колонке A сырого листа, как их видит человек в Sheets.

    Из CSV от gviz номера строк было не достать: он схлопывал шапку в одну
    строку и выбрасывал пустые строки посреди листа — на 13.09.2026 сырых
    строк 210, в CSV 199, и сдвиг рос вниз по листу с четырёх до одиннадцати.
    В сыром экспорте строки настоящие. Нужны они ссылке «открыть таблицу»:
    `range=EQ139` подводит к ячейке, а не к верху листа.
    """
    rows: dict[date, int] = {}
    for index, cell in enumerate(first_column):
        try:
            found = _parse_date(cell)
        except SourceFormatChanged:
            # Отказ со строкой листа даст обход (`parse_sheet`).
            continue
        if found is not None and found not in rows:
            rows[found] = index + 1
    return rows
