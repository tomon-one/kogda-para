"""Доменные модели расписания.

Держим их отдельно от формата таблицы и от формата API: таблицу колледжа
переделывают, JSON версионируется, а смысл «пара у группы в такой-то день»
не меняется.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime


def a1_column(column: int) -> str:
    """Номер колонки с нуля -> буквы, как в Sheets: 0 -> A, 26 -> AA, 664 -> YO.

    И в ссылке приложения, и в сообщениях разбора: номер «в колонке 146» ведёт
    человека на колонку левее — в Sheets счёт с единицы.
    """
    letters = ""
    n = column + 1
    while n:
        n, rem = divmod(n - 1, 26)
        letters = chr(65 + rem) + letters
    return letters


class SourceFormatChanged(Exception):
    """Таблица перестала соответствовать разобранному формату.

    Бросается вместо того, чтобы угадывать. Вызывающий обязан оставить
    предыдущий снимок в силе и позвать людей: показать пустое или чужое
    расписание хуже, чем показать вчерашнее.
    """


class ChangedAgainstPrevious(SourceFormatChanged):
    """Отказ по сверке с прежним принятым снимком: сдвиг против прежней
    версии, по вертикали, пропажа групп или имени в заголовке.

    Сверка идёт всегда с последним принятым, поэтому такой отказ залипает, если
    колледж оставил правку. Законную правку владелец пропускает рычагом
    `accept-next` (руководство по серверу, «Когда что-то не так»).
    """


class SheetTooSmall(SourceFormatChanged):
    """Лист похож на наш, но мал: дат или пар меньше порога.

    Так выглядит и сломанный лист, и следующий, который колледж только начал.
    Второй в окне служба пропускает, а не валит из-за него весь набор; лист,
    который начинается позже сегодняшнего дня (`starts`), — тоже, даже
    единственный (каникулы).
    """

    def __init__(self, message: str, starts: date | None = None):
        super().__init__(message)
        self.starts = starts


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
    # Дни, прочитанные по повторному заголовку с другой раскладкой: id группы
    # -> дата -> её колонка там. Ссылке «открыть таблицу»: колонка главного
    # заголовка в такие дни ведёт в чужой блок.
    day_columns: dict[str, dict[date, int]] = field(default_factory=dict)
    # Блоки главного заголовка без имени: колонка -> сколько под ней пар.
    # Только для проверки при обновлении, на диск не пишется.
    unnamed: dict[int, int] = field(default_factory=dict)
    # Дни групп, которые разбор не прочитал: id группы -> дата -> знал ли её
    # прежний снимок (True — тогда в schedule её прежние пары, если были).
    unread: dict[str, dict[date, bool]] = field(default_factory=dict)
    # Почему не прочитаны — словами для владельца; и с какого момента.
    unread_why: list[str] = field(default_factory=list)
    unread_since: datetime | None = None
    # Звонки дня, как они стоят в листе: дата -> номер пары -> начало и конец.
    # Дня нет — время в листе не прочиталось или снимок записан до появления поля.
    bells: dict[date, dict[str, list[str]]] = field(default_factory=dict)

    def column_of(
        self, group: GroupRef, day: date | None = None, gid: str | None = None
    ) -> int:
        """Колонка группы в листе, где лежит `day` (или в листе `gid`).

        Колонка первого листа на днях второго ведёт в чужую колонку, если
        раскладка сменилась.
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

        Кроме дат, на которые в первом только пустой каркас, а пары — во
        втором: день целиком второго листа — пары, звонки, место в книге и
        пометки «не прочитан». Иначе пары шли бы из второго, а время звонков,
        ссылка в таблицу и пометки — из пустого каркаса первого.
        """
        combined = Snapshot(
            sheet_title=f"{self.sheet_title} + {other.sheet_title}",
            groups=list(self.groups),
        )
        known = {g.id for g in self.groups}
        combined.groups += [g for g in other.groups if g.id not in known]

        def busy(snapshot: "Snapshot") -> set[date]:
            return {day for by in snapshot.schedule.values() for day, ls in by.items() if ls}

        mine, theirs = busy(self), busy(other)
        own = set(self.dates)
        # Дни второго листа: которых нет в первом или где в первом пустой каркас.
        second = {day for day in set(other.dates) | theirs
                  if day not in own or (day not in mine and day in theirs)}

        def pick(first: dict, from_other: dict, fill: bool = True) -> dict:
            # fill — чего у первого нет, берётся у второго (звонки, которых
            # первый не прочёл); пометки «не прочитан» — только на днях второго.
            base = from_other if fill else {day: v for day, v in from_other.items() if day in second}
            return {**base, **{day: v for day, v in first.items() if day not in second}}

        for gid in {g.id for g in combined.groups}:
            combined.schedule[gid] = pick(self.schedule.get(gid, {}), other.schedule.get(gid, {}))

        for gid in set(self.unread) | set(other.unread):
            days = pick(self.unread.get(gid, {}), other.unread.get(gid, {}), fill=False)
            if days:
                combined.unread[gid] = days
        combined.unread_why = self.unread_why + other.unread_why
        combined.dates = sorted(own | set(other.dates))
        combined.places = pick(self.places, other.places)
        combined.bells = pick(self.bells, other.bells)
        combined.sheet_columns = {**other.sheet_columns, **self.sheet_columns}
        for gid in set(self.day_columns) | set(other.day_columns):
            if cols := pick(self.day_columns.get(gid, {}), other.day_columns.get(gid, {}), fill=False):
                combined.day_columns[gid] = cols
        return combined

    def total_lessons(self) -> int:
        return sum(
            len(lessons)
            for by_date in self.schedule.values()
            for lessons in by_date.values()
        )
