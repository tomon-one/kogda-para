"""Поиск листа, покрывающего нужную дату.

Лист в книге живёт примерно две недели, потом появляется новый — с новым gid и
новым именем. Имя при этом врёт: лист «расписание групп 01.-05.09» содержит
02.09–12.09. Поэтому имя годится только чтобы упорядочить кандидатов, а
решение принимается по содержимому.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import pathlib
import re

from ..config import settings
from ..domain.models import SourceFormatChanged
from ..parser.csv_schedule import parse_csv
from . import gsheets
from .gsheets import SheetInfo

log = logging.getLogger(__name__)

# Листов в книге больше сотни: экзамены, аудитории, графики. Расписание групп
# ищем среди тех, чьё имя говорит о расписании и не говорит об индивидуальном.
_LOOKS_LIKE_SCHEDULE = re.compile(r"расписан|график", re.IGNORECASE)
_NOT_SCHEDULE = re.compile(r"индивидуальн|преподавател|аудитор|экз", re.IGNORECASE)
_NUMBERS = re.compile(r"\d{1,2}")


class SheetIndex:
    """Помнит, какой лист какие даты покрывает, чтобы не искать каждый раз."""

    def __init__(self, state_dir: pathlib.Path):
        self.path = state_dir / "sheet_index.json"
        self.known: dict[str, dict] = {}
        self._load()

    def _load(self) -> None:
        try:
            self.known = json.loads(self.path.read_text("utf-8"))
        except (OSError, ValueError):
            self.known = {}

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.known, ensure_ascii=False, indent=1), "utf-8")
        tmp.replace(self.path)

    def remember(self, title: str, gid: str | None, first: dt.date, last: dt.date) -> None:
        self.known[title] = {
            "gid": gid,
            "from": first.isoformat(),
            "to": last.isoformat(),
            "probed": dt.date.today().isoformat(),
        }
        self.save()

    def covering(self, day: dt.date) -> tuple[str, str | None] | None:
        for title, info in self.known.items():
            try:
                first = dt.date.fromisoformat(info["from"])
                last = dt.date.fromisoformat(info["to"])
            except (KeyError, ValueError):
                continue
            if first <= day <= last:
                return title, info.get("gid")
        return None


def list_sheets() -> list[SheetInfo]:
    """Список листов: через API, если есть ключ, иначе через выгрузку xlsx."""
    if settings.sheets_api_key:
        try:
            return gsheets.list_sheets_via_api(settings.sheets_api_key)
        except Exception as exc:  # ключ протух, квота, сеть
            log.warning("Sheets API не ответил (%s), иду через xlsx", exc)
    return gsheets.list_sheets_via_xlsx()


def candidates(sheets: list[SheetInfo], day: dt.date) -> list[SheetInfo]:
    """Кандидаты в порядке правдоподобия: сперва те, чьи числа ближе к дате."""
    picked = [
        s for s in sheets
        if not s.hidden
        and _LOOKS_LIKE_SCHEDULE.search(s.title)
        and not _NOT_SCHEDULE.search(s.title)
    ]

    def distance(sheet: SheetInfo) -> int:
        numbers = [int(n) for n in _NUMBERS.findall(sheet.title)]
        days = [n for n in numbers if 1 <= n <= 31]
        return min((abs(n - day.day) for n in days), default=99)

    return sorted(picked, key=distance)


def resolve_window(
    start: dt.date, days: int, state_dir: pathlib.Path, deep: bool = False
) -> list[tuple[str, str | None]]:
    """Листы, покрывающие ближайшие `days` дней, по порядку.

    Неделя вперёд часто перешагивает границу листа: лист живёт две недели,
    а в пятницу человек уже смотрит понедельник. Поэтому берём не один лист,
    а все, что попадают в окно, — если следующий уже опубликован.

    С `deep=False` соседний лист берём только из памяти: искать его в сети —
    это выгрузка всей книги, и делать её каждые двадцать минут незачем.
    """
    first = resolve_for(start, state_dir)
    sheets = [first]

    if settings.sheet_title or settings.sheet_gid:
        return sheets

    end = start + dt.timedelta(days=days - 1)
    index = SheetIndex(state_dir)
    covered_to = _covered_to(index, first[0])
    if covered_to is None or covered_to >= end:
        return sheets

    # Окно выходит за край текущего листа — ищем следующий.
    following = index.covering(covered_to + dt.timedelta(days=1))
    if following is None:
        if not deep:
            # Искать соседний лист в сети — это выгрузка всей книги на два
            # десятка мегабайт. Раз в сутки (deep) не жалко, каждые двадцать
            # минут — уже расточительство.
            return sheets
        try:
            following = resolve_for(covered_to + dt.timedelta(days=1), state_dir)
        except LookupError:
            log.info("следующий лист ещё не опубликован, отдаём что есть")
            return sheets
    if following and following[0] != first[0]:
        sheets.append(following)
    return sheets


def _covered_to(index: SheetIndex, title: str) -> dt.date | None:
    info = index.known.get(title)
    if not info:
        return None
    try:
        return dt.date.fromisoformat(info["to"])
    except (KeyError, ValueError):
        return None


def resolve_for(day: dt.date, state_dir: pathlib.Path) -> tuple[str, str | None]:
    """Возвращает (название листа, gid) для даты.

    Сначала смотрит в память — обычно этого хватает и в сеть ходить не надо.
    Аварийная настройка из окружения перебивает всё.
    """
    if settings.sheet_title or settings.sheet_gid:
        return settings.sheet_title or "", settings.sheet_gid

    index = SheetIndex(state_dir)
    remembered = index.covering(day)
    if remembered:
        return remembered

    for sheet in candidates(list_sheets(), day):
        try:
            text, _ = gsheets.fetch_sheet_csv(gid=sheet.gid, title=sheet.title)
            if text is None:
                continue
            snapshot = parse_csv(text, sheet.title)
        except SourceFormatChanged as exc:
            log.info("лист %r не похож на расписание групп: %s", sheet.title, exc)
            continue
        except Exception as exc:
            log.warning("лист %r не прочитался: %s", sheet.title, exc)
            continue

        coverage = snapshot.coverage
        if not coverage:
            continue
        index.remember(sheet.title, sheet.gid, *coverage)
        if coverage[0] <= day <= coverage[1]:
            return sheet.title, sheet.gid

    raise LookupError(f"не нашёл лист, покрывающий {day.isoformat()}")
