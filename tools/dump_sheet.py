"""Скачивает лист расписания в файл.

Зачем: разрабатывать и чинить разборщик по живой таблице, не дёргая Google на
каждый прогон тестов. Без него каждая правка разборщика стоила бы мегабайта
трафика и нескольких секунд ожидания.

    python3 tools/dump_sheet.py --out /tmp/sheet.csv
    python3 tools/dump_sheet.py --gid 656498718 --out /tmp/sheet.csv

Тем же путём, что и служба: сырой экспорт `export?format=csv&gid=`. По имени
листа не скачать — экспорт знает только gid; имя ↔ gid даёт Sheets API
(`sources/gsheets.py`) или адресная строка таблицы (`#gid=`). Не gviz: он
теряет текст в «числовых» колонках (`docs/source-format.md`).
"""

from __future__ import annotations

import argparse
import pathlib
import urllib.parse
import urllib.request

SPREADSHEET_ID = "1FiMov0r4UUDKT6A56NWMImpoUakDC2YDevgaOpJQ7Qc"
DEFAULT_GID = "656498718"


def sheet_url(gid: str | None = None) -> str:
    base = f"https://docs.google.com/spreadsheets/d/{SPREADSHEET_ID}/export"
    return f"{base}?{urllib.parse.urlencode({'format': 'csv', 'gid': gid or DEFAULT_GID})}"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--gid", default=None, help="gid листа, по умолчанию текущий")
    ap.add_argument("--out", required=True, type=pathlib.Path)
    args = ap.parse_args()

    data = urllib.request.urlopen(sheet_url(args.gid), timeout=90).read()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(data)
    print(f"{len(data)} байт -> {args.out}")


if __name__ == "__main__":
    main()
