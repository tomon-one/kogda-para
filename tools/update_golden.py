"""Пересобирает golden-файлы тестов.

Зачем: golden фиксирует контракт JSON, который читает приложение на телефонах.
Обновлять его молча нельзя — только этой командой и с осмотром diff.

    python tools/update_golden.py
"""

from __future__ import annotations

import datetime as dt
import gzip
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server" / "src"))

from whensclass.api.payloads import schedule_payload  # noqa: E402
from whensclass.parser.csv_schedule import FIXTURE  # noqa: E402
from whensclass.parser.export import parse_csv  # noqa: E402

TESTS = ROOT / "server" / "tests"
GENERATED = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.timezone.utc)


def main() -> None:
    text = gzip.decompress(
        (TESTS / "fixtures" / "2026-09-02.csv.gz").read_bytes()
    ).decode("utf-8")
    snapshot = parse_csv(text, "расписание групп 01.-05.09", FIXTURE)
    group = next(g for g in snapshot.groups if g.name == "ИСП-924/2")
    body = schedule_payload(snapshot, group.id, dt.date(2026, 9, 7), 3, GENERATED)

    out = TESTS / "golden" / "isp-924-2.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(body, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(f"обновлён {out}")


if __name__ == "__main__":
    main()
