"""Обход листа расписания: строки таблицы -> Snapshot.

Принимает уже разобранные строки, а не текст CSV. Разбор построен на форме,
которую давал gviz: шапка одной строкой «Дисциплина Преподаватель <группа>».
С 14 сентября 2026 лист берётся сырым экспортом, где шапка — три строки
столбиком; `collapse_export` приводит её к прежней форме, и обход остаётся
тем же.
"""

from __future__ import annotations

import csv
import dataclasses
import io
import logging
import re
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, timedelta

from ..domain.ids import group_id
from ..domain.models import (
    GroupRef,
    Lesson,
    SheetPlace,
    SheetTooSmall,
    Snapshot,
    SourceFormatChanged,
    a1_column,
)
from .cells import FIO_RE, parse_lesson, replaced_of
from .groups import (
    _HEADER_PREFIX,
    MIN_GROUPS,
    _warn_once,
    build_column_map,
    find_header_rows,
    header_blocks,
)

log = logging.getLogger(__name__)

# Дату пишут руками, поэтому берём и «2.09.2026 среда», и «02.09.2026, среда»,
# и «07/09/2026», и «понедельник 07.09.2026», и запись без дня недели. День
# недели всё равно только сверяем, доверяя числу.
_DATE_RE = re.compile(r"(\d{1,2})[./](\d{1,2})[./](\d{2,4})")

# Ячейка с двумя числами через точку, дробь или дефис — это дата, которую
# не смогли прочитать, а не «нет даты». Молчать про такое нельзя: раньше
# день молча приклеивался к предыдущему.
_LOOKS_LIKE_DATE = re.compile(r"\d{1,2}\s*[.,/-]\s*\d{1,2}")
_LESSON_NO_RE = re.compile(r"^([1-9])$")
# Строка под парой несёт время звонка в колонке номера: «9-00-10.30».
_TIME_RE = re.compile(r"^\d{1,2}[.:-]\d{2}\s*[-–]\s*\d{1,2}[.:-]\d{2}$")
# Сдвиг блока строк, под которым нет повторного заголовка, не видит ни одна
# сверка колонок: каждая группа молча получает пары соседа. Видно его по
# содержимому, и признак — именно «пары соседа», а не «незнакомые пары».
#
# Две сверки, обе по группам в порядке колонок и по смещению k колонок:
# - с прежней версией листа — на датах, что есть в обеих: пара группы — та,
#   что в прежней версии стояла у соседа через k, а не своя. Так видна правка
#   уже выложенной недели. Отказ — серия в PREV_RUN_REJECT групп подряд;
# - с историей групп — для новой недели, где прежней версии нет: группа за
#   день голосует за k, если её пар, знакомых истории соседа и не знакомых
#   своей, больше, чем наоборот. Отказ — серия в SHIFT_RUN_REJECT голосов.
# Группа, у которой своего больше, серию рвёт; прочие её не трогают.
#
# До 23 сентября 2026 отказ давала доля групп с незнакомыми парами и
# ошибалась в обе стороны (второй аудит, К1, В3, В5). С 23 по 26 сентября
# голосовала строка пары, а не день группы, со смещениями ±1, ±2 и порогом 8:
# хвост в один блок (31 группа), окно в 10 колонок, сдвиг на три блока (85–98
# групп) проходили при ok (третий аудит, К1 прогона 1) — подгруппы с общими
# лекциями и пустые пары не голосуют, и серия набиралась до 7.
#
# Пороги — по 225 парам соседних версий живого листа за 14–24.09.2026 и
# стенду атак на неделю 21–26.09 (хвосты вставкой и удалением 1–4 блоков,
# окна 2–30 колонок, сдвиг на неделе, выложенной сразу со сдвигом): честный
# максимум сверки с прежней версией — 1, атаки — от 2 у окна в 2–3 колонки до
# 150; голосов по истории — 3 честно, 4–7 у хвоста в один блок у правого края
# и 20–140 у прочих хвостов.
PREV_RUN_REJECT = 3
SHIFT_RUN_REJECT = 6
SHIFT_OFFSETS = (-4, -3, -2, -1, 1, 2, 3, 4)
# Сдвиг не на целый блок («вставить 1–3 ячейки»): колонки +1 и +2 блока,
# всегда пустые, получают текст. В 225 версиях — не больше одной ячейки, у
# сдвига хоть на один день — от 70 (третий аудит, В5 прогона 1).
SPILL_REJECT = 5
# Сдвиг ячеек группы по вертикали («удалить ячейки, сдвиг вверх»). На одну
# строку — ФИО преподавателя встаёт на место названия: честно 0 пар в день,
# у сдвига — по паре на каждую задетую группу. На две — пары группы съезжают
# на номер через границы дней: честно так меняется не больше одного дня
# группы за версию, у сдвига — 4–6 (третий аудит, В6 прогона 1).
NAME_SUBJECTS_REJECT = 3
VERTICAL_DAYS_REJECT = 3
# То же на одном дне у многих групп: честно — до 4 групп за день (14.09
# 13:40), у сдвига в последний день листа — 21 и 135.
VERTICAL_GROUPS_REJECT = 10
# Прежняя доля незнакомых — теперь только запись в журнал: законные правки
# задевают её так же, как сдвиг.
SHIFT_WARN_SHARE = 0.25
SHIFT_ROW_WARN_SHARE = 0.35
SHIFT_MIN_GROUPS = 20
SHIFT_MIN_LESSONS_TODAY = 3
SHIFT_MIN_HISTORY = 10

