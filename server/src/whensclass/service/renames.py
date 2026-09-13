"""Переименования: когда идентификатор меняется вместе с именем.

Идентификатор группы и преподавателя — это их имя, переписанное латиницей,
и он лежит в настройках на телефонах. А имена в таблице колледж правит:
11 сентября 2026 «01.26.Р.ИИ.ГД.ОФ.9-НСК» стала «01.26.Р.ИИ.ГД.Д.ОФ.9-НСК»,
и старый id перестал существовать. У кого он в настройках — расписания нет,
пока не выберет группу заново, а приложение об этом даже не предупредит.
Для группы из двадцати человек это «все без пар» в одно утро.

Поэтому служба помнит переименования и по старому id отвечает расписанием
нового. Признак переименования — не похожесть имён, а совпадение пар: старый
id исчез, новый появился, и пары нового за общие даты — те же, что были у
старого. У переименованной группы колонка та же, так что совпадение полное;
у нового курса, занявшего колонку, — никакого. Похожесть имён только
разводит кандидатов между собой.

Книга лежит в `renames.json` рядом со снимком и переживает перезапуск.
"""

from __future__ import annotations

import datetime as dt
import difflib
import json
import logging
import pathlib
import threading

from ..domain.models import Snapshot
from ..domain.teachers import TeacherIndex

log = logging.getLogger(__name__)

# След сущности — набор пар, по которому её узнают под новым именем.
Trace = frozenset

# Меньше трёх общих пар — совпадение случайное. И меньше четырёх пятых
# прежних пар — уже не «та же группа», даже если часть пар сошлась: пока
# группу переименовывали, ей могли переписать и расписание, но не целиком.
MIN_SHARED = 3
MIN_SHARE = 0.8


def group_traces(snapshot: Snapshot) -> dict[str, Trace]:
    """Пары каждой группы целиком: у той же колонки они совпадут один в один."""
    return {
        gid: frozenset(
            (day, x.number, x.subject, x.teachers, x.room, x.url, x.online, x.cancelled)
            for day, lessons in by_date.items()
            for x in lessons
        )
        for gid, by_date in snapshot.schedule.items()
    }


def teacher_traces(index: TeacherIndex) -> dict[str, Trace]:
    """Пары преподавателя без группы: группу могли переименовать в тот же час."""
    return {
        tid: frozenset(
            (day, entry.lesson.number, entry.lesson.subject)
            for day, entries in by_date.items()
            for entry in entries
        )
        for tid, by_date in index.schedule.items()
    }


def detect(old: dict[str, Trace], new: dict[str, Trace]) -> dict[str, str]:
    """Кто в кого переименован: старый id -> новый."""
    vanished = sorted(set(old) - set(new))
    appeared = sorted(set(new) - set(old))
    if not vanished or not appeared:
        return {}

    renames: dict[str, str] = {}
    for old_id in vanished:
        trace = old[old_id]
        if len(trace) < MIN_SHARED:
            continue
        scored: list[tuple[tuple[int, float], str]] = []
        for new_id in appeared:
            shared = len(trace & new[new_id])
            if shared < MIN_SHARED or shared < MIN_SHARE * len(trace):
                continue
            # Из нескольких кандидатов с теми же парами (колонка на две
            # группы) берём того, чьё имя ближе к прежнему.
            score = (shared, difflib.SequenceMatcher(None, old_id, new_id).ratio())
            scored.append((score, new_id))
        if not scored:
            continue
        scored.sort(reverse=True)
        if len(scored) > 1 and scored[0][0] == scored[1][0]:
            # Два кандидата неотличимы — это не переименование, а разделение:
            # 13 сентября 2026 КВД-926 стала КВД-926/1 и КВД-926/2 с теми же
            # парами. Половине группы любой из ответов — чужая подгруппа.
            # Честнее не отвечать вовсе: пусть выберут себя заново.
            log.info(
                "%r исчез, а на его место претендуют %s поровну — не гадаю",
                old_id, [new_id for _, new_id in scored[:2]],
            )
            continue
        renames[old_id] = scored[0][1]
    return renames


class RenameBook:
    """Что во что переименовано — для групп и преподавателей отдельно."""

    def __init__(self, state_dir: pathlib.Path):
        self.path = state_dir / "renames.json"
        self._lock = threading.Lock()
        self.groups: dict[str, str] = {}
        self.teachers: dict[str, str] = {}
        self._load()

    def _load(self) -> None:
        try:
            data = json.loads(self.path.read_text("utf-8"))
            self.groups = dict(data.get("groups", {}))
            self.teachers = dict(data.get("teachers", {}))
        except (OSError, ValueError, AttributeError):
            self.groups, self.teachers = {}, {}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(
            json.dumps(
                {
                    "groups": self.groups,
                    "teachers": self.teachers,
                    "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                },
                ensure_ascii=False,
                indent=1,
            ),
            "utf-8",
        )
        tmp.replace(self.path)

    def group(self, gid: str) -> str | None:
        """Новый id группы, если старый переименован."""
        return self.groups.get(gid)

    def teacher(self, tid: str) -> str | None:
        return self.teachers.get(tid)

    def record(
        self,
        previous: Snapshot,
        current: Snapshot,
        previous_teachers: TeacherIndex,
        current_teachers: TeacherIndex,
    ) -> None:
        """Сверяет два снимка подряд и дописывает книгу."""
        old_groups = {g.id: g.name for g in previous.groups}
        new_groups = {g.id: g.name for g in current.groups}
        # Следы строятся по всем парам снимка — незачем, пока состав id тот же,
        # а это почти каждое обновление.
        found_groups = (
            detect(group_traces(previous), group_traces(current))
            if old_groups.keys() != new_groups.keys()
            else {}
        )
        found_teachers = (
            detect(teacher_traces(previous_teachers), teacher_traces(current_teachers))
            if previous_teachers.names.keys() != current_teachers.names.keys()
            else {}
        )
        with self._lock:
            changed = self._update(
                self.groups, found_groups, alive=set(new_groups),
                names=(old_groups, new_groups), what="группа",
            )
            changed |= self._update(
                self.teachers, found_teachers, alive=set(current_teachers.names),
                names=(previous_teachers.names, current_teachers.names), what="преподаватель",
            )
            if changed:
                self._save()

    @staticmethod
    def _update(
        book: dict[str, str],
        found: dict[str, str],
        alive: set[str],
        names: tuple[dict[str, str], dict[str, str]],
        what: str,
    ) -> bool:
        changed = False
        for old_id, new_id in found.items():
            log.warning(
                "%s %r (%s) теперь %r (%s): по старому id отвечаю расписанием нового",
                what, names[0].get(old_id, old_id), old_id, names[1].get(new_id, new_id), new_id,
            )
            book[old_id] = new_id
            # Цепочку не храним: A -> B -> C сворачивается в A -> C, иначе
            # ответ по A придётся искать в два шага, а по три — никогда.
            for key, value in list(book.items()):
                if value == old_id:
                    book[key] = new_id
            changed = True
        # Старое имя вернулось в таблицу — значит, это снова настоящая
        # сущность, и подменять её нельзя.
        for old_id in list(book):
            if old_id in alive:
                del book[old_id]
                changed = True
        return changed
