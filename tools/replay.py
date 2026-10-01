"""Стенд: версии листа из архива — через службу, как в бою.

Гоняет настоящий `Refresher` (разбор, детектор сдвига, ворота, покрытие
для API) во временном каталоге состояния. Сеть,
поиск листа, архив и тревоги подменены: ничего никуда не уходит, ntfy не
зовётся. Боевой сервер не трогается.

Запуск — питоном службы из корня репозитория:

    server/.venv/bin/python tools/replay.py chain
    … tools/replay.py chain --since 2026-09-20 --until 2026-09-22
    … tools/replay.py one ПРАВЛЕНЫЙ.csv --prev ВЕРСИЯ.csv.gz [--today 2026-09-24]

`chain` — все версии по порядку, каждая против прежнего принятого снимка
(так их видела служба; отвергнутые не двигают снимок). Печатает строку на
версию: решение, причина, покрытие листа и выложенное (`cov` в API).

`one` — одна версия (например, испорченная стендом правок) против снимка,
собранного из `--prev`: «что сделала бы служба, приди этот текст следующим».
`today` по умолчанию — дата из имени `--prev`.

Архив: `<архив>/<gid>/<дата>-<время>-<хеш>[-rejected].csv.gz`, время —
Новосибирск. Отвергнутые лежат с `.txt` причины рядом. Каталог только для
чтения. Где он: `WHENSCLASS_ARCHIVE`, иначе копия состояния сервера
`~/WhensClass-state/history`, иначе локальный архив автора.
"""

from __future__ import annotations

import argparse
import atexit
import datetime as dt
import gzip
import logging
import pathlib
import shutil
import sys
import tempfile

REPO = pathlib.Path.cwd()
sys.path.insert(0, str(REPO / "server" / "src"))

from whensclass.api.payloads import published  # noqa: E402
from whensclass.service import alerts, refresher as refresher_mod  # noqa: E402
from whensclass.sources import gsheets, sheet_index  # noqa: E402
from whensclass.storage import history, snapshot_store  # noqa: E402

def _archive() -> pathlib.Path:
    import os

    chosen = os.environ.get("WHENSCLASS_ARCHIVE")
    if chosen:
        return pathlib.Path(chosen)
    return pathlib.Path.home() / "WhensClass-state" / "history"


ARCHIVE = _archive()
GID = "656498718"
TITLE = "расписание групп 01.-19.09"

SENT: list[tuple[str, str]] = []


def read(path: pathlib.Path) -> str:
    if path.suffix == ".gz":
        return gzip.open(path, "rt", encoding="utf-8").read()
    return path.read_text("utf-8")


def stamp(path: pathlib.Path) -> tuple[dt.date, str]:
    """Дата и время версии из имени файла: 2026-09-24-1200-… -> (24.09, 12:00)."""
    name = path.name
    return dt.date.fromisoformat(name[:10]), f"{name[11:13]}:{name[13:15]}"


# Каталог состояния стенда — на диске и с уборкой. 24 сентября 2026 он
# создавался в /tmp и не удалялся: стенд запустили 1423
# раза, 5,8 ГБ снимков легли в tmpfs, то есть в память, и забили zram
# машины владельца целиком.
SCRATCH = pathlib.Path.home() / ".cache" / "whensclass-replay"


class Bench:
    """Служба в пробирке: один лист, текст подаётся руками."""

    def __init__(self) -> None:
        SCRATCH.mkdir(exist_ok=True)
        # Убитый процесс atexit не зовёт: хвосты старше
        # часа подметает следующий запуск.
        for old in SCRATCH.glob("replay-*"):
            try:
                if dt.datetime.now().timestamp() - old.stat().st_mtime > 3600:
                    shutil.rmtree(old, ignore_errors=True)
            except OSError:
                pass
        self.state = pathlib.Path(tempfile.mkdtemp(prefix="replay-", dir=SCRATCH))
        atexit.register(shutil.rmtree, self.state, ignore_errors=True)
        self.text = ""
        sheet_index.resolve_window = lambda today, days, state_dir, deep=False: [(TITLE, GID)]
        gsheets.fetch_sheet_csv = lambda gid=None, title=None: self.text
        history.archive = lambda *a, **k: None

        def notify(kind, text, force=False, good=False):
            SENT.append((kind, text))
            return True

        alerts.notify = notify
        alerts.forget = lambda kind: None
        alerts.remember = lambda kind, at: None
        refresher_mod.alerts = alerts
        self.store = snapshot_store.SnapshotStore(self.state)
        self.service = refresher_mod.Refresher(self.store, self.state)

    def feed(self, text: str, today: dt.date) -> dict:
        self.text = text
        before = self.store.snapshot
        SENT.clear()
        updated = self.service.refresh(today=today)
        snap = self.store.snapshot
        out = {
            "decision": "принят" if updated else ("отвергнут" if self.service.status != "ok" else "без изменений"),
            "status": self.service.status,
            "error": self.service.last_error if not updated else None,
            "alerts": [k for k, _ in SENT],
        }
        if snap is not None:
            shown = published(snap, today)
            out["sheet"] = f"{snap.dates[0]}..{snap.dates[-1]}" if snap.dates else "-"
            out["cov"] = f"{shown[0]}..{shown[-1]}" if shown else "-"
            out["groups"] = len(snap.groups)
            out["lessons"] = snap.total_lessons()
        out["changed"] = snap is not before
        return out


def show(label: str, result: dict) -> None:
    line = f"{label}  {result['decision']:<13} {result['status']:<5}"
    if "sheet" in result:
        line += f" лист {result['sheet']} cov {result['cov']} групп {result['groups']} пар {result['lessons']}"
    if result["alerts"]:
        line += f" тревоги {','.join(result['alerts'])}"
    print(line)
    if result["error"]:
        print(f"    причина: {result['error'][:300]}")


def chain(args) -> None:
    files = sorted(p for p in (ARCHIVE / GID).glob("*.csv.gz"))
    bench = Bench()
    for path in files:
        day, time = stamp(path)
        if args.since and day < args.since or args.until and day > args.until:
            continue
        label = f"{day} {time} {'R' if 'rejected' in path.name else ' '}"
        show(label, bench.feed(read(path), day))


def one(args) -> None:
    prev = pathlib.Path(args.prev)
    today = args.today or stamp(prev)[0]
    bench = Bench()
    base = bench.feed(read(prev), today)
    show(f"прежний {prev.name[:15]}", base)
    show(f"проверяемый {pathlib.Path(args.csv).name}", bench.feed(read(pathlib.Path(args.csv)), today))


def main() -> None:
    logging.basicConfig(level=logging.ERROR, format="    журнал: %(levelname)s %(message)s")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("chain")
    c.add_argument("--since", type=dt.date.fromisoformat)
    c.add_argument("--until", type=dt.date.fromisoformat)
    o = sub.add_parser("one")
    o.add_argument("csv")
    o.add_argument("--prev", required=True)
    o.add_argument("--today", type=dt.date.fromisoformat)
    args = ap.parse_args()
    {"chain": chain, "one": one}[args.cmd](args)


if __name__ == "__main__":
    main()