_WEEKDAYS = (
    "понедельник", "вторник", "среда",
    "четверг", "пятница", "суббота", "воскресенье",
)

@dataclass(frozen=True)
class Limits:
    """Границы правдоподобия листа. Не сошлось — значит таблицу переделали."""

    min_groups: int = 100
    min_dates: int = 5
    min_lessons: int = 500
    # Разрыв между соседними днями одного листа. Опечатка в месяце у одной
    # даты (14.10 вместо 14.09) даёт разрыв в месяц и растянула бы лист; а
    # зимние каникулы внутри листа (26.12 → 11.01) — шестнадцать дней, и с
    # прежним порогом в две недели лист отвергался целиком (второй аудит, М6).
    max_gap_days: int = 25
    # Дальше этого от сегодня дата с парами — опечатка («2027» растянула бы
    # лист на год), а пустые даты — каркас на будущее, их просто отрезаем.
    # 24 сентября 2026 колледж вписал каркас на 39 дней вперёд; при прежних
    # 60 днях и отказе продление каркаса до конца ноября роняло обновление у
    # всех (третий аудит, В7 прогона 1).
    max_days_ahead: int = 120


FULL_SHEET = Limits()
# Для фикстур из нескольких групп: структура та же, объём другой.
FIXTURE = Limits(min_groups=3, min_dates=2, min_lessons=5)


def read_csv(text: str) -> list[list[str]]:
    """Разбирает CSV. Кавычки и переводы строк внутри ячеек — забота модуля csv."""
    return list(csv.reader(io.StringIO(text.lstrip("﻿"))))


