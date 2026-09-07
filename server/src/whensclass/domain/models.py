"""Доменные модели расписания.

Держим их отдельно от формата таблицы и от формата API: таблицу колледжа
переделывают, JSON версионируется, а смысл «пара у группы в такой-то день»
не меняется.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date


class SourceFormatChanged(Exception):
    """Таблица перестала соответствовать разобранному формату.

    Бросается вместо того, чтобы угадывать. Вызывающий обязан оставить
    предыдущий снимок в силе и позвать людей: показать пустое или чужое
    расписание хуже, чем показать вчерашнее.
    """


@dataclass(frozen=True)
class GroupRef:
    """Группа и колонка таблицы, в которой лежат её пары."""

    name: str
    id: str
    column: int


@dataclass(frozen=True)
class Lesson:
    number: int
    subject: str
    kind: str | None = None          # Лек, Пр, ... — как в таблице
    teachers: tuple[str, ...] = ()   # список с самого начала: их бывает двое
    room: str | None = None
    url: str | None = None           # вебинар вместо аудитории
    cancelled: bool = False
    note: str | None = None          # приписка из таблицы: «преподаватель заболел»


@dataclass
class Snapshot:
    """Разобранный лист целиком."""

    sheet_title: str
    groups: list[GroupRef] = field(default_factory=list)
    # id группы -> дата -> пары, отсортированные по номеру
    schedule: dict[str, dict[date, list[Lesson]]] = field(default_factory=dict)
    dates: list[date] = field(default_factory=list)

    @property
    def coverage(self) -> tuple[date, date] | None:
        return (self.dates[0], self.dates[-1]) if self.dates else None

    def total_lessons(self) -> int:
        return sum(
            len(lessons)
            for by_date in self.schedule.values()
            for lessons in by_date.values()
        )
