"""Что служба говорит владельцу после принятого листа и подписчикам об изменениях.

Часть `Refresher`: `on_update` и `_dropped` заводит его __init__.
"""

from __future__ import annotations

import datetime as dt
import logging

from ..config import settings
from . import alerts
from .bells import own_bells
from .gates import groups_emptied

log = logging.getLogger(__name__)

# Как часто напоминать о тех же непрочитанных днях; о новых — сразу.
UNREAD_REMIND_SECONDS = 24 * 60 * 60


class Telling:
    """Сообщения владельцу и подписчикам; методы `Refresher`."""

    def _tell_unread(self, old, snapshot, today: dt.date) -> None:
        """Непрочитанные дни — владельцу сразу, как появились новые; прочитались
        все — что снова всё. Прошедшие не в счёт: их колледж уже не перепишет."""
        quiet = settings.quiet_groups
        was = {(g, d) for g, days in (old.unread if old else {}).items() for d in days
               if d >= today and g not in quiet}
        now = {(g, d) for g, days in snapshot.unread.items() for d in days
               if d >= today and g not in quiet}
        if now:
            silent = tuple(f"{g.name} " for g in snapshot.groups if g.id in quiet)
            whys = [why for why in snapshot.unread_why if not why.startswith(silent)]
            alerts.notify(
                "unread",
                "Лист принят, но не прочитаны дни: " + "; ".join(whys[:3])
                + ". Там отдаётся прежнее, а где его нет — день с пометкой «не прочитан»; "
                "старые версии приложения видят сбой. Присмотреться (руководство по "
                "серверу, «Когда что-то не так»).",
                force=bool(now - was),
                # Те же дни остаются непрочитанными, пока колледж не поправит
                # лист, — сутки, а не шесть часов.
                window=UNREAD_REMIND_SECONDS,
            )
        elif was:
            alerts.forget("unread")
            alerts.notify("unread-ok", "Непрочитанных дней на сегодня и вперёд больше нет.", force=True, good=True)

    def _tell_emptied(self, old, snapshot, today) -> None:
        """Группа потеряла все будущие пары — владельцу, сразу как появилась новая."""
        emptied = groups_emptied(old, snapshot, today, dropped=bool(self._dropped))
        if not emptied:
            return
        names = ", ".join(f"{name} с {first:%d.%m} ({n} дн.)" for name, first, n in emptied[:3])
        more = f" и ещё {len(emptied) - 3}" if len(emptied) > 3 else ""
        alerts.notify(
            "emptied",
            f"Лист принят, но у групп пропали все пары на будущее: {names}{more}. Колонку "
            "стёрли или группу убрали? Пока это так, у них пустые дни без пометки. "
            "Присмотреться (руководство по серверу, «Когда что-то не так»).",
            force=True, retry=True,
        )

    def _tell_bells(self, old, snapshot) -> None:
        """День со своими звонками — владельцу, когда такой появился или изменился."""
        def own(snap) -> dict:
            return {
                day: grid for day in (snap.bells if snap else {})
                if (grid := own_bells(snap, day))
            }

        was, now = own(old), own(snapshot)
        fresh = sorted(day for day, grid in now.items() if was.get(day) != grid)
        if fresh:
            day = fresh[0]
            spans = ", ".join(f"{n} — {a}–{b}" for n, (a, b) in sorted(now[day].items()))
            more = f" и ещё {len(fresh) - 1} дн." if len(fresh) > 1 else ""
            alerts.notify(
                "bells",
                f"В листе у {day:%d.%m} свои звонки: {spans}{more}. Отдаются у этого дня "
                "вместо обычных.",
                force=True, quiet=True, retry=True,
            )

    def _announce(self, before, snapshot, teachers, today: dt.date) -> None:
        """Сказать подписчикам об изменениях; их поломка — не поломка обновления."""
        if self.on_update is None:
            return
        try:
            self.on_update(before[0], before[1], snapshot, teachers, today)
        except Exception:
            log.exception("уведомления об изменениях не посчитались")
