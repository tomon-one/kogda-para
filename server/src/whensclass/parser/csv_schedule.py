"""Обход листа расписания: строки таблицы -> Snapshot.

Принимает уже разобранные строки, а не текст CSV. Разбор построен на форме,
которую давал gviz: шапка одной строкой «Дисциплина Преподаватель <группа>».
С 14 сентября 2026 лист берётся сырым экспортом, где шапка — три строки
столбиком; `collapse_export` приводит её к прежней форме, и обход остаётся
тем же.
"""

from __future__ import annotations

import dataclasses
import logging
import re
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, timedelta

from ..domain.ids import group_id
from ..domain.models import (
    GroupRef,
    Lesson,
    SheetTooSmall,
    Snapshot,
    SourceFormatChanged,
    a1_column,
)
from .cells import parse_lesson, replaced_of
from .dates import _parse_date
from .groups import (
    _warn_once,
    build_column_map,
    find_header_rows,
    header_blocks,
)

log = logging.getLogger(__name__)

# Ячейка с двумя числами через точку, дробь или дефис — это дата, которую
# не смогли прочитать, а не «нет даты». Молчать про такое нельзя: раньше
# день молча приклеивался к предыдущему.
_LOOKS_LIKE_DATE = re.compile(r"\d{1,2}\s*[.,/-]\s*\d{1,2}")
_LESSON_NO_RE = re.compile(r"^([1-9])$")
# Строка под парой несёт время звонка в колонке номера: «9-00-10.30».
_TIME_RE = re.compile(r"^\d{1,2}[.:-]\d{2}\s*[-–]\s*\d{1,2}[.:-]\d{2}$")
# Сдвиг не на целый блок («вставить 1–3 ячейки»): колонки +1 и +2 блока,
# всегда пустые, получают текст. В 225 версиях — не больше одной ячейки, у
# сдвига хоть на один день — от 70.
SPILL_REJECT = 5


@dataclass(frozen=True)
class Limits:
    """Границы правдоподобия листа. Не сошлось — значит таблицу переделали."""

    min_groups: int = 100
    min_dates: int = 5
    min_lessons: int = 500
    # Разрыв между соседними днями одного листа. Опечатка в месяце у одной
    # даты (14.10 вместо 14.09) даёт разрыв в месяц и растянула бы лист; а
    # зимние каникулы внутри листа (26.12 → 11.01) — шестнадцать дней, и с
    # прежним порогом в две недели лист отвергался целиком.
    max_gap_days: int = 25
    # Дальше этого от сегодня дата с парами — опечатка («2027» растянула бы
    # лист на год), а пустые даты — каркас на будущее, их просто отрезаем.
    # 24 сентября 2026 колледж вписал каркас на 39 дней вперёд; при прежних
    # 60 днях и отказе продление каркаса до конца ноября роняло обновление у
    # всех.
    max_days_ahead: int = 120

FULL_SHEET = Limits()
# Для фикстур из нескольких групп: структура та же, объём другой.
FIXTURE = Limits(min_groups=3, min_dates=2, min_lessons=5)


def _cell(row: list[str], col: int) -> str:
    return row[col] if col < len(row) else ""


