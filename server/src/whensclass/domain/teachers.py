"""Расписание преподавателей — из того же снимка, что и расписание групп.

Отдельного листа с преподавателями в таблице колледжа нет: имена стоят под
названиями предметов в клетках групп. Поэтому расписание преподавателя мы не
читаем, а собираем — переворачивая уже разобранный снимок.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field

from .ids import group_id
from .models import Lesson, Snapshot


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
    """
    index = TeacherIndex()
    # (id, дата, номер пары) -> список групп
    merged: dict[tuple[str, dt.date, int], list[str]] = {}
    best: dict[tuple[str, dt.date, int], Lesson] = {}
    columns: dict[tuple[str, dt.date, int], int] = {}

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
                    tid = teacher_id(name)
                    index.names.setdefault(tid, name)
                    key = (tid, day, lesson.number)
                    merged.setdefault(key, []).append(group.name)
                    columns.setdefault(key, group.column)
                    known = best.get(key)
                    if known is None or len(lesson.subject) > len(known.subject):
                        best[key] = lesson

    for key, groups in merged.items():
        tid, day, _number = key
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
