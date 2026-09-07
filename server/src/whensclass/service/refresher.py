"""Обновление расписания из таблицы.

Главное правило: неудачное обновление не должно делать пользователей слепыми.
Если таблица не читается или перестала соответствовать формату, прежний снимок
остаётся в силе, а сервис уходит в состояние stale — показать вчерашнее лучше,
чем показать пустоту.
"""

from __future__ import annotations

import datetime as dt
import logging
import pathlib

from ..config import settings
from ..domain.models import SourceFormatChanged
from ..parser.csv_schedule import parse_csv
from ..sources import gsheets, sheet_index
from ..storage.snapshot_store import SnapshotStore

log = logging.getLogger(__name__)


class Refresher:
    def __init__(self, store: SnapshotStore, state_dir: pathlib.Path):
        self.store = store
        self.state_dir = state_dir
        self.status = "empty"          # empty | ok | stale
        self.checked_at: dt.datetime | None = None
        self.last_error: str | None = None
        self._source_etag: str | None = None
        self._sheet: tuple[str, str | None] | None = None

    def refresh(self, today: dt.date | None = None, force: bool = False) -> bool:
        """Перечитывает таблицу. True, если снимок обновился."""
        today = today or dt.date.today()
        self.checked_at = dt.datetime.now(dt.timezone.utc)
        try:
            if self._sheet is None or force:
                self._sheet = sheet_index.resolve_for(today, self.state_dir)
            title, gid = self._sheet

            text, etag = gsheets.fetch_sheet_csv(
                gid=gid, title=title or None, etag=None if force else self._source_etag
            )
            if text is None:
                # Google ответил 304: таблица не менялась.
                self.status = "ok"
                return False

            snapshot = parse_csv(text, title or f"gid {gid}")
        except SourceFormatChanged as exc:
            # Самый опасный случай: таблицу переделали. Держим прежнее.
            self._fail(f"формат таблицы изменился: {exc}")
            return False
        except LookupError as exc:
            self._fail(f"не нашёл лист на {today}: {exc}")
            return False
        except Exception as exc:
            self._fail(f"таблица не прочиталась: {exc}")
            return False

        coverage = snapshot.coverage
        if coverage and not (coverage[0] <= today <= coverage[1]):
            # Лист устарел — на следующем заходе поищем новый.
            log.info("лист %r больше не покрывает %s", snapshot.sheet_title, today)
            self._sheet = None

        self._source_etag = etag
        self.store.put(snapshot, dt.datetime.now(dt.timezone.utc))
        self.status = "ok"
        self.last_error = None
        log.info(
            "снимок обновлён: лист %r, %d групп, %d пар",
            snapshot.sheet_title, len(snapshot.groups), snapshot.total_lessons(),
        )
        return True

    def _fail(self, message: str) -> None:
        self.last_error = message
        self.status = "stale" if self.store.snapshot else "empty"
        self._sheet = None
        log.error("%s (состояние: %s)", message, self.status)


def state_dir() -> pathlib.Path:
    return pathlib.Path(settings.state_dir).resolve()
