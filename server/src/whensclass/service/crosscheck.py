"""Сверка разбора с листами преподавателей колледжа.

Лист преподавателей — отдельная выгрузка той же недели. Пара в нём есть — у
названной группы в тот же день под тем же номером у нас должен быть тот же
предмет. Не сходится большинство пар группы за день — значит, мы читаем её
колонку не там: сдвиг блока, неделя не тем способом, стёртая колонка. Так
ловится то, что шлюзы по объёму пропускают, пока ошибка у одной-двух групп.

Только тревога владельцу: ответы клиентам сверка не меняет. Отсутствие пары
в листе преподавателей не значит ничего — поток записан одной группой.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import pathlib
import re
import zoneinfo
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass

from ..api.payloads import published
from ..config import settings
from ..storage.atomic import write_json
from ..domain.ids import group_id
from ..domain.models import Snapshot
from ..parser.groups import split_group_names
from ..parser.teacher_sheet import TeacherPair, parse_teacher_sheet, sheet_week
from ..sources import gsheets, sheet_index
from . import alerts

log = logging.getLogger(__name__)

# Группа-день расходится, если пар для сверки хватает и сошлось меньше половины:
# одна несошедшаяся пара — поздняя правка листа групп («Кураторский час»
# вместо пары), а не сдвиг.
MIN_PAIRS = 2
MIN_SHARE = 0.5
# Насколько вперёд смотреть листы: дальше колледж их ещё не выкладывает.
AHEAD = dt.timedelta(days=14)


@dataclass(frozen=True)
class Mismatch:
    group: str
    day: dt.date
    matched: int
    total: int
    unread: bool


def _norm(text: str) -> str:
    return re.sub(r"[^\w]+", "", text.lower().replace("ё", "е"))


def _same_subject(theirs: str, ours: str) -> bool:
    # У них «Физика (Лек)», у нас «Физика» и вид отдельно.
    a, b = _norm(theirs), _norm(ours)
    return bool(b) and a.startswith(b)


def compare(snapshot: Snapshot, pairs: list[TeacherPair], today: dt.date) -> list[Mismatch]:
    names = {g.id: g.name for g in snapshot.groups}
    by_group_day: dict[tuple[str, dt.date], list[TeacherPair]] = defaultdict(list)
    for p in pairs:
        if p.day < today:
            continue
        for name in split_group_names(p.group):
            try:
                gid = group_id(name)
            except ValueError:
                continue
            # Имя, которого нет в листе групп (прежнее имя в выгрузке), — не сверяем.
            if gid in names:
                by_group_day[(gid, p.day)].append(p)
    out = []
    # Только выложенные дни: дня нет в листе групп или там пустой каркас дат
    # (следующий лист не дописан или не принят, недописанный хвост) — сверять
    # не с чем, пустота тут не наша ошибка чтения.
    days = set(published(snapshot, today))
    for (gid, day), theirs in sorted(by_group_day.items(), key=lambda kv: (kv[0][1], names[kv[0][0]])):
        if day not in days:
            continue
        ours = snapshot.schedule.get(gid, {}).get(day, [])
        matched = sum(
            1 for p in theirs
            if any(x.number == p.number and _same_subject(p.subject, x.subject) for x in ours)
        )
        if len(theirs) >= MIN_PAIRS and matched < MIN_SHARE * len(theirs):
            out.append(Mismatch(names[gid], day, matched, len(theirs), day in snapshot.unread.get(gid, {})))
    return out


class CrossCheck:
    """`snapshot` — принятый снимок или None, когда сверять нельзя (служба держит
    прежний лист: новый лист преподавателей разошёлся бы с ним целиком)."""

    def __init__(self, snapshot: Callable[[], Snapshot | None], directory: pathlib.Path | None = None):
        self.snapshot = snapshot
        # О чём уже сказано — на диске: служба перезапускается при каждой выкладке.
        self.path = directory / "crosscheck.json" if directory else None
        self.reported: set[tuple[str, dt.date]] = set()
        if self.path:
            try:
                self.reported = {
                    (g, dt.date.fromisoformat(d)) for g, d in json.loads(self.path.read_text(encoding="utf-8"))
                }
            except FileNotFoundError:
                pass
            except (OSError, ValueError, TypeError) as exc:
                log.warning("crosscheck.json не читается (%s) — начну с пустого", exc)

    def _save(self) -> None:
        if not self.path:
            return
        # Не записалось (диск) — тревога всё равно уходит: о сказанном помнит
        # память, а после перезапуска о том же скажут ещё раз — лучше, чем никогда.
        try:
            write_json(self.path, sorted([g, d.isoformat()] for g, d in self.reported))
        except OSError as exc:
            log.warning("crosscheck.json не записался: %s", exc)

    def run(self) -> None:
        snap = self.snapshot()
        if snap is None or settings.freeze:
            return
        # Листы преподавателей большие: при 429 они отняли бы у расписания последнее.
        if gsheets.throttled():
            log.info("сверка: Google недавно ответил 429 — пропускаю заход")
            return
        now = dt.datetime.now(zoneinfo.ZoneInfo(settings.timezone))
        today = now.date()
        try:
            sheets = sheet_index.list_sheets(max_age=dt.timedelta(hours=1))
        except Exception as exc:  # тревогу о сбое API шлёт list_sheets
            log.warning("сверка: список листов не получен (%s)", sheet_index.hide_key(exc))
            return
        weeks = []
        unnamed = []
        for sheet in sheets:
            if sheet.hidden or not sheet.gid or not re.search(r"препод", sheet.title, re.IGNORECASE):
                continue
            week = sheet_week(sheet.title, today)
            if week is None:
                unnamed.append(sheet.title)
            elif week[1] >= today and week[0] <= today + AHEAD:
                weeks.append((sheet, week))
        found: list[Mismatch] = []
        checked = 0
        failed: list[tuple[dt.date, dt.date]] = []
        broken = list(unnamed)
        for sheet, week in weeks:
            try:
                pairs = parse_teacher_sheet(gsheets.fetch_sheet_csv(sheet.gid, sheet.title))
            except Exception as exc:
                log.warning("сверка: лист %r не прочитан: %s", sheet.title, sheet_index.hide_key(exc))
                failed.append(week)
                if not sheet_index._is_network(exc):
                    broken.append(sheet.title)
                continue
            checked += len(pairs)
            found += compare(snap, pairs, today)
        log.info("сверка с листами преподавателей: листов %d, пар %d, расхождений %d",
                 len(weeks), checked, len(found))
        # Непрочитанные дни уже в тревоге «не прочитаны» — второй раз о них не говорим.
        found = [m for m in found if not m.unread]
        if broken:
            # Сверка не сторожит эти недели — тихо, раз в сутки.
            alerts.notify(
                "crosscheck-sheet",
                "Сверка с листами преподавателей не читает: " + "; ".join(f"«{t}»" for t in broken[:3])
                + ". Имя или раскладка листа не те (руководство по серверу, «Когда что-то не так»).",
                window=24 * 60 * 60, quiet=True,
            )
        else:
            alerts.forget("crosscheck-sheet")
        now_set = {(m.group, m.day) for m in found}
        fresh = now_set - self.reported
        # Расхождения недель, чей лист в этот заход не прочитался, держатся: иначе
        # о них сказали бы снова при следующем удачном чтении.
        kept = {(g, d) for g, d in self.reported if any(a <= d <= b for a, b in failed)}
        if now_set | kept != self.reported:
            self.reported = now_set | kept
            self._save()
        if not fresh:
            return
        shown = [m for m in found if (m.group, m.day) in fresh]
        text = "; ".join(
            f"{m.group} {m.day:%d.%m} — сошлось {m.matched} из {m.total}" for m in shown[:5]
        )
        more = f" и ещё {len(shown) - 5}" if len(shown) > 5 else ""
        alerts.notify(
            "crosscheck",
            f"Лист групп расходится с листом преподавателей: {text}{more}. Колонку читаем не "
            "там или колледж правил один лист без другого. Присмотреться (руководство по "
            "серверу, «Когда что-то не так»).",
            force=True,
        )