def _parse_date(cell: str) -> date | None:
    """'02.09.2026 среда' -> date. День недели служит только сверкой."""
    text = (cell or "").replace("\xa0", " ").strip()
    m = _DATE_RE.search(text)
    if not m:
        return None
    day, month, year = m.groups()
    # День недели — любое слово рядом с датой, до или после неё.
    words = [w.strip(" ,.;") for w in (text[: m.start()] + " " + text[m.end():]).split()]
    weekday = next((w for w in words if w.lower() in _WEEKDAYS), None)
    number = int(year)
    if number < 100:
        # «02.09.26» тоже встречается: век дописываем сами.
        number += 2000
    try:
        value = date(number, int(month), int(day))
    except ValueError:
        # Числа есть, а даты не выходит — «32.13.2026». Не наше дело гадать,
        # что имелось в виду: пусть обход решает, что формат поехал.
        return None
    if weekday is None:
        # День недели пишут не всегда. Он всё равно только сверка.
        return value
    expected = _WEEKDAYS[value.weekday()]
    if weekday.lower() != expected:
        # Раньше — «верю числу» в журнал: одна цифра, и понедельник уезжал в
        # воскресенье, а неделя без пропуска воскресенья раздавала всем пары
        # следующего дня при ok (третий аудит, В4 прогона 1). В архиве 14–24.09
        # слово и число не расходились ни разу.
        raise SourceFormatChanged(
            f"дата {value:%d.%m.%Y} помечена как {weekday.lower()}, а это {expected}"
        )
    return value


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
    202», а в Sheets это была 214-я (раздел 7 docs/hardening-2026-09-14.md).
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
                    # номерах пар настоящего дня и без строки (третий аудит,
                    # М8 прогона 1).
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
            # субботу (раздел 7 docs/hardening-2026-09-14.md, задача 10).
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
    вместо предметов (третий аудит, В5 прогона 1).
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
    «Коммуникативный тренинг», — «Кураторский час» вместо него (третий аудит,
    В2 прогона 1).
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
    преподаватели (третий аудит, В11 прогона 1). Строки без номера и времени
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
            f"нашёл всего {len(snapshot.dates)} дней, ожидал не меньше {limits.min_dates}"
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
            f"нашёл всего {total} пар, ожидал не меньше {limits.min_lessons}"
        )
    _check_shift(snapshot)


def _trace(lesson: Lesson) -> tuple[str, tuple[str, ...]]:
    return " ".join(lesson.subject.lower().split()), lesson.teachers


Seed = dict[str, set[tuple[str, tuple[str, ...]]]]


def shift_seed(previous: Snapshot | None, current: Snapshot) -> Seed:
    """История из прошлого снимка — для первой недели листа, где своей ещё нет.

    Каждый новый лист начинается без истории, и первые дни сдвиг был бы
    невидим — а это как раз дни, которые только что набрали руками. Прошлый
    снимок годится в историю, когда это тот же лист (даты пересекаются) или
    следующий за ним через выходные. После каникул — нет: у половины групп
    сменились предметы, и первый день семестра выглядел бы как сдвиг.
    """
    if previous is None or not previous.dates or not current.dates:
        return {}
    same = set(previous.dates) & set(current.dates)
    gap = (min(current.dates) - max(previous.dates)).days
    if not same and not 0 <= gap <= 3:
        return {}
    return {
        gid: {_trace(x) for lessons in by_date.values() for x in lessons}
        for gid, by_date in previous.schedule.items()
    }


def _check_shift(
    snapshot: Snapshot, seed: Seed | None = None, previous: Snapshot | None = None
) -> None:
    """Сдвиг колонок или ячеек по содержимому (см. SHIFT_*, PREV_*, выше).

    История копится по дням, а не собирается заново для каждого дня из всех
    прошлых: так было квадратично по числу дат, а колледж дописывает недели в
    один лист (второй аудит, М36). Порядок колонок — этого снимка, поэтому
    склеенный снимок двух листов сюда не годится: проверять каждый лист
    отдельно (М9). `previous` — прежний принятый снимок: с ним сверяются
    даты, что есть в обоих.
    """
    seed = seed or {}
    first: dict[int, str] = {}
    for group in snapshot.groups:
        first.setdefault(group.column, group.id)
    order = [first[c] for c in sorted(first)]
    names = {g.id: g.name for g in snapshot.groups}
    _check_name_subjects(snapshot)
    if previous is not None:
        _judge_against_previous(snapshot, previous, order, names)
        _check_vertical(snapshot, previous, names)
    history = {gid: set(seed.get(gid, ())) for gid in snapshot.schedule}
    for day in snapshot.dates:
        _judge_neighbours(snapshot, day, history, order, names)
        _warn_strangers(snapshot, day, history)
        for gid, by_date in snapshot.schedule.items():
            history[gid].update(_trace(x) for x in by_date.get(day, []))


