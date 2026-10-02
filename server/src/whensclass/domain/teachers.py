"""Расписание преподавателей — из того же снимка, что и расписание групп.

Отдельного листа с преподавателями в таблице колледжа нет: имена стоят под
названиями предметов в клетках групп. Поэтому расписание преподавателя мы не
читаем, а собираем — переворачивая уже разобранный снимок.
"""

from __future__ import annotations

import datetime as dt
import logging
import re
from dataclasses import dataclass, field

from .ids import group_id
from .models import Lesson, Snapshot

log = logging.getLogger(__name__)

# Имя-отчество без фамилии («Анастасия Дмитриевна») и сокращение («СПТ»).
PATRONYMIC_RE = re.compile(r"^[А-ЯЁ][а-яё]+\s+[А-ЯЁ][а-яё]+(?:вич|вна|ична|инична|ич)$")
ABBREVIATION_RE = re.compile(r"^[А-ЯЁA-Z]{2,6}$")


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
    # id краткой записи («Филатова Л.А.») -> id полной, к которой она сведена:
    # по старому id отвечает полная
    aliases: dict[str, str] = field(default_factory=dict)

    def days(self, teacher: str) -> dict[dt.date, list[TeacherLesson]]:
        return self.schedule.get(teacher, {})


def teacher_id(name: str) -> str:
    """Слаг преподавателя. Правила те же, что у групп."""
    return group_id(name)


def not_a_person(name: str) -> bool:
    """«Анастасия Дмитриевна» без фамилии и «СПТ» — в ячейке их видно, но
    преподавателем в списке они не становятся: id пропал бы с первой правкой
    ячейки, а выбравший его получил бы «вас больше нет»."""
    return bool(PATRONYMIC_RE.match(name) or ABBREVIATION_RE.match(name))


def _surname_initials(name: str) -> tuple[str, str]:
    """«Нечаев Сергей Алексеевич», «Филатова Л.А.» -> (фамилия, инициалы)."""
    words = [w for w in re.split(r"[\s.]+", name.replace("ё", "е").replace("Ё", "Е")) if w]
    if not words:
        return "", ""
    return words[0].casefold(), "".join(w[0].casefold() for w in words[1:])


def spelling_twin(index: TeacherIndex | None, name: str) -> str | None:
    """Id того же человека под другим написанием, если у него есть пары.

    Та же фамилия и те же инициалы: «Данил» и «Даниил», «Паловна» и
    «Павловна», «Е.С.» и «Евгения Сергеевна» — в живом листе 24 сентября 2026
    таких пар пять. Однофамильцы с другими инициалами — разные люди.
    """
    if index is None:
        return None
    surname, initials = _surname_initials(name)
    if not surname or not initials:
        return None
    for tid, other in index.names.items():
        same = other != name and _surname_initials(other) == (surname, initials)
        if same and index.schedule.get(tid):
            return tid
    return None


def _short(name: str) -> bool:
    """«Филатова Л.А.», «Сеченов Д. С.» — фамилия и одни инициалы."""
    words = [w for w in re.split(r"[\s.]+", name) if w]
    return len(words) >= 2 and all(len(w) == 1 for w in words[1:])


def _one_letter_apart(a: str, b: str) -> bool:
    """Одна вставка, удаление или замена буквы: «Данил» и «Даниил»,
    «Паловна» и «Павловна». «Анна» и «Алла» — две буквы, разные люди."""
    a, b = (x.casefold().replace("ё", "е") for x in (a, b))
    if len(a) > len(b):
        a, b = b, a
    if len(b) - len(a) > 1:
        return False
    i = 0
    while i < len(a) and a[i] == b[i]:
        i += 1
    if len(a) == len(b):
        return a[i + 1:] == b[i + 1:]
    return a[i:] == b[i + 1:]


def _full_names(snapshot: Snapshot) -> dict[str, str]:
    """Другое написание того же человека -> имя, под которым он в списке.

    В одном листе «Филатова Л.А.» и «Филатова Лариса Андреевна» — два id, и
    пары поделены между ними: выбравший один увидит половину. Так же и
    опечатка в полном имени, которую колледж исправил не во всех клетках:
    «Данил» и «Даниил» с той же фамилией и отчеством. Книга переименований
    тут не поможет: оба написания живут в листе одновременно.

    Полные имена сводятся, если у них та же фамилия, те же инициалы и
    разница в одну букву; в списке — то, у которого больше пар. Краткая
    запись — к полному, если человек с такими инициалами один.
    """
    count: dict[str, int] = {}
    for by_date in snapshot.schedule.values():
        for lessons in by_date.values():
            for lesson in lessons:
                for name in lesson.teachers:
                    if name.strip():
                        count[name.strip()] = count.get(name.strip(), 0) + 1
    full: dict[tuple[str, str], list[str]] = {}
    for name in sorted(count, key=lambda n: (-count[n], n)):
        if not _short(name):
            full.setdefault(_surname_initials(name), []).append(name)
    out = {}
    people: dict[tuple[str, str], list[str]] = {}
    for key, names in full.items():
        for name in names:
            main = next((p for p in people.get(key, []) if _one_letter_apart(p, name)), None)
            if main is None:
                people.setdefault(key, []).append(name)
            else:
                out[name] = main
    for name in count:
        if _short(name):
            candidates = people.get(_surname_initials(name), [])
            if len(candidates) == 1:
                out[name] = candidates[0]
    return out


def build_index(snapshot: Snapshot) -> TeacherIndex:
    """Переворачивает снимок: из «пары группы» получаются «пары преподавателя».

    Одна и та же пара у одного преподавателя может стоять сразу у нескольких
    групп — например, лекция для трёх подгрупп в одной аудитории. Такие
    склеиваем в одну запись, перечисляя группы через запятую: преподавателю
    важно, что в это время он занят, а не сколько раз это записано в таблице.

    Склеиваем по времени, а не по названию предмета: у разных групп одна и та
    же пара записана по-разному — «Физическая культура» и «Физическая культура
    / Адаптивная физическая культура». Из названий берём подробное.

    Но только пары в одном состоянии — аудитория, ссылка, онлайн, отмена: иначе
    отмена у одной группы стала бы отменой у всех. Разное остаётся отдельными
    записями, со своими группами и колонкой.
    """
    index = TeacherIndex()
    # (id, дата, номер пары, состояние) -> список групп
    Key = tuple[str, dt.date, int, tuple]
    merged: dict[Key, list[str]] = {}
    best: dict[Key, Lesson] = {}
    columns: dict[Key, int] = {}

    by_id = {g.id: g for g in snapshot.groups}
    full_names = _full_names(snapshot)
    for gid, by_date in snapshot.schedule.items():
        group = by_id.get(gid)
        if group is None:
            continue
        for day, lessons in by_date.items():
            for lesson in lessons:
                for teacher in lesson.teachers:
                    name = teacher.strip()
                    if not name or not_a_person(name):
                        continue
                    if name in full_names:
                        try:
                            index.aliases[teacher_id(name)] = teacher_id(full_names[name])
                        except ValueError:
                            pass
                        name = full_names[name]
                    try:
                        tid = teacher_id(name)
                    except ValueError:
                        # Разбор ячеек такое уже отсеивает, но индекс — последняя
                        # линия: одно такое имя не должно ронять его целиком.
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