def parse_sheet(
    rows: Iterable[list[str]],
    sheet_title: str,
    limits: Limits = FULL_SHEET,
    around: date | None = None,
    sheet_rows: list[int] | None = None,
) -> Snapshot:
    """`around` — сегодняшний день: дальше `limits.max_days_ahead` от него пар не ждём.

    `sheet_rows[i]` — номер строки листа, как его видит человек в Sheets, для
    строки `i` после схлопывания шапки. С ним сообщения разбора ведут в ту
    строку, что открывается в таблице: 20 сентября 2026 журнал писал «строка
    202», а в Sheets это была 214-я (разбор сбоя 20.09.2026).
    """
    rows = [list(r) for r in rows]

    def where(i: int) -> str:
        if sheet_rows is not None and i < len(sheet_rows):
            return f"строке {sheet_rows[i]} листа"
        return f"строке {i}"

    groups = build_column_map(rows, limits.min_groups)
    adopt: dict[int, list[str]] = {}
    skip = find_header_rows(rows, groups, limits.min_groups, where=where, adopt=adopt)
    groups, unnamed = _adopt_names(rows, groups, adopt, limits.min_groups)
    _check_spill(rows, groups, skip, where)

    snapshot = Snapshot(sheet_title=sheet_title, groups=groups)
    for group in groups:
        snapshot.schedule.setdefault(group.id, {})

    current: date | None = None
    seen_dates: list[date] = []
    # Порядок дат, как они встретились, без дедупликации: она и прятала
    # нарушение порядка. Дата, встреченная второй раз, в seen_dates не
    # попадала, и проверка «даты идут по возрастанию» её не видела.
    date_order: list[date] = []
    # Где в листе стоит каждая дата из date_order — чтобы отказ называл
    # строку, а не пересказывал весь столбец дат.
    date_where: list[str] = []

    # Номера пар каждого дня: обязаны идти 1, 2, 3… без пропусков и
    # повторов. Повтор даты (скопированный блок) и номер не по шаблону
    # («3 пара», пропущенная строка) ловятся здесь же.
    numbers_by_date: dict[date, list[int]] = {}
    # Даты дальше горизонта и где они в листе: с парами — отказ, без — отрезаем.
    far: dict[date, str] = {}
    # Где в листе дата встретилась впервые — для отказа при повторе.
    first_row: dict[date, str] = {}

    for i, row in enumerate(rows):
        if i in skip:
            continue

        cell = _cell(row, 0)
        try:
            found = _parse_date(cell)
        except SourceFormatChanged as exc:
            raise SourceFormatChanged(f"{exc} в {where(i)}") from exc
        if found is None and cell.strip():
            if _LOOKS_LIKE_DATE.search(cell):
                # Раньше такая ячейка просто не узнавалась: current оставался
                # на прошлом дне, и весь новый день дописывался к предыдущему —
                # с повторяющимися номерами пар и без единой жалобы.
                raise SourceFormatChanged(f"дата в {where(i)} не разобралась: {cell!r}")
            # Слово без чисел — «понедельник» над датой, «неделя 3»: не дата.
            log.info("в колонке дат в %s не дата: %r — пропускаю", where(i), cell.strip())
        if found is not None:
            if found.weekday() == 6:
                # Воскресений в листах колледжа не бывает: это опечатка в числе.
                raise SourceFormatChanged(f"дата {found:%d.%m.%Y} в {where(i)} — воскресенье")
            if around is not None and found > around + timedelta(days=limits.max_days_ahead):
                far.setdefault(found, where(i))
            if found != current:
                if found in first_row:
                    # Копия блока вместе с датой: раньше отказ говорил о
                    # номерах пар настоящего дня и без строки.
                    raise SourceFormatChanged(
                        f"дата {found:%d.%m.%Y} в {where(i)} уже была в {first_row[found]}"
                    )
                first_row[found] = where(i)
                date_order.append(found)
                date_where.append(where(i))
            current = found
            if current not in seen_dates:
                seen_dates.append(current)

        number_cell = _cell(row, 1).strip()
        m = _LESSON_NO_RE.match(number_cell)
        if not m:
            if number_cell and not _TIME_RE.match(number_cell):
                # В колонке номеров бывают только номера и время звонка.
                # «1-2», «кл. час», «3 пара» — новый способ записи, и что
                # с ним делать, должен решить человек, а не пропуск.
                raise SourceFormatChanged(
                    f"в колонке номеров пар в {where(i)} стоит {number_cell!r}"
                )
            continue
        if current is None:
            raise SourceFormatChanged(f"пара в {where(i)} раньше первой даты")
        number = int(m.group(1))
        if number == 1 and found is not None and numbers_by_date.get(current):
            raise SourceFormatChanged(
                f"дата {found:%d.%m.%Y} в {where(i)} повторена: этот день уже начат в "
                f"{first_row[found]}"
            )
        if number == 1 and found is None and numbers_by_date.get(current):
            # С первой пары начинается новый день, а даты у него нет. Раньше
            # такой день молча дописывался к предыдущему, и отказ приходил
            # окольно — через номера пар соседнего дня: 20 сентября 2026 вместо
            # «21.09.2026 понедельник» стояла запятая, а err говорил про
            # субботу (разбор сбоя 20.09.2026).
            raise SourceFormatChanged(
                f"в {where(i)} начинается новый день (1-я пара), а даты нет: "
                f"{cell.strip()!r} — после {current}"
            )
        expected = len(numbers_by_date.setdefault(current, [])) + 1
        if number != expected:
            raise SourceFormatChanged(
                f"номера пар {current} идут {numbers_by_date[current] + [number]}: "
                f"ждал {expected}"
            )
        numbers_by_date[current].append(number)

        teacher_row = _teacher_row(rows, i, skip, where)

        for group in groups:
            lesson = parse_lesson(
                number=number,
                subject_raw=_cell(row, group.column),
                room_raw=_cell(row, group.column + 3),
                teacher_raw=_cell(teacher_row, group.column) if teacher_row else "",
            )
            if lesson is not None:
                snapshot.schedule[group.id].setdefault(current, []).append(lesson)
        for col in unnamed:
            if _cell(row, col).strip():
                unnamed[col] += 1

    for by_date in snapshot.schedule.values():
        for day, lessons in by_date.items():
            lessons.sort(key=lambda x: x.number)
    _settle_replacements(snapshot)
    _cut_far_skeleton(snapshot, far, seen_dates, limits, around)

    snapshot.dates = sorted(seen_dates)
    snapshot.unnamed = {col: n for col, n in unnamed.items() if n}
    _validate(snapshot, date_order, limits, date_where)
    return snapshot


