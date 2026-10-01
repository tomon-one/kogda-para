"""Сбой обновления: с какого момента и почему, что видно наружу, кому тревога.

Часть `Refresher`: поля заводит его __init__. Сбой лежит на диске
(failing.json) — «лежим с четверга» должно пережить перезапуск.
"""

from __future__ import annotations

import datetime as dt
import json
import logging

from ..storage.atomic import write_json
from . import alerts
from .clock import _zone

log = logging.getLogger(__name__)


class Failing:
    """Состояние сбоя захода и тревоги о нём; методы `Refresher`."""

    def _freeze(self) -> None:
        """Заморожено владельцем: прежний снимок, stale, в сеть не ходим."""
        self.checked_at = dt.datetime.now(dt.timezone.utc)
        if self.failing_since is None:
            self.failing_since = self.checked_at
            log.warning("служба заморожена (WHENSCLASS_FREEZE) — в таблицу не хожу")
        self._fetch_only = False
        self.last_error = "заморожено владельцем: отдаётся прежнее расписание"
        self.status = self._visible_status()
        self._save_failing()

    def restore_status(self) -> None:
        """Состояние после перезапуска: какое было — такое и есть."""
        self.status = self._visible_status()

    def _visible_status(self, now: dt.datetime | None = None) -> str:
        if self.store.snapshot is None:
            return "empty"
        if self.failing_since is None:
            return "ok"
        now = now or dt.datetime.now(dt.timezone.utc)
        if self._fetch_only and now - self.failing_since < FETCH_GRACE:
            # Чих Google: владельцу о нём не говорят полчаса, и телефонам
            # незачем полчаса показывать «сбой на нашем сервере».
            return "ok"
        return "stale"

    def _fail(self, message: str, kind: str = "error", public: str | None = None) -> None:
        now = dt.datetime.now(dt.timezone.utc)
        first = self.failing_since is None
        if first:
            self.failing_since = now
            self._alerted = False
            self._fetch_only = True
        self._fetch_only = self._fetch_only and kind == "fetch"
        # Сеть отсчитывается от первого сетевого отказа подряд, а не от начала
        # всего сбоя: иначе один таймаут посреди отказа по формату сразу уйдёт
        # тревогой «таблица не прочиталась» и подменит err.
        self._fetch_since = (self._fetch_since or now) if kind == "fetch" else None
        if kind != "fetch" or self._fetch_only:
            self.last_error = public or message
        self.status = self._visible_status(now)
        self._sheets = None
        log.error("%s (состояние: %s)", message, self.status)

        # Формат и поиск листа сами не чинятся — говорить сразу. Сеть и
        # Google чинятся к следующему заходу: о них — только если сеть лежит
        # дольше получаса, иначе каждый чих Google будит человека дважды.
        if kind != "fetch" or now - self._fetch_since >= FETCH_GRACE:
            sent = alerts.notify(
                kind,
                f"{message}. Состояние: {self.status}, "
                + ("отдаётся прежнее расписание." if self.status == "stale"
                   else "отдавать нечего.")
                + ("" if first else
                   f" Лежим с {self.failing_since.astimezone(_zone()):%d.%m %H:%M}."),
                force=not self._alerted,
            )
            if sent:
                self._alerted = True
        self._save_failing()

    def _recovered(self) -> None:
        """Обновление удалось после сбоя: сказать, что и сколько лежало."""
        since = self.failing_since
        if since is None:
            return
        lying = dt.datetime.now(dt.timezone.utc) - since
        if self._alerted:
            healed = not self._lever_used and since >= self._started
            alerts.notify(
                "recovered",
                ("Само исправилось. " if healed else "")
                + f"Расписание снова обновляется. Лежало {_lying(lying)}, "
                f"с {since.astimezone(_zone()):%d.%m %H:%M}.",
                force=True, good=True,
            )
        self._lever_used = False
        for kind in FAIL_KINDS:
            alerts.forget(kind)
        self.failing_since, self.last_error, self._alerted = None, None, False
        self._fetch_since = None
        self._fetch_only = True
        self._save_failing()

    def _save_failing(self) -> None:
        try:
            if self.failing_since is None:
                self._failing_path.unlink(missing_ok=True)
                return
            write_json(self._failing_path, {
                "since": self.failing_since.isoformat(),
                "error": self.last_error,
                "fetch_only": self._fetch_only,
                "alerted": self._alerted,
                "fetch_since": self._fetch_since.isoformat() if self._fetch_since else None,
            })
        except OSError as exc:
            log.warning("состояние сбоя не записалось: %s", exc)

    def _load_failing(self) -> None:
        # Когда уходила тревога каждого вида, помнит alerts.json (keep_in
        # выше): перезапуск посреди сбоя её не повторит.
        self._fetch_since: dt.datetime | None = None
        try:
            data = json.loads(self._failing_path.read_text("utf-8"))
            self.failing_since = dt.datetime.fromisoformat(data["since"])
            self.last_error = data.get("error")
            self._alerted = bool(data.get("alerted"))
            # Старые файлы без признака — сбой не сетевой: показывать как был.
            self._fetch_only = bool(data.get("fetch_only", False))
            if data.get("fetch_since"):
                self._fetch_since = dt.datetime.fromisoformat(data["fetch_since"])
        except (OSError, ValueError, KeyError, TypeError):
            self.failing_since, self.last_error, self._alerted = None, None, False

    def _mark_running(self) -> None:
        try:
            self._running_path.write_text(str(self._crashes + 1), "utf-8")
        except OSError as exc:
            log.warning("отметка захода не записалась: %s", exc)

    def _clear_running(self) -> None:
        self._crashes = 0
        try:
            self._running_path.unlink(missing_ok=True)
        except OSError as exc:
            log.warning("отметка захода не снялась: %s", exc)


# Сколько сетевой сбой держим за чих: ни тревоги, ни stale наружу.
FETCH_GRACE = dt.timedelta(minutes=30)
# После скольких заходов подряд, умерших посреди разбора, — тревога.
CRASHES_TO_ALERT = 2
# Виды тревог о сбое: все забываются, когда обновление снова удалось.
FAIL_KINDS = ("format", "sheet", "fetch", "error", "closed", "crash")


def _lying(delta: dt.timedelta) -> str:
    """Сколько лежали, по-человечески: короткий сбой — минутами, а не «0.0 ч»."""
    minutes = int(delta.total_seconds() // 60)
    if minutes < 60:
        return f"{minutes} мин"
    return f"{minutes / 60:.1f} ч".replace(".", ",")
