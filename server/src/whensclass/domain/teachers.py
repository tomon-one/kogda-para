"""Расписание преподавателей — из того же снимка, что и расписание групп.

Отдельного листа с преподавателями в таблице колледжа нет: имена стоят под
названиями предметов в клетках групп. Поэтому расписание преподавателя мы не
читаем, а собираем — переворачивая уже разобранный снимок.
"""

from __future__ import annotations

import datetime as dt
import logging
from dataclasses import dataclass, field

from .ids import group_id
from .models import Lesson, Snapshot

log = logging.getLogger(__name__)


@dataclass
class TeacherLesson:
    """Пара в расписании преподавателя: та же, но с указанием группы."""

    lesson: Lesson
    group_name: str
    # Колонка группы в листе — чтобы ссылка «открыть таблицу» подвела к
    # ячейке этой пары. У склеенной из нескольких групп берём первую.
    column: int | None = None


@dataclass
class TeacherIndex:
    """Кто когда преподаёт. Собирается один раз на снимок."""

    # id преподавателя -> его имя как в таблице
    names: dict[str, str] = field(default_factory=dict)
    # id -> дата -> пары
    schedule: dict[str, dict[dt.date, list[TeacherLesson]]] = field(default_factory=dict)
    # id -> id групп, у которых он ведёт пары хоть раз в листе: день
    # преподавателя выложен, только когда колледж дописал все его группы
    groups: dict[str, set[str]] = field(default_factory=dict)

    def days(self, teacher: str) -> dict[dt.date, list[TeacherLesson]]:
        return self.schedule.get(teacher, {})


def teacher_id(name: str) -> str:
    """Слаг преподавателя. Правила те же, что у групп."""
    return group_id(name)


def build_index(snapshot: Snapshot) -> TeacherIndex:
    """Переворачивает снимок: из «пары группы» получаются «пары преподавателя».

    Одна и та же пара у одного преподавателя может стоять сразу у нескольких
    групп — например, лекция для трёх подгрупп в одной аудитории. Такие
    склеиваем в одну запись, перечисляя группы через запятую: преподавателю
    важно, что в это время он занят, а не сколько раз это записано в таблице.

    Склеиваем по времени, а не по названию предмета: у разных групп одна и та
    же пара записана по-разному — «Физическая культура» и «Физическая культура
    / Адаптивная физическая культура». Из названий берём подробное.

    Но только пары в одном состоянии — аудитория, ссылка, онлайн, отмена. Раньше
    склеивалось всё в одном слоте, и всем группам раздавалось состояние одной:
    отменили пару у одной группы — отменена у всех, у одной другая аудитория —
    у всех та же (второй аудит, В1). Разное остаётся отдельными записями, со
    своими группами и колонкой.
    """
    index = TeacherIndex()
    # (id, дата, номер пары, состояние) -> список групп
    Key = tuple[str, dt.date, int, tuple]
    merged: dict[Key, list[str]] = {}
    best: dict[Key, Lesson] = {}
    columns: dict[Key, int] = {}

    by_id = {g.id: g for g in snapshot.groups}
    for gid, by_date in snapshot.schedule.items():
        group = by_id.get(gid)
        if group is None:
            continue
        for day, lessons in by_date.items():
            for lesson in lessons:
                for teacher in lesson.teachers:
                    name = teacher.strip()
                    if not name:
                        continue
                    try:
                        tid = teacher_id(name)
                    except ValueError:
                        # Разбор ячеек такое уже отсеивает, но индекс — последняя
                        # линия: одно такое имя роняло его целиком, /v1/teachers
                        # отвечал 500, а снимок замерзал (второй аудит, К2).
                        log.warning("у преподавателя %r нет идентификатора — пропускаю", name)
                        continue
                    index.names.setdefault(tid, name)
                    index.groups.setdefault(tid, set()).add(gid)
                    state = (
                        lesson.room, lesson.url, lesson.online or bool(lesson.url),
                        lesson.cancelled,
                    )
                    key = (tid, day, lesson.number, state)
                    merged.setdefault(key, []).append(group.name)
                    columns.setdefault(key, snapshot.column_of(group, day))
                    known = best.get(key)
                    if known is None or len(lesson.subject) > len(known.subject):
                        best[key] = lesson

    for key, groups in merged.items():
        tid, day, _number, _state = key
        entry = TeacherLesson(
            lesson=best[key],
            group_name=", ".join(sorted(set(groups))),
            column=columns.get(key),
        )
        index.schedule.setdefault(tid, {}).setdefault(day, []).append(entry)

    for by_date in index.schedule.values():
        for day_lessons in by_date.values():
            day_lessons.sort(key=lambda x: x.lesson.number)
    return index
