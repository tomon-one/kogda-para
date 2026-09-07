"""Сообщения владельцу, когда с расписанием что-то не так.

Служба устроена так, чтобы непонятный лист не подменял собой рабочий: она
удерживает прежний снимок и уходит в состояние stale. Но молчаливая поломка —
плохая поломка: без этого модуля о ней узнаёшь от одногруппников в понедельник.

Работает, только если в окружении заданы WHENSCLASS_TELEGRAM_TOKEN и
WHENSCLASS_TELEGRAM_CHAT. Без них молча ничего не делает — сервис не должен
падать из-за ненастроенных оповещений.
"""

from __future__ import annotations

import logging
import time

import httpx

from ..config import settings

log = logging.getLogger(__name__)

# Чтобы не превратить неудачное обновление раз в 20 минут в поток сообщений.
_QUIET_SECONDS = 6 * 60 * 60
_last_sent: dict[str, float] = {}


def notify(kind: str, text: str, force: bool = False) -> bool:
    """Отправляет сообщение владельцу. True, если получилось.

    `kind` — вид происшествия: одинаковые не повторяются чаще, чем раз в шесть
    часов. Таблица может лежать сутки, и напоминать об этом каждые двадцать
    минут незачем.
    """
    token = settings.telegram_token
    chat = settings.telegram_chat
    if not token or not chat:
        log.debug("оповещения не настроены, пропускаю: %s", text)
        return False

    now = time.monotonic()
    if not force and now - _last_sent.get(kind, 0) < _QUIET_SECONDS:
        return False

    try:
        response = httpx.post(
            f"https://api.telegram.org/bot{token}/sendMessage",
            json={"chat_id": chat, "text": text, "disable_web_page_preview": True},
            timeout=15,
        )
        response.raise_for_status()
    except Exception as exc:
        log.error("не смог отправить оповещение: %s", exc)
        return False

    _last_sent[kind] = now
    return True
