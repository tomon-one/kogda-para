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


class SheetTooSmall(SourceFormatChanged):
    """Лист похож на наш, но мал: дат или пар меньше порога.

    Так выглядит и сломанный лист, и следующий, который колледж только начал.
    Второй в окне служба пропускает, а не валит из-за него весь набор
    (третий аудит, В18 прогона 1).
    """


@dataclass(frozen=True)
class GroupRef:
    """Группа и колонка таблицы, в которой лежат её пары."""

    name: str
    id: str
    column: int


@dataclass(frozen=True)
class SheetPlace:
    """Где в книге лежит день: лист и строка с датой.

    Нужно ссылке «открыть таблицу»: по `#gid=...&range=ZK130` Google Sheets
    открывает нужный лист и подводит к ячейке, а человеку не приходится
    искать свою колонку среди семисот.
    """

    gid: str | None
    row: int   # как в интерфейсе Sheets: с единицы


@dataclass(frozen=True)
class Lesson:
    number: int
    subject: str
    kind: str | None = None          # Лек, Пр, ... — как в таблице
    teachers: tuple[str, ...] = ()   # список с самого начала: их бывает двое
    room: str | None = None
    url: str | None = None           # вебинар вместо аудитории
    # Занятие идёт не в аудитории. Ссылка есть не всегда: чаще в колонке
    # аудитории просто написано «онлайн», а ссылку дают позже или в чате.
    online: bool = False
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
    # дата -> где она в книге. Пусто у снимков, записанных до появления поля.
    places: dict[date, SheetPlace] = field(default_factory=dict)
    # gid листа -> id группы -> её колонка в этом листе. У склеенного снимка
    # двух листов раскладка своя у каждого, а в `groups` — колонки первого.
    sheet_columns: dict[str, dict[str, int]] = field(default_factory=dict)
    # Блоки главного заголовка без имени: колонка -> сколько под ней пар.
    # Только для проверки при обновлении, на диск не пишется.
    unnamed: dict[int, int] = field(default_factory=dict)

    def column_of(
        self, group: GroupRef, day: date | None = None, gid: str | None = None
    ) -> int:
        """Колонка группы в листе, где лежит `day` (или в листе `gid`).

        Раньше всегда бралась колонка первого листа, и ссылка к ячейке на днях
        второго листа вела в чужую колонку, если раскладка сменилась (второй
        аудит, М28).
        """
        if gid is None and day is not None and day in self.places:
            gid = self.places[day].gid
        return self.sheet_columns.get(gid or "", {}).get(group.id, group.column)

    @property
    def coverage(self) -> tuple[date, date] | None:
        return (self.dates[0], self.dates[-1]) if self.dates else None

    def merged_with(self, other: "Snapshot") -> "Snapshot":
        """Приклеивает соседний лист.

        Листы колледжа идут подряд и не пересекаются по датам, а состав групп
        в них один и тот же. Если группа встречается в обоих, берём её пары из
        обоих листов; даты, которые есть в первом, вторым не переписываем —
        текущий лист главнее, он свежее правится.
        """
        combined = Snapshot(
            sheet_title=f"{self.sheet_title} + {other.sheet_title}",
            groups=list(self.groups),
        )
        known = {g.id for g in self.groups}
        combined.groups += [g for g in other.groups if g.id not in known]

        for gid in {g.id for g in combined.groups}:
            by_date = dict(other.schedule.get(gid, {}))
            by_date.update(self.schedule.get(gid, {}))
            combined.schedule[gid] = by_date

        combined.dates = sorted(set(self.dates) | set(other.dates))
        combined.places = {**other.places, **self.places}
        combined.sheet_columns = {**other.sheet_columns, **self.sheet_columns}
        return combined

    def total_lessons(self) -> int:
        return sum(
            len(lessons)
            for by_date in self.schedule.values()
            for lessons in by_date.values()
        )