def _check_spill(rows: list[list[str]], groups: list[GroupRef], skip: set[int], where) -> None:
    """Отказ, если в пустых колонках блоков (+1, +2) появился текст.

    Так выглядит «вставить ячейки» не на ширину блока: предмет уезжает в +1
    или +2, а аудитория соседа — в колонку предмета. Раньше это проходило при
    ok: на две ячейки — «пар нет» у всех групп, на одну — номера аудиторий
    вместо предметов.
    """
    columns = sorted({g.column for g in groups})
    found: list[tuple[int, int]] = []
    for i, row in enumerate(rows):
        if i in skip:
            continue
        for col in columns:
            for extra in (1, 2):
                if _cell(row, col + extra).strip():
                    found.append((i, col + extra))
    if len(found) >= SPILL_REJECT:
        i, col = found[0]
        raise SourceFormatChanged(
            f"в {len(found)} ячейках пустых колонок блоков есть текст, первая — "
            f"{where(i)}, колонка {a1_column(col)}: похоже на вставку ячеек не на ширину блока"
        )


def _cut_far_skeleton(
    snapshot: Snapshot, far: dict[date, str], seen: list[date], limits: Limits, around
) -> None:
    """Даты за горизонтом: с парами — опечатка, отказ; пустые — каркас, прочь."""
    for day, place in sorted(far.items()):
        if any(by_date.get(day) for by_date in snapshot.schedule.values()):
            raise SourceFormatChanged(
                f"дата {day} в {place} дальше {limits.max_days_ahead} дней от {around}, "
                "а в ней уже пары"
            )
    if far:
        log.info("пустые даты за горизонтом (%s…%s) — каркас, отрезаю", min(far), max(far))
        seen[:] = [d for d in seen if d not in far]


def _topic(subject: str) -> str:
    """Первые два слова названия: «Иностранный язык .Английский ячзык» и
    «Иностранный язык, Английский» — один предмет."""
    return " ".join(re.findall(r"[а-яёa-z]+", subject.casefold())[:2])


def _settle_replacements(snapshot: Snapshot) -> None:
    """Одно ФИО у замены — чьё: заменённой пары или новой?

    Два ФИО колледж пишет как «прежний, новый», а одно бывает и тем, и другим.
    Судим по остальным ячейкам листа: если преподаватель ведёт заменённый
    предмет и не ведёт новый, это прежний — у новой пары его нет. Иначе в его
    расписании стояла пара, которой у него нет: Звонцову, который ведёт
    «Коммуникативный тренинг», — «Кураторский час» вместо него.
    """
    teaches: dict[str, set[str]] = {}
    pending: list[tuple[list[Lesson], int, str]] = []
    for by_date in snapshot.schedule.values():
        for lessons in by_date.values():
            for i, lesson in enumerate(lessons):
                old = replaced_of(lesson)
                if old is None:
                    for name in lesson.teachers:
                        teaches.setdefault(name, set()).add(_topic(lesson.subject))
                elif len(lesson.teachers) == 1:
                    pending.append((lessons, i, old))
    for lessons, i, old in pending:
        lesson = lessons[i]
        known = teaches.get(lesson.teachers[0], set())
        if _topic(old) in known and _topic(lesson.subject) not in known:
            lessons[i] = dataclasses.replace(lesson, teachers=())


