"""Проверяет, не переделал ли колледж таблицу.

Зачем: расписание разбирается по договорённостям, которых никто не обещал —
блоки по четыре колонки, дата в первой строке дня, тип занятия в скобках.
Когда таблицу переделают, сервис перестанет обновляться молча: он честно
удержит прежний снимок, но узнать об этом хочется раньше, чем в понедельник
утром у всей группы.

Скрипт тянет живой лист и проверяет только структуру, не содержимое. Ставится
в cron раз в сутки; ненулевой код возврата означает «сходи посмотри».

    python tools/check_source.py
    python tools/check_source.py --quiet   # печатать только при беде
"""

from __future__ import annotations

import argparse
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server" / "src"))

from whensclass.domain.models import SourceFormatChanged  # noqa: E402
from whensclass.service import alerts  # noqa: E402
from whensclass.parser.csv_schedule import parse_csv  # noqa: E402
from whensclass.service.refresher import _today  # noqa: E402
from whensclass.sources import gsheets, sheet_index  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--quiet", action="store_true", help="молчать, пока всё хорошо")
    ap.add_argument("--state-dir", default=str(ROOT / "server" / "var"))
    args = ap.parse_args()

    # По поясу колледжа, не машины: в 04:30 по Новосибирску UTC ещё вчера.
    today = _today()
    state = pathlib.Path(args.state_dir)

    try:
        title, gid = sheet_index.resolve_for(today, state)
    except LookupError as exc:
        print(f"БЕДА: не нашёл лист на {today}: {exc}")
        return 2

    try:
        text, _ = gsheets.fetch_sheet_csv(gid=gid, title=title or None)
        snapshot = parse_csv(text or "", title or f"gid {gid}")
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

    if coverage and not (coverage[0] <= today <= coverage[1]):
        print(f"ВНИМАНИЕ: сегодняшний день {today} вне листа {coverage[0]}—{coverage[1]}.")
        print("Похоже, пора появиться новому листу, а его ещё нет.")
        alerts.notify(
            "canary-coverage",
            f"Когда пара?: лист покрывает {coverage[0]}—{coverage[1]}, "
            f"а сегодня уже {today}. Похоже, новый лист ещё не опубликовали.",
        )
        return 1

    if not args.quiet:
        print("структура на месте")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