def _shift_message(votes: int, start: str, day: date, k: int, how: str) -> str:
    side = "правее" if k > 0 else "левее"
    word = "колонку" if abs(k) == 1 else "колонки"
    return (
        f"{day} похоже на сдвиг колонок: у {votes} групп подряд, начиная с {start}, "
        f"пары соседа на {abs(k)} {word} {side} ({how})"
    )


def _series(
    order: list[str], k: int, score, threshold: int, names: dict[str, str], day: date, how: str
) -> None:
    """Серия групп подряд, у которых `score(i, j)` — «чужого больше своего»."""
    votes, start = 0, None
    for i, gid in enumerate(order):
        j = i + k
        if not 0 <= j < len(order):
            continue
        theirs, own = score(gid, order[j])
        if theirs > own:
            votes += 1
            start = start or gid
            if votes >= threshold:
                raise SourceFormatChanged(
                    _shift_message(votes, names.get(start, start), day, k, how)
                )
        elif own > theirs:
            votes, start = 0, None


def _judge_neighbours(
    snapshot: Snapshot, day: date, history: dict[str, set], order: list[str], names: dict[str, str]
) -> None:
    """Отказ, если подряд идущие группы получили за день пары соседа — по истории."""
    traces = {
        gid: [_trace(x) for x in snapshot.schedule.get(gid, {}).get(day, [])] for gid in order
    }

    def score(gid: str, neighbour: str) -> tuple[int, int]:
        mine, theirs = history[gid], history[neighbour]
        day_traces = traces[gid]
        return (
            sum(1 for x in day_traces if x in theirs and x not in mine),
            sum(1 for x in day_traces if x in mine and x not in theirs),
        )

    for k in SHIFT_OFFSETS:
        _series(order, k, score, SHIFT_RUN_REJECT, names, day, "по истории групп")


def _slots(snapshot: Snapshot, gid: str, day: date) -> dict[int, Lesson]:
    return {x.number: x for x in snapshot.schedule.get(gid, {}).get(day, [])}


def _judge_against_previous(
    snapshot: Snapshot, previous: Snapshot, order: list[str], names: dict[str, str]
) -> None:
    """Отказ, если на общей с прежней версией дате группы подряд получили
    пары, которые там стояли у соседа, — пара в пару, по номерам."""
    for day in sorted(set(snapshot.dates) & set(previous.dates)):
        new = {gid: _slots(snapshot, gid, day) for gid in order}
        old = {gid: _slots(previous, gid, day) for gid in order}

        def score(gid: str, neighbour: str) -> tuple[int, int]:
            mine, theirs = old[gid], old[neighbour]
            lessons = new[gid].items()
            return (
                sum(1 for n, x in lessons if theirs.get(n) == x and mine.get(n) != x),
                sum(1 for n, x in lessons if mine.get(n) == x and theirs.get(n) != x),
            )

        for k in SHIFT_OFFSETS:
            _series(order, k, score, PREV_RUN_REJECT, names, day, "против прежней версии")


def _check_vertical(snapshot: Snapshot, previous: Snapshot, names: dict[str, str]) -> None:
    """Отказ, если пары группы на нескольких днях съехали на номер-два против
    прежней версии: так выглядит «удалить ячейки, сдвиг вверх» в её блоке."""
    common = sorted(set(snapshot.dates) & set(previous.dates))
    same_day: dict[tuple[date, int], list[str]] = {}
    for gid in snapshot.schedule:
        for step in (-2, -1, 1, 2):
            days = []
            for day in common:
                new, old = _slots(snapshot, gid, day), _slots(previous, gid, day)
                if new == old or len(new) < 2:
                    continue
                # Номер у съехавшей пары другой — сверяется всё, кроме него.
                hit = sum(
                    1 for n, x in new.items()
                    if n + step in old and _same_but_number(old[n + step], x)
                )
                if hit >= 2 and hit >= len(new) - 1:
                    days.append(day)
                    same_day.setdefault((day, step), []).append(gid)
            if len(days) >= VERTICAL_DAYS_REJECT:
                raise SourceFormatChanged(
                    f"у группы {names.get(gid, gid)} пары съехали на {abs(step)} "
                    f"{'номер' if abs(step) == 1 else 'номера'} "
                    f"{'вверх' if step > 0 else 'вниз'} на {len(days)} днях с {days[0]} — "
                    "похоже на сдвиг ячеек по вертикали"
                )
    for (day, step), gids in sorted(same_day.items()):
        if len(gids) >= VERTICAL_GROUPS_REJECT:
            raise SourceFormatChanged(
                f"{day} у {len(gids)} групп, начиная с {names.get(gids[0], gids[0])}, пары "
                f"съехали на {abs(step)} {'номер' if abs(step) == 1 else 'номера'} — похоже "
                "на сдвиг ячеек по вертикали"
            )


