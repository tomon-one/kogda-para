"""Тихие часы: ночью изменения не шлются, а копятся до утра.

Часть `Push`: `_held`, `_held_path` и `_lock` заводит его __init__.
"""

from __future__ import annotations

import datetime as dt
import json
import logging

from ..storage.atomic import write_json
from . import changes
from .delivery import Job
from .messages import changes_message, changes_ttl

log = logging.getLogger(__name__)


def quiet_hours(now: dt.datetime) -> bool:
    """С 22:00 до 7:00 по времени колледжа об изменениях не сообщаем — копим и
    шлём утром одним (quietHours в Refresh.kt приложения)."""
    return now.hour >= 22 or now.hour < 7


def _by_day(lines: list[list[str]]) -> list[list[str]]:
    """Строки ночи — по дням, как днём у change_lines: получатель оставляет
    первые MAX_LINES, и сегодняшнее должно быть в начале. Внутри дня — порядок
    прихода; лишнее уходит с дальних дней, а в дне — старое: «вернули» после
    «отменили» важнее самой отмены."""
    order = sorted(range(len(lines)), key=lambda i: (lines[i][0], -i))[:changes.MAX_LINES]
    return [lines[i] for i in sorted(order, key=lambda i: (lines[i][0], i))]


class Quiet:
    """Придержанные на ночь изменения; методы `Push`."""

    def _load_held(self) -> dict[tuple[str, str], list[list[str]]]:
        try:
            data = json.loads(self._held_path.read_text("utf-8"))
            return {(str(k), str(i)): [[str(d), str(t)] for d, t in lines] for k, i, lines in data}
        except (OSError, ValueError, TypeError):
            return {}

    def _save_held(self) -> None:
        try:
            write_json(self._held_path, [[k, i, lines] for (k, i), lines in sorted(self._held.items())])
        except OSError as exc:
            log.warning("придержанные изменения не записались: %s", exc)

    def _hold(self, lines_for: dict[tuple[str, str], list[list[str]]]) -> None:
        """Ночь: строки — к придержанным. Повтор встаёт на последнее место, как в
        приложении (mergeChanges)."""
        with self._lock:
            for key, lines in lines_for.items():
                fresh = [list(x) for x in lines]
                kept = [x for x in self._held.get(key, []) if x not in fresh]
                self._held[key] = kept + fresh
            self._save_held()
        log.info("тихие часы: изменения придержаны до утра у %d субъектов", len(lines_for))

    def _release(self, today: dt.date) -> None:
        """Утро: придержанное — одним уведомлением, без прошедших дней."""
        with self._lock:
            held, self._held = self._held, {}
            self._save_held()
        jobs = []
        for sub in self._snapshot_subs():
            if not sub.get("changes") or sub.get("gone"):
                continue
            lines = _by_day([x for x in held.get((sub["kind"], sub["id"]), []) if x[0] >= today.isoformat()])
            if lines:
                jobs.append(Job(sub, changes_message(sub, lines), changes_ttl(lines)))
        if jobs:
            log.info("тихие часы кончились: %d уведомлений об изменениях", len(jobs))
            self._dispatch(jobs)
