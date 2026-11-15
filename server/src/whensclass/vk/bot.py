"""Бот ВКонтакте: Long Poll в отдельном потоке службы.

Отвечает на сообщения сообщества расписанием, ничего о человеке не храня:
id ВК прочитан, отправлен ответ и тут же забыт — ни на диск, ни в память
между запросами, ни в журнал.

Токен сообщества лежит только в настройках (`settings.vk_token`): он уходит в
параметры запросов VK, поэтому ни один URL и ни одно исключение не попадают в
журнал целиком — только имя метода и код ошибки.
"""

from __future__ import annotations

import contextlib
import datetime as dt
import logging
import threading
import time

import httpx

from ..config import settings
from ..service.clock import _today
from .render import reply_text

log = logging.getLogger(__name__)

# Версия API VK во всех обращениях.
API_VERSION = "5.199"
# Пауза ожидания событий в Long Poll, секунд.
WAIT = 25
# Пауза между обходами после сетевого сбоя: растёт, но не бесконечно.
BACKOFF_START = 5.0
BACKOFF_MAX = 30.0
# «Слишком много запросов» — подождать секунду и повторить один раз.
TOO_MANY_REQUESTS = 6


class VkError(Exception):
    """Ошибка обращения к VK без адреса и токена в тексте.

    `method` — имя метода («groups.getLongPollServer», «longPoll»,
    «messages.send»), `code` — error_code VK (None у сетевых сбоев),
    `message` — краткая причина без токена.
    """

    def __init__(self, method: str, code: int | None, message: str):
        super().__init__(message)
        self.method = method
        self.code = code

    def __str__(self) -> str:
        if self.code is None:
            return f"{self.method}: {self.args[0]}"
        return f"{self.method}: {self.code} {self.args[0]}"


class VkBot:
    """Один Long Poll-обход за другим, пока не поставлен `stop`."""

    def __init__(self, store, stop: threading.Event | None = None):
        self.store = store
        self.stop = stop or threading.Event()
        self._token = settings.vk_token
        self._group_id = settings.vk_group_id
        self._server: str | None = None
        self._key: str | None = None
        self._ts: str | None = None
        self._counter = 0
        self._backoff = BACKOFF_START
        self._client = httpx.Client(
            timeout=settings.http_timeout, headers={"User-Agent": settings.user_agent}
        )
        self._thread = threading.Thread(target=self._run, name="vk-bot", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def shutdown(self) -> None:
        """Ставит `stop`, закрывает клиент и ждёт выхода потока."""
        self.stop.set()
        with contextlib.suppress(Exception):
            self._client.close()
        self._thread.join(timeout=WAIT + 5)

    # --- цикл -----------------------------------------------------------------

    def _run(self) -> None:
        while not self.stop.is_set():
            try:
                self._step()
                self._backoff = BACKOFF_START
            except VkError as exc:
                log.warning("бот ВК: %s", exc)
                self._pause()
            except Exception as exc:  # обход не должен уронить службу
                log.error("бот ВК: обход упал (%s)", type(exc).__name__)
                self._pause()

    def _pause(self) -> None:
        """Ждёт с растущей паузой; `stop` будит сразу."""
        self.stop.wait(self._backoff)
        self._backoff = min(self._backoff * 2, BACKOFF_MAX)

    def _step(self) -> None:
        if self._ts is None:
            self._get_long_poll_server()
        self._long_poll()

    def _get_long_poll_server(self) -> None:
        data = self._call("groups.getLongPollServer", {
            "group_id": self._group_id,
            "v": API_VERSION,
        })
        response = data.get("response") or {}
        self._server = response.get("server")
        self._key = response.get("key")
        self._ts = response.get("ts")
        if self._server is None or self._key is None or self._ts is None:
            raise VkError("groups.getLongPollServer", None, "нет server/key/ts")

    def _long_poll(self) -> None:
        try:
            response = self._client.get(
                self._server,
                params={
                    "act": "a_check",
                    "key": self._key,
                    "ts": self._ts,
                    "wait": WAIT,
                    "mode": 2,
                    "version": 3,
                },
            )
            data = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            # Сеть или не-JSON: текст исключения может держать адрес — не в журнал.
            raise VkError("longPoll", None, type(exc).__name__) from None
        if not isinstance(data, dict):
            raise VkError("longPoll", None, f"не-JSON ответ, статус {response.status_code}")

        failed = data.get("failed")
        if failed == 1:
            self._ts = data.get("ts")
            return
        if failed:
            # 2 — ключ просрочен, 3 — сервер потерян: сервер берём заново.
            self._ts = None
            return
        self._ts = data.get("ts")
        for update in data.get("updates", []):
            self._handle(update)

    # --- сообщения ------------------------------------------------------------

    def _handle(self, update) -> None:
        if not isinstance(update, dict) or update.get("type") != "message_new":
            return
        message = (update.get("object") or {}).get("message")
        if not isinstance(message, dict):
            return
        text = (message.get("text") or "").strip()
        from_id = message.get("from_id") or 0
        if not text or from_id <= 0:
            return

        snapshot = self.store.snapshot
        if snapshot is None:
            self._send(from_id, "Расписание ещё не загружено.")
            return
        generated = self.store.generated or dt.datetime.now(dt.UTC)
        self._send(from_id, reply_text(snapshot, text, generated, _today()))

    def _send(self, peer_id: int, message: str) -> None:
        for attempt in (0, 1):
            try:
                self._call("messages.send", {
                    "peer_id": peer_id,
                    "message": message,
                    "random_id": self._random_id(),
                    "v": API_VERSION,
                })
                return
            except VkError as exc:
                if exc.code == TOO_MANY_REQUESTS and attempt == 0:
                    time.sleep(1)
                    continue
                log.error("бот ВК: %s", exc)
                return

    def _random_id(self) -> int:
        self._counter += 1
        # VK ждёт random_id в пределах 32-битного знакового: больше — отказ.
        return (int(time.time() * 1000) + self._counter) % (2**31)

    # --- HTTP -----------------------------------------------------------------

    def _call(self, method: str, params: dict) -> dict:
        """Обращение к методу VK; ошибка — без адреса и токена.

        Токен добавляется сюда, а не в строку вызова: он не нужен вызывающему
        и не должен мелькнуть в его коде. Тело, а не строка адреса — так токен
        не попадает в адрес запроса.
        """
        params = {**params, "access_token": self._token}
        try:
            response = self._client.post(
                f"https://api.vk.com/method/{method}", data=params
            )
        except httpx.HTTPError as exc:
            raise VkError(method, None, type(exc).__name__) from None
        try:
            data = response.json()
        except ValueError:
            raise VkError(method, None, f"не-JSON, статус {response.status_code}") from None
        if not isinstance(data, dict):
            raise VkError(method, None, f"не-JSON ответ, статус {response.status_code}")
        error = data.get("error")
        if error:
            raise VkError(
                method, error.get("error_code"), str(error.get("error_msg") or "")
            )
        return data
