"""Сообщения владельцу, когда с расписанием что-то не так.

Служба устроена так, чтобы непонятный лист не подменял собой рабочий: она
удерживает прежний снимок и уходит в состояние stale. Но молчаливая поломка —
плохая поломка: 11–13 сентября 2026 служба два дня писала ERROR в журнал
каждые двадцать минут, и узнали об этом по экрану телефона на третий день.

Шлём в ntfy.sh: это HTTPS-запрос, такой же, как к таблице Google, — юнит
режет файловую систему и setuid, а не сеть. Почта отсюда не уходила
(ProtectSystem=strict прячет очередь /var/spool), а Telegram с этого сервера
не отвечает вовсе (проверено 14 сентября 2026). Тема ntfy — длинная случайная
строка, она же и пароль: знает её только /etc/whensclass/env и телефон.

Без темы модуль молчит, как раньше без адреса почты.
"""

from __future__ import annotations

import datetime as dt
import logging
import time

import httpx

from ..config import settings

log = logging.getLogger(__name__)

# Чтобы неудачное обновление раз в двадцать минут не превратилось в поток.
_QUIET_SECONDS = 6 * 60 * 60
_last_sent: dict[str, float] = {}
# Своё, короткое: notify зовётся из _fail под замком обновления, и общий
# таймаут в минуту держал бы замок ровно тогда, когда всё и так плохо.
_TIMEOUT = 10.0


def configured() -> bool:
    return bool(settings.ntfy_topic)


def notify(kind: str, text: str, force: bool = False, good: bool = False) -> bool:
    """Отправляет сообщение владельцу. True, если получилось.

    `kind` — вид происшествия: одинаковые не повторяются чаще, чем раз в шесть
    часов. Таблица может лежать сутки, и напоминать об этом каждые двадцать
    минут незачем. `good` — не тревога, а «починилось».
    """
    if not settings.ntfy_topic:
        log.debug("оповещения не настроены, пропускаю: %s", text)
        return False

    now = time.monotonic()
    last = _last_sent.get(kind)
    # «Ни разу не слали» — это None, а не ноль: monotonic считается от
    # загрузки машины, и с нулём первые шесть часов после перезагрузки
    # сервера любая тревога считалась бы уже отправленной.
    if not force and last is not None and now - last < _QUIET_SECONDS:
        return False

    # Публикация JSON-ом в корень, а не POST в /<тема>: так тема не стоит в
    # адресе и не попадёт в текст исключения httpx, а заголовки не надо
    # кодировать ради кириллицы.
    body = {
        "topic": settings.ntfy_topic,
        "title": "Когда пара?",
        "message": text,
        "priority": 3 if good else 4,
        "tags": ["white_check_mark"] if good else ["rotating_light"],
    }
    try:
        with httpx.Client(timeout=_TIMEOUT, headers={"User-Agent": settings.user_agent}) as client:
            response = client.post(settings.ntfy_url, json=body)
        response.raise_for_status()
    except httpx.HTTPStatusError as exc:
        log.error("оповещение не ушло (%s): ответ %s", kind, exc.response.status_code)
        return False
    except Exception as exc:
        # Тип, а не текст: в тексте httpx приводит адрес запроса.
        log.error("оповещение не ушло (%s): %s", kind, type(exc).__name__)
        return False

    _last_sent[kind] = now
    log.info("отправлено оповещение (%s)", kind)
    return True


def remember(kind: str, at: dt.datetime) -> None:
    """Тревога этого вида уже уходила в `at` — окно тишины считать от неё.

    Окно живёт в памяти процесса, а служба перезапускается при каждой
    выкладке: перезапуск посреди сбоя сразу повторял тревогу (второй аудит,
    М23). Время последней тревоги лежит в failing.json и поднимается отсюда.
    """
    ago = (dt.datetime.now(dt.timezone.utc) - at).total_seconds()
    if 0 <= ago < _QUIET_SECONDS:
        _last_sent[kind] = time.monotonic() - ago


def forget(kind: str) -> None:
    """Забыть, что об этом уже говорили: беда прошла, следующая — новая."""
    _last_sent.pop(kind, None)
