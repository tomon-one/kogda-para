"""Лист пересдач: найти в книге, прочитать, держать последний удачный.

Живёт отдельно от расписания групп: его сбой не должен ни задерживать
расписание, ни делать службу stale. Лист пропал из книги — пересдач нет,
пустой список; лист не прочитался — отдаётся прежний.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import pathlib
import re
from dataclasses import asdict

from ..parser.retakes import parse_retakes
from ..sources import gsheets, sheet_index
from . import alerts

log = logging.getLogger(__name__)

_IS_RETAKES = re.compile(r"пересдач", re.IGNORECASE)


class RetakesSource:
    def __init__(self, directory: pathlib.Path):
        self.path = directory / "retakes.json"
        # Тело ответа /v1/retakes; None — ещё ни разу не читали.
        self.payload: dict | None = None
        try:
            self.payload = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            pass
        except (OSError, ValueError) as exc:
            log.warning("retakes.json не читается (%s) — начну с пустого", exc)

    def refresh(self) -> None:
        try:
            sheets = [s for s in sheet_index.list_sheets() if not s.hidden and s.gid and _IS_RETAKES.search(s.title)]
        except Exception as exc:  # сбой API: тревогу о нём шлёт list_sheets
            log.warning("пересдачи: список листов не получен (%s)", sheet_index.hide_key(exc))
            return
        if not sheets:
            self._put({"title": None, "sheet": None, "rows": [], "redirects": []})
            return
        # Листов несколько — берём последний в книге: новые колледж добавляет в конец.
        sheet = sheets[-1]
        try:
            parsed = parse_retakes(gsheets.fetch_sheet_csv(sheet.gid, sheet.title))
        except Exception as exc:
            log.warning("пересдачи: лист %r не прочитан: %s", sheet.title, sheet_index.hide_key(exc))
            alerts.notify(
                "retakes",
                f"Лист пересдач «{sheet.title}» не прочитан: {sheet_index.hide_key(exc)}. "
                "Отдаётся прежний список.",
                window=24 * 60 * 60,
            )
            return
        alerts.forget("retakes")
        self._put({
            "title": parsed.title,
            "sheet": sheet.title,
            "rows": [asdict(r) for r in parsed.rows],
            "redirects": [asdict(r) for r in parsed.redirects],
        })

    def _put(self, body: dict) -> None:
        old = dict(self.payload or {})
        old.pop("updated", None)
        if old == body:
            return
        body = {**body, "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")}
        self.payload = body
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        tmp.replace(self.path)
        log.info("пересдачи: %s, строк %d", body["sheet"] or "листа нет", len(body["rows"]))
