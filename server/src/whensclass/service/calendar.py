"""Календарный график: сессия, каникулы и практика по неделям.

Живёт отдельно от расписания: лист графика не подменяет пары и его сбой не
делает службу stale. Читается раз в сутки — график меняется редко, а лишние
запросы к Google приближают отказ и для расписания. Не прочитался — отдаётся
прежний: пустая неделя честнее выдуманной.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import pathlib
import re
import zoneinfo
from collections.abc import Callable

from ..config import settings
from ..parser import calendar as parser
from ..sources import gsheets, sheet_index
from ..storage.atomic import write_json

log = logging.getLogger(__name__)

# Лист зовётся «Календарный график 2026-2027г.»; год в имени когда-нибудь станет другим.
_IS_CALENDAR = re.compile(r"календарн", re.IGNORECASE)


class CalendarSource:
    def __init__(
        self,
        directory: pathlib.Path,
        groups: Callable[[], dict[str, str]] | None = None,
    ) -> None:
        """[groups] — имя группы в листе → id в расписании, на момент чтения."""
        self.groups = groups or dict
        self.now = lambda: dt.datetime.now(zoneinfo.ZoneInfo(settings.timezone))
        self.path = directory / "calendar.json"
        # {"sheet": ..., "updated": ..., "groups": {id: {дата: состояние}}}
        self.payload: dict | None = None
        # Последняя попытка чтения не удалась: суточный заход повторяют.
        self.failed = False
        try:
            self.payload = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            pass
        except (OSError, ValueError) as exc:
            log.warning("calendar.json не читается (%s) — начну с пустого", exc)

    @property
    def read_at(self) -> str | None:
        return (self.payload or {}).get("updated")

    def states(self, group_id: str) -> dict[str, str] | None:
        """Дни группы с пометкой: дата ISO → «session», «holidays», «practice»."""
        return ((self.payload or {}).get("groups") or {}).get(group_id)

    def refresh(self) -> None:
        # До конца захода `failed` — «не прочитано»: любой возврат ниже, кроме
        # успешного, оставит его True и позовёт повтор.
        self.failed = True
        # Рычаг заморозки: в таблицу не ходит никто, график тоже. Повтор после
        # разморозки прочитает график, не дожидаясь следующего утра.
        if settings.freeze:
            return
        # Google просил ходить реже — график подождёт, отдаётся прежний.
        if gsheets.throttled() and self.payload is not None:
            log.info("график: Google недавно ответил 429 — пропускаю заход")
            return
        try:
            sheets = [
                s for s in sheet_index.list_sheets(max_age=dt.timedelta(hours=6))
                if not s.hidden and s.gid and _IS_CALENDAR.search(s.title)
            ]
        except Exception as exc:  # сбой API: тревогу о нём шлёт list_sheets
            log.warning("график: список листов не получен (%s)", sheet_index.hide_key(exc))
            return
        if not sheets:
            # Лист убрали — прежнее не стираем: он про весь учебный год, и его
            # исчезновение скорее правка книги, чем «графика больше нет».
            log.warning("график: листа в книге нет — отдаётся прежний")
            return
        sheet = sheets[-1]
        try:
            text = gsheets.fetch_sheet_csv(sheet.gid, sheet.title)
            parsed = parser.parse(text, set(self.groups()), self.now().date())
        except Exception as exc:
            log.warning(
                "график: лист %r не прочитан: %s — отдаётся прежний",
                sheet.title, sheet_index.hide_key(exc),
            )
            return
        if not parsed:
            # Пусто бывает от сдвига колонок: прежнее лучше выдуманного «обучения».
            log.warning("график: в листе %r ничего не разобрано — отдаётся прежний", sheet.title)
            return
        ids = self.groups()
        body = {
            "sheet": sheet.title,
            "updated": self.now().astimezone(dt.UTC).isoformat(timespec="seconds"),
            "groups": {
                ids[name]: days
                for name, days in parsed.items()
                if name in ids
            },
        }
        self._put(body)
        self.failed = False

    def refresh_if_failed(self) -> None:
        """Повтор суточного чтения — только когда оно не удалось.

        График меняется редко, а каждая выгрузка листа приближает отказ
        Google и для расписания; поэтому обычный заход один в сутки, а частые
        попытки — лишь пока не прочитано.
        """
        if self.failed and not settings.freeze:
            self.refresh()

    def _put(self, body: dict) -> None:
        old = dict(self.payload or {})
        old.pop("updated", None)
        fresh = dict(body)
        fresh.pop("updated", None)
        if old == fresh:
            # График тот же: `updated` остаётся временем, когда пометки
            # сменились, — и файл не переписывается зря.
            return
        self.payload = body
        try:
            write_json(self.path, body)
        except OSError as exc:
            # В памяти график свежий, отдаётся; после перезапуска — прежний.
            log.error("график не записался на диск: %s", exc)
        log.info("график: %s, групп с пометками %d", body["sheet"], len(body["groups"]))
