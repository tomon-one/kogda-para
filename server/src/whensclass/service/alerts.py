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
import json
import logging
import pathlib
import time

import httpx

from ..config import settings

log = logging.getLogger(__name__)

# Чтобы неудачное обновление раз в двадцать минут не превратилось в поток.
_QUIET_SECONDS = 6 * 60 * 60
# Дольше этого окно тишины не помнится (у тревоги каникул — месяц).
_LONGEST_WINDOW = 45 * 24 * 60 * 60
_last_sent: dict[str, float] = {}
# Когда ушла тревога каждого вида — по часам, для файла: окно тишины должно
# пережить перезапуск для всех видов, а не только для тех, что пишет
# failing.json. Тревога о диске и о ключе после перезапуска посреди той же
# беды уходила сразу снова (третий аудит, М35 прогона 1).
_sent_at: dict[str, dt.datetime] = {}
_state_path: pathlib.Path | None = None
# Своё, короткое: notify зовётся из _fail под замком обновления, и общий
# таймаут в минуту держал бы замок ровно тогда, когда всё и так плохо.
_TIMEOUT = 10.0


def notify(
    kind: str,
    text: str,
    force: bool = False,
    good: bool = False,
    quiet: bool = False,
    window: float = _QUIET_SECONDS,
) -> bool:
    """Отправляет сообщение владельцу. True, если получилось.

    `kind` — вид происшествия: одинаковые не повторяются чаще, чем раз в шесть
    часов. Таблица может лежать сутки, и напоминать об этом каждые двадцать
    минут незачем. `good` — не тревога, а «починилось». `quiet` — не беда, а
    сведение (приоритет 2, без звука); `window` — своё окно тишины в секундах.
    """
    if not settings.ntfy_topic:
        log.debug("оповещения не настроены, пропускаю: %s", text)
        return False

    now = time.monotonic()
    last = _last_sent.get(kind)
    # «Ни разу не слали» — это None, а не ноль: monotonic считается от
    # загрузки машины, и с нулём первые шесть часов после перезагрузки
    # сервера любая тревога считалась бы уже отправленной.
    if not force and last is not None and now - last < window:
        return False

    # Публикация JSON-ом в корень, а не POST в /<тема>: так тема не стоит в
    # адресе и не попадёт в текст исключения httpx, а заголовки не надо
    # кодировать ради кириллицы.
    body = {
        "topic": settings.ntfy_topic,
        "title": "Когда пара?",
        "message": text,
        "priority": 2 if quiet else 3 if good else 4,
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
    _sent_at[kind] = dt.datetime.now(dt.timezone.utc)
    _save()
    log.info("отправлено оповещение (%s)", kind)
    return True


def keep_in(path: pathlib.Path) -> None:
    """Где помнить окна тишины между перезапусками; поднимает прежние."""
    global _state_path
    _state_path = path
    _sent_at.clear()
    try:
        data = json.loads(path.read_text("utf-8"))
    except (OSError, ValueError):
        return
    for kind, at in (data if isinstance(data, dict) else {}).items():
        try:
            remember(kind, dt.datetime.fromisoformat(at))
        except (TypeError, ValueError):
            continue


def sent_recently(kind: str) -> bool:
    """Тревога этого вида уходила и окно тишины ещё идёт."""
    last = _last_sent.get(kind)
    return last is not None and time.monotonic() - last < _QUIET_SECONDS


def _save() -> None:
    if _state_path is None:
        return
    data = {kind: _sent_at[kind].isoformat() for kind in _last_sent if kind in _sent_at}
    try:
        tmp = _state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data), "utf-8")
        tmp.replace(_state_path)
    except OSError as exc:
        log.warning("окна тишины не записались: %s", exc)


def remember(kind: str, at: dt.datetime) -> None:
    """Тревога этого вида уже уходила в `at` — окно тишины считать от неё.

    Окно живёт в памяти процесса, а служба перезапускается при каждой
    выкладке: перезапуск посреди сбоя сразу повторял тревогу (второй аудит,
    М23). Время последней тревоги лежит в failing.json и поднимается отсюда.
    """
    ago = (dt.datetime.now(dt.timezone.utc) - at).total_seconds()
    if 0 <= ago < _LONGEST_WINDOW:
        _last_sent[kind] = time.monotonic() - ago
        _sent_at[kind] = at


def forget(kind: str) -> None:
    """Забыть, что об этом уже говорили: беда прошла, следующая — новая."""
    if _last_sent.pop(kind, None) is not None:
        _sent_at.pop(kind, None)
        _save()
