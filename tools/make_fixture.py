"""Урезает лист до нескольких групп, сохраняя структуру.

Зачем: полный лист — 820 КБ, класть такое в git на каждый формат таблицы
накладно. Скрипт оставляет служебные колонки (дата и номер пары) и блоки
выбранных групп, поэтому фикстура весит десятки килобайт, но содержит те же
особенности: повторный заголовок, отмены, вебинары, составные колонки.

    python tools/make_fixture.py --src %TEMP%/sheet.csv \
        --out server/tests/fixtures/2026-09-02.csv.gz
"""

from __future__ import annotations

import argparse
import csv
import gzip
import io
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "server" / "src"))

from whensclass.parser.groups import build_column_map  # noqa: E402

# Группы, ради которых фикстура и существует: своя, составная колонка,
# и те, где встречаются отмена и вебинар (подбираются автоматически).
ALWAYS = ("ИСП-924/2", "ДП-923", "БП-1126")
KEEP_COLUMNS = (0, 1)
BLOCK = 4


def pick_columns(rows: list[list[str]]) -> list[int]:
    groups = build_column_map(rows)
    by_name = {g.name: g for g in groups}

    wanted: list[int] = []
    for name in ALWAYS:
        g = by_name.get(name)
        if g is None:
            raise SystemExit(f"группа {name!r} в листе не найдена")
        if g.column not in wanted:
            wanted.append(g.column)

    # Ищем колонки, в которых живут спецслучаи, — они и делают фикстуру ценной.
    for needle in ("ОТМЕНА", "http"):
        for g in groups:
            if g.column in wanted:
                continue
            hit = any(
                needle.lower() in (r[c] or "").lower()
                for r in rows
                for c in (g.column, g.column + 3)
                if c < len(r)
            )
            if hit:
                wanted.append(g.column)
                break
    return sorted(wanted)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--src", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    args = ap.parse_args()

    rows = list(csv.reader(io.StringIO(args.src.read_text("utf-8-sig"))))
    columns = list(KEEP_COLUMNS)
    for start in pick_columns(rows):
        columns.extend(range(start, start + BLOCK))

    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="\n")
    for row in rows:
        writer.writerow([row[c] if c < len(row) else "" for c in columns])

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(gzip.compress(buf.getvalue().encode("utf-8")))
    print(f"{len(columns)} колонок, {len(rows)} строк -> {args.out} "
          f"({args.out.stat().st_size} байт)")


if __name__ == "__main__":
    main()
