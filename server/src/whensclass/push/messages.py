"""Сообщения уведомлений сайта: заголовок, текст, куда ведёт нажатие, срок жизни.

Формат — декларативный, Apple: айфон покажет уведомление и без сервис-воркера,
а Chrome и Firefox получают тот же JSON в сервис-воркер.
"""

from __future__ import annotations

import datetime as dt
import json
import zoneinfo

from ..config import settings


# Изменения живут у службы рассылки до конца последнего дня в сводке, но не
# меньше минуты (`changes_ttl`). Было — 12 часов при любом дне: «на завтра»,
# отправленное вечером, истекало ночью, до пары, а «на сегодня» приходило
# назавтра.
CHANGES_TTL_MIN = 60
# Сообщение целиком — до 4 КБ зашифрованным (Apple, RFC 8291: 3993 байта
# открытого текста); с запасом.
MAX_PAYLOAD = 3000
MAX_LINE = 300


def changes_ttl(lines: list[list[str]], now: dt.datetime | None = None) -> int:
    """Сколько сводке жить у службы рассылки: до конца последнего её дня по
    времени колледжа. Сводка «на сегодня» не придёт назавтра, «на завтра» —
    не истечёт ночью."""
    zone = zoneinfo.ZoneInfo(settings.timezone)
    now = now or dt.datetime.now(zone)
    last = max(dt.date.fromisoformat(day) for day, _ in lines)
    end = dt.datetime.combine(last + dt.timedelta(days=1), dt.time(), tzinfo=zone)
    return max(CHANGES_TTL_MIN, int((end - now).total_seconds()))


def message(sub: dict, kind: str, title: str, body: str, data: dict | None = None) -> dict:
    """Сообщение в декларативном формате Apple (iOS 18.4+):
    сервис-воркер показывает его сам, а не проснулся или упал — айфон покажет
    это же и не отзовёт подписку за «невидимое» уведомление. Chrome и Firefox
    получают тот же JSON в сервис-воркер как обычные данные.

    tag на айфоне уведомления не заменяет (WebKit 258922) — склеивает строки и
    закрывает прежнее сервис-воркер.
    """
    return {
        "web_push": 8030,
        "notification": {
            "title": title,
            "body": body,
            "navigate": site_url(sub),
            "tag": kind,
            "data": {"t": kind, **(data or {})},
        },
        "mutable": True,
    }


def changes_message(sub: dict, lines: list[list[str]]) -> dict:
    """Изменения: строки — текстом, их дни — рядом, чтобы сервис-воркер склеил
    с висящим. Не влезает в MAX_PAYLOAD — прочь завтрашний хвост, а не
    сегодняшнее: строки идут по дням."""
    kept = [[day, text[:MAX_LINE]] for day, text in lines]
    while True:
        # who — чьё расписание: сервис-воркер склеивает с висящим только
        # строки того же.
        out = message(sub, "changes", "Расписание изменилось", "\n".join(t for _, t in kept),
                      {"days": [d for d, _ in kept], "who": f"{sub['kind']}:{sub['id']}"})
        if len(kept) == 1 or len(json.dumps(out, ensure_ascii=False).encode("utf-8")) <= MAX_PAYLOAD:
            return out
        kept = kept[:-1]


def gone_message(sub: dict) -> dict:
    """Группы или преподавателя больше нет в таблице — как в приложении."""
    if sub.get("kind") == "teacher":
        title, what = "Вас больше нет в таблице", "себя"
    else:
        title, what = "Группы больше нет в таблице", "группу"
    return message(sub, "gone", title,
                   f"Откройте сайт и выберите {what} заново: до этого уведомлений не будет.")


def site_url(sub: dict) -> str:
    return f"https://{settings.domain}/" + ("tested/" if sub.get("site") == "tested" else "")


def welcome_text(sub: dict) -> str:
    what = []
    if sub["changes"]:
        what.append("отмены и замены на сегодня и завтра")
    if sub["remind"]:
        what.append("напоминания о паре")
    return "Сюда будут приходить " + " и ".join(what) + "."