def _same_but_number(a: Lesson, b: Lesson) -> bool:
    return dataclasses.replace(a, number=b.number) == b


def _check_name_subjects(snapshot: Snapshot) -> None:
    """Отказ, если в дне у нескольких пар вместо названия — ФИО: строки пар и
    преподавателей поменялись местами (сдвиг ячеек на одну строку)."""
    per_day: dict[date, list[str]] = {}
    for gid, by_date in snapshot.schedule.items():
        for day, lessons in by_date.items():
            for lesson in lessons:
                if FIO_RE.fullmatch(lesson.subject):
                    per_day.setdefault(day, []).append(lesson.subject)
    for day, found in sorted(per_day.items()):
        if len(found) >= NAME_SUBJECTS_REJECT:
            raise SourceFormatChanged(
                f"{day} у {len(found)} пар вместо названия ФИО ({found[0]!r}…) — похоже на "
                "сдвиг ячеек на строку"
            )


def _warn_strangers(snapshot: Snapshot, day: date, history: dict[str, set]) -> None:
    """Много незнакомых пар — не отказ, а запись в журнал: так выглядит и
    неделя практики, и классный час, и сдвиг, которого признак соседа не
    увидел (например, в первые дни листа без истории)."""
    compared = 0
    strangers: list[str] = []
    rows: dict[int, list[int]] = {}
    for gid, by_date in snapshot.schedule.items():
        today = by_date.get(day, [])
        known = history[gid]
        if len(known) < SHIFT_MIN_HISTORY:
            continue
        for lesson in today:
            cell = rows.setdefault(lesson.number, [0, 0])
            cell[0] += 1
            if _trace(lesson) not in known:
                cell[1] += 1
        if len(today) < SHIFT_MIN_LESSONS_TODAY:
            continue
        compared += 1
        if not ({_trace(x) for x in today} & known):
            strangers.append(gid)
    for number, (seen, unknown) in sorted(rows.items()):
        if seen >= SHIFT_MIN_GROUPS and unknown / seen > SHIFT_ROW_WARN_SHARE:
            log.warning(
                "%d-я пара %s: у %d групп из %d незнакомый предмет — не сдвиг по соседям, "
                "но присмотреться", number, day, unknown, seen,
            )
    if compared >= SHIFT_MIN_GROUPS and len(strangers) / compared > SHIFT_WARN_SHARE:
        log.warning(
            "день %s: у %d групп из %d ни одного знакомого предмета (%s) — "
            "не сдвиг по соседям, но присмотреться", day, len(strangers), compared,
            ", ".join(strangers[:3]),
        )


def collapse_export(rows: list[list[str]], min_groups: int = MIN_GROUPS) -> list[list[str]]:
    """Сырой экспорт -> форма, на которой построен разбор. См. `collapse_with_rows`."""
    return collapse_with_rows(rows, min_groups)[0]


