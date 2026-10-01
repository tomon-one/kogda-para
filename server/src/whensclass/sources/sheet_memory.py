"""Память поиска листов: какой лист какие даты покрывает и на какой день листа нет.

Обе — на диске в каталоге состояния: искать по книге дорого (кандидат — лист в
мегабайт и больше), а набор листов пересобирается часто.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import pathlib

from ..storage.atomic import write_json

log = logging.getLogger(__name__)


class SheetIndex:
    """Помнит, какой лист какие даты покрывает, чтобы не искать каждый раз."""

    def __init__(self, state_dir: pathlib.Path):
        self.path = state_dir / "sheet_index.json"
        self.known: dict[str, dict] = {}
        self._load()

    def _load(self) -> None:
        try:
            self.known = json.loads(self.path.read_text("utf-8"))
        except (OSError, ValueError):
            self.known = {}

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        write_json(self.path, self.known, indent=1)

    def remember(self, title: str, gid: str | None, first: dt.date, last: dt.date) -> None:
        if gid is not None:
            # Лист переименовали, не пересоздавая: 11 сентября 2026 «расписание
            # групп 01.-05.09» стал «…01.-19.09» с тем же gid. Старое имя с тем
            # же gid — тот же лист; оставить его значит подписывать снимок
            # старым именем и читать один лист дважды.
            for other, info in list(self.known.items()):
                if other != title and info.get("gid") == gid:
                    del self.known[other]
        self.known[title] = {
            "gid": gid,
            "from": first.isoformat(),
            "to": last.isoformat(),
            "probed": dt.date.today().isoformat(),
        }
        self.save()

    def forget(self, title: str) -> None:
        """Лист удалили или пересоздали — запись о нём больше не правда."""
        if title in self.known:
            del self.known[title]
            self.save()

    def nearest(self, day: dt.date) -> tuple[str, str | None] | None:
        """Ближайший по датам лист, когда день не покрыт ни одним.

        Воскресений в листах колледжа нет, поэтому день между двумя листами не
        покрыт ничем: старый кончился в субботу, новый начнётся в понедельник.
        Раньше это роняло поиск, служба уходила в stale на все сутки и отдавала
        пустую неделю — при том что новый лист уже был опубликован и найден.

        Ближайший лист лучше пустоты, и будущий предпочтительнее прошедшего:
        в воскресенье человек смотрит на неделю, которая начнётся завтра.
        """
        best = None
        for title, info in self.known.items():
            try:
                first = dt.date.fromisoformat(info["from"])
                last = dt.date.fromisoformat(info["to"])
            except (KeyError, ValueError):
                continue
            if day < first:
                key = (0, (first - day).days)
            elif day > last:
                key = (1, (day - last).days)
            else:
                key = (0, 0)
            if best is None or key < best[0]:
                best = (key, (title, info.get("gid")))
        return best[1] if best else None

    def following(self, after: dt.date) -> tuple[str, str | None] | None:
        """Ближайший лист, начинающийся позже указанного дня.

        Спрашивать вместо этого `covering(after + 1)` нельзя, хотя раньше так и
        было: между листами всегда лежит воскресенье, которого нет ни в одном
        из них. Память отвечала «не знаю» ровно на той границе, ради которой её
        и спрашивают, — и неделя вперёд в пятницу обрывалась субботой, даже
        когда следующий лист был давно найден и записан.
        """
        best = None
        for title, info in self.known.items():
            try:
                first = dt.date.fromisoformat(info["from"])
            except (KeyError, ValueError):
                continue
            if first > after and (best is None or first < best[0]):
                best = (first, (title, info.get("gid")))
        return best[1] if best else None

    def covering(self, day: dt.date) -> tuple[str, str | None] | None:
        for title, info in self.known.items():
            try:
                first = dt.date.fromisoformat(info["from"])
                last = dt.date.fromisoformat(info["to"])
            except (KeyError, ValueError):
                continue
            if first <= day <= last:
                return title, info.get("gid")
        return None


# Сколько помнить, что на день листа нет. Пока сегодняшний день за краем
# покрытия, набор листов пересобирается на каждом заходе, и без этой памяти
# служба каждые двадцать минут заново качала и разбирала всех кандидатов.
# Новый лист всё равно найдётся сразу: слежка за книгой
# (раз в полчаса, с ключом) при новом имени ищет глубоко, мимо этой памяти, и
# так же — ночной поиск.
MISS_TTL = dt.timedelta(hours=2)


def _misses_path(state_dir: pathlib.Path) -> pathlib.Path:
    return state_dir / "sheet_misses.json"


def _recent_miss(state_dir: pathlib.Path, day: dt.date) -> bool:
    try:
        misses = json.loads(_misses_path(state_dir).read_text("utf-8"))
        at = dt.datetime.fromisoformat(misses[day.isoformat()])
    except (OSError, ValueError, KeyError, TypeError):
        return False
    return dt.datetime.now(dt.timezone.utc) - at < MISS_TTL


def _remember_miss(state_dir: pathlib.Path, day: dt.date) -> None:
    now = dt.datetime.now(dt.timezone.utc)
    try:
        misses = json.loads(_misses_path(state_dir).read_text("utf-8"))
    except (OSError, ValueError):
        misses = {}
    # Старое не копим: нужна память на часы, а не на семестр.
    misses = {
        k: v for k, v in misses.items()
        if isinstance(v, str) and now - dt.datetime.fromisoformat(v) < MISS_TTL
    }
    misses[day.isoformat()] = now.isoformat()
    try:
        path = _misses_path(state_dir)
        write_json(path, misses)
    except OSError as exc:
        log.warning("память о ненайденном листе не записалась: %s", exc)
