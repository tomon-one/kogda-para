"""Сообщения владельцу, когда с расписанием что-то не так.

Служба устроена так, чтобы непонятный лист не подменял собой рабочий: она
удерживает прежний снимок и уходит в состояние stale. Но молчаливая поломка —
плохая поломка: без этого модуля о ней узнаёшь от одногруппников в понедельник.

Пишем письмом через локальный почтовый сервер: он на этой машине уже есть и
работает, а до внешних служб оповещения отсюда не достучаться.

Адрес задаётся переменной WHENSCLASS_ALERT_EMAIL. Сейчас он намеренно не
задан — владелец следит за расписанием сам, — и модуль молчит. Код оставлен
на случай, если следить надоест: тогда достаточно вписать адрес в
/etc/whensclass/env и перезапустить службу.
"""

from __future__ import annotations

import logging
import subprocess
import time
from email.message import EmailMessage

from ..config import settings

log = logging.getLogger(__name__)

# Чтобы неудачное обновление раз в двадцать минут не превратилось в поток писем.
_QUIET_SECONDS = 6 * 60 * 60
_last_sent: dict[str, float] = {}


def notify(kind: str, text: str, force: bool = False) -> bool:
    """Отправляет письмо владельцу. True, если получилось.

    `kind` — вид происшествия: одинаковые не повторяются чаще, чем раз в шесть
    часов. Таблица может лежать сутки, и напоминать об этом каждые двадцать
    минут незачем.
    """
    address = settings.alert_email
    if not address:
        log.debug("оповещения не настроены, пропускаю: %s", text)
        return False

    now = time.monotonic()
    if not force and now - _last_sent.get(kind, 0) < _QUIET_SECONDS:
        return False

    message = EmailMessage()
    message["To"] = address
    message["From"] = settings.alert_from
    message["Subject"] = "Когда пара?: расписание не обновляется"
    message.set_content(text)

    try:
        subprocess.run(
            ["/usr/sbin/sendmail", "-t", "-oi"],
            input=message.as_bytes(),
            check=True,
            timeout=30,
        )
    except Exception as exc:
        log.error("не смог отправить оповещение на %s: %s", address, exc)
        return False

    _last_sent[kind] = now
    log.info("отправлено оповещение (%s) на %s", kind, address)
    return True