def collapse_with_rows(
    rows: list[list[str]], min_groups: int = MIN_GROUPS
) -> tuple[list[list[str]], list[int]]:
    """Сырой экспорт -> (форма, на которой построен разбор; номера строк листа).

    В сыром листе шапка стоит столбиком: строка «Дисциплина», под ней
    «Преподаватель», под ней имена групп; выше — пустые строки с рамками.
    gviz склеивал эти строки в одну — «Дисциплина Преподаватель БП-1126»,
    «Ауд.», «№» — и выбрасывал пустые строки посреди листа. Делаем то же
    сами, и только это: остальное разбор читает как читал. Повторные
    заголовки внутри листа не трогаем — они и раньше приходили столбиком.

    Лист, уже собранный (форма gviz, фикстуры), возвращается как есть:
    признак — строка с «Дисциплина Преподаватель …» раньше первой
    столбиковой шапки.

    Второе значение — номер строки листа (как в Sheets, с единицы) для каждой
    строки результата: по нему сообщения разбора ведут в настоящую строку.
    """
    for i, row in enumerate(rows):
        if sum(1 for c in row if c.strip().startswith(_HEADER_PREFIX)) >= min_groups:
            return rows, list(range(1, len(rows) + 1))
        below = rows[i + 1] if i + 1 < len(rows) else []
        if (
            sum(1 for c in row if c.strip() == "Дисциплина") >= min_groups
            and sum(1 for c in below if c.strip() == "Преподаватель") >= min_groups
        ):
            head = rows[: i + 3]
            width = max(len(r) for r in head)
            merged = [
                " ".join(
                    part
                    for part in ((r[col] if col < len(r) else "").strip() for r in head)
                    if part
                )
                for col in range(width)
            ]
            # Пустые строки — только шум: строка под парой отдана
            # преподавателям, и пустая между ними отняла бы их у всех групп.
            kept = [
                (n, r) for n, r in enumerate(rows[i + 3:], start=i + 4) if any(c.strip() for c in r)
            ]
            return [merged] + [r for _, r in kept], [i + 3] + [n for n, _ in kept]
    return rows, list(range(1, len(rows) + 1))


def parse_csv(
    text: str, sheet_title: str, limits: Limits = FULL_SHEET, around: date | None = None
) -> Snapshot:
    rows, numbers = collapse_with_rows(read_csv(text), limits.min_groups)
    return parse_sheet(rows, sheet_title, limits, around=around, sheet_rows=numbers)


def parse_export(
    text: str,
    sheet_title: str,
    gid: str | None,
    limits: Limits = FULL_SHEET,
    around: date | None = None,
) -> Snapshot:
    """То же, что `parse_csv`, но помнит, в каких строках листа стоят дни.

    Номера строк — до схлопывания и выбрасывания пустых: в сыром экспорте
    они совпадают с тем, что видит человек в Sheets (проверено по Sheets API
    14 сентября 2026: 6, 18, 30, 43, 64, …, 139).
    """
    rows = read_csv(text)
    places = {
        day: SheetPlace(gid=gid, row=row)
        for day, row in date_rows([r[0] if r else "" for r in rows]).items()
    }
    collapsed, numbers = collapse_with_rows(rows, limits.min_groups)
    snapshot = parse_sheet(collapsed, sheet_title, limits, around=around, sheet_rows=numbers)
    snapshot.places = places
    if gid:
        snapshot.sheet_columns = {gid: {g.id: g.column for g in snapshot.groups}}
    return snapshot


def date_rows(first_column: list[str]) -> dict[date, int]:
    """Номера строк дней по колонке A сырого листа, как их видит человек в Sheets.

    Из CSV от gviz номера строк было не достать: он схлопывал шапку в одну
    строку и выбрасывал пустые строки посреди листа — на 13.09.2026 сырых
    строк 210, в CSV 199, и сдвиг рос вниз по листу с четырёх до одиннадцати.
    В сыром экспорте строки настоящие. Нужны они ссылке «открыть таблицу»:
    `range=EQ139` подводит к ячейке, а не к верху листа.
    """
    rows: dict[date, int] = {}
    for index, cell in enumerate(first_column):
        try:
            found = _parse_date(cell)
        except SourceFormatChanged:
            # Отказ со строкой листа даст обход (`parse_sheet`).
            continue
        if found is not None and found not in rows:
            rows[found] = index + 1
    return rows
