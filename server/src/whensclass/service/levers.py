"""Рычаги владельца: accept-next и college-editing.

Часть `Refresher`: пути и флаги заводит его __init__. Рычаг — файл в каталоге
состояния, его ставит владелец командой на сервере.
"""

from __future__ import annotations

import datetime as dt
import logging

log = logging.getLogger(__name__)

ACCEPT_NEXT_TTL = dt.timedelta(hours=2)
EDITING_TTL = dt.timedelta(hours=12)


class Levers:
    """Рычаги владельца; методы `Refresher`."""

    def _accept_next(self) -> bool:
        """Поставлен ли рычаг accept-next. Старше ACCEPT_NEXT_TTL — стирается:
        забытый файл пропустил бы без сверки настоящий сдвиг через неделю."""
        try:
            age = dt.datetime.now().timestamp() - self._accept_path.stat().st_mtime
        except OSError:
            return False
        if age > ACCEPT_NEXT_TTL.total_seconds():
            log.warning("рычаг accept-next старше %s — не беру и стираю", ACCEPT_NEXT_TTL)
            self._accept_path.unlink(missing_ok=True)
            return False
        log.warning("рычаг accept-next: лист сверяется без прежнего снимка")
        return True

    def editing(self) -> bool:
        """Поставлен ли рычаг college-editing. Старше EDITING_TTL — стирается:
        забытый файл прятал бы настоящий сбой."""
        try:
            age = dt.datetime.now().timestamp() - self._editing_path.stat().st_mtime
        except OSError:
            return False
        if age > EDITING_TTL.total_seconds():
            log.warning("рычаг college-editing старше %s — стираю", EDITING_TTL)
            self._editing_path.unlink(missing_ok=True)
            return False
        return True

    def _accepted(self) -> None:
        """Лист принят — разовый рычаг снят."""
        if self._accepting:
            self._accepting = False
            self._lever_used = True
            try:
                self._accept_path.unlink(missing_ok=True)
            except OSError as exc:
                log.warning("рычаг accept-next не стёрся: %s", exc)
            log.warning("лист принят без сверки с прежним снимком (accept-next), рычаг снят")
