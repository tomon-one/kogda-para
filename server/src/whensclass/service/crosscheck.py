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
import logging
import re
import zoneinfo
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass

from ..config import settings
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
    for (gid, day), theirs in sorted(by_group_day.items(), key=lambda kv: (kv[0][1], names[kv[0][0]])):
        ours = snapshot.schedule.get(gid, {}).get(day, [])
        matched = sum(
            1 for p in theirs
            if any(x.number == p.number and _same_subject(p.subject, x.subject) for x in ours)
        )
        if len(theirs) >= MIN_PAIRS and matched < MIN_SHARE * len(theirs):
            out.append(Mismatch(names[gid], day, matched, len(theirs), day in snapshot.unread.get(gid, {})))
    return out


class CrossCheck:
    def __init__(self, snapshot: Callable[[], Snapshot | None]):
        self.snapshot = snapshot
        self.reported: set[tuple[str, dt.date]] = set()

    def run(self) -> None:
        snap = self.snapshot()
        if snap is None:
            return
        now = dt.datetime.now(zoneinfo.ZoneInfo(settings.timezone))
        today = now.date()
        try:
            sheets = sheet_index.list_sheets()
        except Exception as exc:  # тревогу о сбое API шлёт list_sheets
            log.warning("сверка: список листов не получен (%s)", sheet_index.hide_key(exc))
            return
        weeks = []
        for sheet in sheets:
            week = sheet_week(sheet.title, now.year)
            if week and sheet.gid and not sheet.hidden and week[1] >= today and week[0] <= today + AHEAD:
                weeks.append((sheet, week))
        found: list[Mismatch] = []
        checked = 0
        for sheet, _ in weeks:
            try:
                pairs = parse_teacher_sheet(gsheets.fetch_sheet_csv(sheet.gid, sheet.title))
            except Exception as exc:
                log.warning("сверка: лист %r не прочитан: %s", sheet.title, sheet_index.hide_key(exc))
                continue
            checked += len(pairs)
            found += compare(snap, pairs, today)
        log.info("сверка с листами преподавателей: листов %d, пар %d, расхождений %d",
                 len(weeks), checked, len(found))
        # Непрочитанные дни уже в тревоге «не прочитаны» — второй раз о них не говорим.
        found = [m for m in found if not m.unread]
        now_set = {(m.group, m.day) for m in found}
        fresh = now_set - self.reported
        self.reported = now_set
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
