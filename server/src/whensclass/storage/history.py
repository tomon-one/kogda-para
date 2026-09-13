"""Архив сырых листов: каждая версия, которую видела служба.

11 сентября 2026 служба отвергла лист и двое суток писала об этом в журнал —
а самого листа в момент отказа ни у кого не осталось: к вечеру колледж уже
правил его дальше. Разбирать инцидент пришлось по снимкам, которых он не
касался. Теперь текст листа складывается сюда при каждом изменении, а
отвергнутый — с текстом ошибки рядом. Из архива делаются фикстуры и прогон
истории: «поправили разборщик — не перестал ли он принимать вчерашний лист».

Копия ~90 КБ в gzip; версий за день бывает десяток. Старше 90 дней — удаляем.
"""

from __future__ import annotations

import datetime as dt
import gzip
import logging
import pathlib
import time

log = logging.getLogger(__name__)

KEEP_DAYS = 90


def archive(
    state_dir: pathlib.Path,
    gid: str | None,
    text: str,
    digest: str,
    rejected: str | None = None,
    now: dt.datetime | None = None,
) -> pathlib.Path | None:
    """Кладёт лист в архив, если такого содержимого там ещё нет."""
    try:
        folder = state_dir / "history" / (gid or "no-gid")
        folder.mkdir(parents=True, exist_ok=True)
        short = digest[:8]
        if any(folder.glob(f"*-{short}*.csv.gz")):
            if rejected is None:
                return None
            # Тот же текст, но теперь отвергнут: ошибка — новость.
        stamp = (now or dt.datetime.now()).strftime("%Y-%m-%d-%H%M")
        suffix = "-rejected" if rejected is not None else ""
        path = folder / f"{stamp}-{short}{suffix}.csv.gz"
        if path.exists():
            return path
        path.write_bytes(gzip.compress(text.encode("utf-8")))
        if rejected is not None:
            path.with_suffix("").with_suffix(".txt").write_text(rejected + "\n", "utf-8")
        _prune(folder)
        return path
    except OSError as exc:
        log.warning("архив листа не записался: %s", exc)
        return None


def _prune(folder: pathlib.Path) -> None:
    deadline = time.time() - KEEP_DAYS * 86400
    for path in folder.iterdir():
        try:
            if path.is_file() and path.stat().st_mtime < deadline:
                path.unlink()
        except OSError:
            pass
