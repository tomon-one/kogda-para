"""Проверяет, не переделал ли колледж таблицу.

Зачем: расписание разбирается по договорённостям, которых никто не обещал —
блоки по четыре колонки, дата в первой строке дня, тип занятия в скобках.
Когда таблицу переделают, сервис перестанет обновляться молча: он честно
удержит прежний снимок, но узнать об этом хочется раньше, чем в понедельник
утром у всей группы.

Скрипт тянет живой лист и разбирает его тем же путём, что служба: пороги —
из того же env (`WHENSCLASS_MIN_*`, горизонт дат), сдвиг — против снимка
службы. Запускается юнитом whensclass-canary.timer раз в сутки, в 04:30.

Коды возврата:
    0 — всё на месте, или каникулы (лист кончился больше недели назад: одна
        тихая тревога и молчание до нового листа);
    1 — учебный день вне листа: похоже, новый лист ещё не выложен;
    2 — беда: лист не найден, не прочитался или не разобрался — причина в
        журнале строкой «БЕДА: …».

    python tools/check_source.py
    python tools/check_source.py --quiet   # печатать только при беде
"""

from __future__ import annotations

import argparse
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server" / "src"))

import datetime as dt  # noqa: E402

from whensclass.config import settings  # noqa: E402
from whensclass.domain.models import SourceFormatChanged  # noqa: E402
from whensclass.parser.csv_schedule import _check_shift, parse_export, shift_seed  # noqa: E402
from whensclass.service import alerts  # noqa: E402
from whensclass.service.refresher import _limits, _today  # noqa: E402
from whensclass.sources import gsheets, sheet_index  # noqa: E402
from whensclass.storage.snapshot_store import SnapshotStore  # noqa: E402

# Сертификат, который отдаёт nginx: продлевается сам, но перезагрузку nginx
# после продления делает хук, который ни разу не срабатывал и зависит от
# `nginx -t` по чужим сайтам общей машины. За
# две недели до конца срока — тревога.
CERT_WARN_DAYS = 14

# Лист кончился больше недели назад, а нового нет — это каникулы, а не беда:
# раньше канарейка всё лето каждое утро в 04:30 слала тревогу со звуком.
HOLIDAY_DAYS = 7
HOLIDAY_WINDOW = 30 * 24 * 60 * 60


def cert_days_left(host: str) -> int:
    """Сколько дней осталось сертификату, который отдаёт `host` на 443."""
    import socket
    import ssl

    context = ssl.create_default_context()
    with socket.create_connection((host, 443), timeout=15) as raw:
        with context.wrap_socket(raw, server_hostname=host) as tls:
            not_after = tls.getpeercert()["notAfter"]
    expires = dt.datetime.fromtimestamp(ssl.cert_time_to_seconds(not_after), dt.timezone.utc)
    return (expires - dt.datetime.now(dt.timezone.utc)).days


def check_cert(quiet: bool) -> None:
    host = settings.domain
    try:
        left = cert_days_left(host)
    except Exception as exc:
        print(f"сертификат {host} не проверился: {type(exc).__name__}: {exc}")
        return
    if not quiet:
        print(f"сертификат {host}: осталось {left} дн.")
    if left < CERT_WARN_DAYS:
        print(f"БЕДА: сертификат {host} кончается через {left} дн.")
        alerts.notify(
            "canary-cert",
            f"Когда пара?: сертификат {host} кончается через {left} дн. — продление "
            "не дошло до nginx? docs/deploy.md, «Сертификат».",
        )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--quiet", action="store_true", help="молчать, пока всё хорошо")
    ap.add_argument("--state-dir", default=str(ROOT / "server" / "var"))
    args = ap.parse_args()

    # По поясу колледжа, не машины: в 04:30 по Новосибирску UTC ещё вчера.
    today = _today()
    state = pathlib.Path(args.state_dir)
    alerts.keep_in(state / "canary-alerts.json")
    check_cert(args.quiet)

    try:
        # Глубоко: память службы «листа нет» канарейке не указ — раз в сутки
        # можно и сходить посмотреть.
        title, gid = sheet_index.resolve_for(today, state, deep=True)
    except LookupError as exc:
        print(f"БЕДА: не нашёл лист на {today}: {exc}")
        return 2

    try:
        text = gsheets.fetch_sheet_csv(gid=gid, title=title or None)
        # Тем же путём и с теми же порогами, что служба: иначе рычаг «опустить
        # пороги на день» делал канарейку ежедневной ложной тревогой.
        snapshot = parse_export(
            text, title or f"gid {gid}", gid, limits=_limits(), around=today
        )
        store = SnapshotStore(state)
        previous = store.snapshot if store.load() else None
        _check_shift(snapshot, seed=shift_seed(previous, snapshot), previous=previous)
    except SourceFormatChanged as exc:
        print(f"БЕДА: формат таблицы изменился — {exc}")
        print("Сверьтесь с docs/source-format.md: там записано, как было.")
        alerts.notify("canary-format", f"Когда пара?: формат таблицы изменился — {exc}")
        return 2
    except Exception as exc:
        print(f"БЕДА: лист {title!r} не прочитался: {exc}")
        alerts.notify("canary-fetch", f"Когда пара?: лист {title!r} не прочитался — {exc}")
        return 2

    coverage = snapshot.coverage
    if not args.quiet:
        print(f"лист {snapshot.sheet_title!r}")
        print(f"групп {len(snapshot.groups)}, дней {len(snapshot.dates)}, "
              f"пар {snapshot.total_lessons()}")
        print(f"покрытие {coverage[0]} — {coverage[1]}" if coverage else "покрытие пустое")

    if coverage and today > coverage[1] + dt.timedelta(days=HOLIDAY_DAYS):
        print(f"каникулы? лист кончился {coverage[1]}, нового нет")
        alerts.notify(
            "canary-holiday",
            f"Когда пара?: лист кончился {coverage[1]}, а нового нет уже "
            f"{(today - coverage[1]).days} дней — похоже, каникулы. Дальше молчу, пока "
            "не появится новый лист.",
            quiet=True, window=HOLIDAY_WINDOW,
        )
        return 0
    if coverage and not (coverage[0] <= today <= coverage[1]) and today.weekday() != 6:
        print(f"ВНИМАНИЕ: сегодняшний день {today} вне листа {coverage[0]}—{coverage[1]}.")
        print("Похоже, пора появиться новому листу, а его ещё нет.")
        alerts.notify(
            "canary-coverage",
            f"Когда пара?: лист покрывает {coverage[0]}—{coverage[1]}, "
            f"а сегодня уже {today}. Похоже, новый лист ещё не опубликовали.",
        )
        return 1
    # Лист снова покрывает сегодня: следующие каникулы — новая тревога.
    alerts.forget("canary-holiday")

    if not args.quiet:
        print("структура на месте")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
