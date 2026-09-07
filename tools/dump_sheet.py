"""Скачивает лист расписания в файл.

Зачем: разрабатывать и чинить парсер по живой таблице, не дёргая Google на
каждый прогон тестов. Без него каждая правка парсера стоила бы 820 КБ трафика
и нескольких секунд ожидания.

    python tools/dump_sheet.py --out %TEMP%/sheet.csv
    python tools/dump_sheet.py --sheet "расписание групп 01.-05.09" --out x.csv
"""

from __future__ import annotations

import argparse
import pathlib
import urllib.parse
import urllib.request

SPREADSHEET_ID = "1FiMov0r4UUDKT6A56NWMImpoUakDC2YDevgaOpJQ7Qc"
DEFAULT_GID = "656498718"


def sheet_url(gid: str | None = None, sheet: str | None = None) -> str:
    base = f"https://docs.google.com/spreadsheets/d/{SPREADSHEET_ID}/gviz/tq"
    query = {"tqx": "out:csv"}
    if sheet:
        query["sheet"] = sheet
    else:
        query["gid"] = gid or DEFAULT_GID
    return f"{base}?{urllib.parse.urlencode(query)}"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--gid", default=None)
    ap.add_argument("--sheet", default=None, help="название листа вместо gid")
    ap.add_argument("--out", required=True, type=pathlib.Path)
    args = ap.parse_args()

    url = sheet_url(args.gid, args.sheet)
    data = urllib.request.urlopen(url, timeout=90).read()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(data)
    print(f"{len(data)} байт -> {args.out}")


if __name__ == "__main__":
    main()