def _adopt_names(
    rows: list[list[str]], groups: list[GroupRef], adopt: dict[int, list[str]], min_groups: int
) -> tuple[list[GroupRef], dict[int, int]]:
    """Имена безымянным блокам главного заголовка — из повторного.

    Возвращает группы (с подобранными) и оставшиеся безымянные блоки со
    счётчиком пар, который набирает обход.
    """
    taken = {g.column for g in groups}
    known = {g.id for g in groups}
    blocks = header_blocks(rows, min_groups) - taken
    groups = list(groups)
    for col in sorted(adopt):
        if col not in blocks:
            # Главный заголовок про этот блок не знает вовсе — не с чего
            # подбирать: его ставят и законно, под будущую группу.
            _warn_once(
                ("gap", col, tuple(adopt[col])),
                "повторный заголовок: в колонке %s стоит %s, а в главном заголовке этой "
                "колонки нет и такого имени нет нигде — считаю блок безымянным",
                a1_column(col), adopt[col], logger=log,
            )
            continue
        for name in adopt[col]:
            gid = group_id(name)
            if gid in known:
                continue
            _warn_once(
                ("adopt", col, name),
                "в главном заголовке у колонки %s нет имени, а повторный называет её %r — "
                "беру имя оттуда", a1_column(col), name, logger=log,
            )
            known.add(gid)
            groups.append(GroupRef(name=name, id=gid, column=col))
        blocks.discard(col)
    groups.sort(key=lambda g: g.column)
    return groups, dict.fromkeys(blocks, 0)


def _teacher_row(rows: list[list[str]], i: int, skip: set[int], where) -> list[str]:
    """Строка преподавателей пары в строке `i`.

    Это строка под парой со временем звонка в колонке номеров. Раньше бралась
    просто следующая: вставленная между ними строка с припиской («перенос с
    23.09» у одной группы) забирала её роль, и у всей строки пары пропадали
    преподаватели. Строки без номера и времени
    между ними пропускаются. Времени нет вовсе — берём следующую, как раньше.
    """
    between: list[int] = []
    j = i + 1
    while j < len(rows) and j not in skip:
        marker = _cell(rows[j], 1).strip()
        if _LESSON_NO_RE.match(marker):
            break
        if _TIME_RE.match(marker):
            if between:
                _warn_once(
                    ("между", where(between[0])),
                    "между строкой пары и строкой времени в %s лишняя строка — пропускаю",
                    where(between[0]), logger=log,
                )
            return rows[j]
        between.append(j)
        j += 1
    return rows[between[0]] if between else []


def _validate(
    snapshot: Snapshot,
    seen_order: list[date],
    limits: Limits,
    where: list[str] | None = None,
) -> None:
    """Проверяет, что разобранное похоже на расписание, а не на обломки."""
    if len(snapshot.dates) < limits.min_dates:
        raise SheetTooSmall(
            f"нашёл всего {len(snapshot.dates)} дней, ожидал не меньше {limits.min_dates}",
            starts=min(snapshot.dates, default=None),
        )
    for k, (a, b) in enumerate(zip(seen_order, seen_order[1:])):
        if b <= a:
            # Первое нарушение, со строкой листа. Раньше в тревогу уходил
            # весь столбец дат списком из тридцати datetime.date(…), и
            # искать в нём сломанное место приходилось глазами (24 сентября
            # 2026: колледж собирал новую неделю из скопированных блоков, и
            # под 05.10 осталась дата 26.09 от копии).
            place = f" в {where[k + 1]}" if where and k + 1 < len(where) else ""
            raise SourceFormatChanged(
                f"даты в листе идут не по возрастанию: {b:%d.%m.%Y}{place} "
                f"стоит после {a:%d.%m.%Y}"
            )
    gaps = [
        (a, b) for a, b in zip(seen_order, seen_order[1:]) if (b - a).days > limits.max_gap_days
    ]
    if gaps:
        # Опечатка в месяце у одной даты: остальные проверки её пропустят,
        # а `covering` растянет лист на месяц.
        raise SourceFormatChanged(
            f"между {gaps[0][0]} и {gaps[0][1]} больше {limits.max_gap_days} дней"
        )
    total = snapshot.total_lessons()
    if total < limits.min_lessons:
        raise SheetTooSmall(
            f"нашёл всего {total} пар, ожидал не меньше {limits.min_lessons}",
            starts=min(snapshot.dates, default=None),
        )
    # Сдвиг по содержимому здесь не проверяется: его зовут явно служба (с
    # историей прежнего снимка) и канарейка. Раньше он шёл дважды — здесь без
    # истории и в службе с ней, — и не было видно, какая из двух проверок
    # отвергла лист.
