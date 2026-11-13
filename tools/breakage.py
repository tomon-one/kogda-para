"""Стенд поломок: настоящий лист, испорченный скриптом, — через службу.

Следующую правку колледжа предсказать нельзя, поэтому проверяется не
каждая правка, а обещание службы: на любой поломке листа у человека одно из
трёх — верное расписание, пометка «не прочитан» у группы или прежний лист
(отказ). Всё остальное — молча неверное при `ok`, это баг.

Основа — принятая версия из архива (по умолчанию последняя). Служба сначала
принимает её как есть, потом получает испорченную; то, что она выложила бы,
сверяется с разбором чистой основы по каждой группе и дню из `cov`.

Запуск — питоном службы из корня репозитория:

    server/.venv/bin/python tools/breakage.py
    … tools/breakage.py --only дата_стёрта,пустой_блок
    … tools/breakage.py --base АРХИВ/2026-11-05-1100-….csv.gz --list

Итог на поломку: «верно», «не прочитан» (сколько дней групп помечено),
«отказ», «скрыто» (день выпал из `cov`: человек видит «ещё не
опубликовано») или «БАГ» с первыми расхождениями. Код выхода 1, если есть баг.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import io
import pathlib
import re
import sys
import time
from dataclasses import replace

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import itertools

import replay
from replay import Bench, read, stamp

from whensclass.api.published import published

_DATE = re.compile(r"(\d{1,2})[./](\d{1,2})[./](\d{2,4})")
WEEKDAYS = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]


# --- лист как строки ---------------------------------------------------------

def rows_of(text: str) -> list[list[str]]:
    rows = list(csv.reader(io.StringIO(text)))
    width = max(len(r) for r in rows)
    return [r + [""] * (width - len(r)) for r in rows]


def text_of(rows: list[list[str]]) -> str:
    out = io.StringIO()
    csv.writer(out, lineterminator="\n").writerows(rows)
    return out.getvalue()


def date_of(cell: str) -> dt.date | None:
    m = _DATE.search(cell)
    if not m:
        return None
    d, mo, y = (int(x) for x in m.groups())
    try:
        return dt.date(y + 2000 if y < 100 else y, mo, d)
    except ValueError:
        return None


HEAD_WORDS = ("Дисциплина", "Преподаватель")


class Sheet:
    """Где в строках листа шапки, дни и блоки групп."""

    def __init__(self, rows: list[list[str]]):
        self.rows = rows
        # Шапка — строки «Дисциплина» и «Преподаватель» столбиком (первую
        # колледж то повторяет, то теряет), под ними строка имён групп.
        self.heads: list[int] = []
        self.names: list[int] = []
        i = 0
        while i < len(rows):
            if rows[i][2].strip() in HEAD_WORDS:
                start = i
                while i < len(rows) and rows[i][2].strip() in HEAD_WORDS:
                    i += 1
                self.heads.append(start)
                self.names.append(i)
            i += 1
        self.days = [(i, date_of(r[0])) for i, r in enumerate(rows) if date_of(r[0])]
        head = rows[self.names[0]]
        self.blocks = [c for c in range(2, len(head), 4) if head[c].strip()]

    def day_rows(self, start: int) -> range:
        """Строки дня: от даты до следующей даты или повторной шапки."""
        later = [i for i, _ in self.days if i > start] + [h for h in self.heads if h > start]
        end = min(later, default=len(self.rows))
        return range(start, end)

    def future_day(self, today: dt.date, skip: int = 0) -> int:
        """Строка даты дня после сегодняшнего (skip — сколько таких пропустить)."""
        ahead = [i for i, d in self.days if d > today]
        return ahead[min(skip, len(ahead) - 1)]

    def middle_block(self) -> int:
        return self.blocks[len(self.blocks) // 2]


# --- поломки -----------------------------------------------------------------
# Каждая получает строки (копию) и сегодня, возвращает строки и имена групп,
# которые из листа ушли по-настоящему (их пропажа — не ошибка службы).

def insert_columns(rows, at: int, cells_by_row) -> None:
    for i, r in enumerate(rows):
        r[at:at] = cells_by_row(i)


def m_as_is(rows, today):
    return rows, set()


def m_empty_block(rows, today):
    at = Sheet(rows).middle_block()
    insert_columns(rows, at, lambda i: [""] * 4)
    return rows, set()


def m_new_group(rows, today):
    s = Sheet(rows)
    at = s.middle_block()
    heads = {n: "НОВ-1126" for n in s.names}
    for h, n in zip(s.heads, s.names):
        heads.update({i: rows[i][2].strip() for i in range(h, n)})

    def cells(i):
        if i in heads:
            return [heads[i], "", "", "Ауд." if heads[i] == "Дисциплина" else ""]
        return [""] * 4
    insert_columns(rows, at, cells)
    return rows, set()


def m_group_removed(rows, today):
    s = Sheet(rows)
    at = s.middle_block()
    gone = {rows[s.names[0]][at].strip()}
    for r in rows:
        del r[at:at + 4]
        r.extend([""] * 4)
    return rows, gone


def m_day_shifted(rows, today):
    s = Sheet(rows)
    for i in s.day_rows(s.future_day(today))[:]:
        r = rows[i]
        r[2:] = [""] + r[2:-1]
    return rows, set()


def m_sheet_shifted(rows, today):
    s = Sheet(rows)
    for i in range(s.names[0] + 1, len(rows)):
        r = rows[i]
        r[2:] = [""] + r[2:-1]
    return rows, set()


def m_foreign_date(rows, today):
    s = Sheet(rows)
    at = s.future_day(today, 1)
    other = s.future_day(today, 0)
    rows[at][0] = rows[other][0]
    return rows, set()


def m_date_typo(rows, today):
    s = Sheet(rows)
    rows[s.future_day(today)][0] = "31.11.2026 понедельник"
    return rows, set()


def m_date_other_way(rows, today):
    s = Sheet(rows)
    at = s.future_day(today)
    d = date_of(rows[at][0])
    rows[at][0] = f"{WEEKDAYS[d.weekday()]} {d.day}.{d.month}.{d.year % 100}"
    return rows, set()


def m_date_erased(rows, today):
    s = Sheet(rows)
    rows[s.future_day(today, 1)][0] = ""
    return rows, set()


def m_weeks_swapped(rows, today):
    """Две будущие недели подряд меняются местами (без шапки между ними)."""
    s = Sheet(rows)
    weeks: dict[tuple, list[int]] = {}
    for i, d in s.days:
        if d > today:
            weeks.setdefault(d.isocalendar()[:2], []).append(i)
    keys = sorted(weeks)
    for a, b in itertools.pairwise(keys):
        first, second = weeks[a], weeks[b]
        lo, mid = first[0], second[0]
        hi = s.day_rows(second[-1]).stop
        if any(lo < h < hi for h in s.heads):
            continue
        rows[lo:hi] = rows[mid:hi] + rows[lo:mid]
        return rows, set()
    raise LookupError("нет двух будущих недель подряд без шапки между ними")


def m_teacher_row_lost(rows, today):
    """Строка преподавателей третьей пары удалена. Имён в листе больше нет:
    верно — та же пара без преподавателей (владельцу тревога о незнакомых)."""
    s = Sheet(rows)
    start = s.future_day(today)
    day = date_of(rows[start][0])
    del rows[start + 5]
    rows.append([""] * len(rows[0]))

    def allow(when, want, got):
        if when != day or len(want) != len(got):
            return False
        return all(w == g or (w.number == 3 and g == replace(w, teachers=())) for w, g in zip(want, got))
    return rows, set(), allow


def m_blank_row(rows, today):
    s = Sheet(rows)
    start = s.future_day(today)
    rows.insert(start + 4, [""] * len(rows[0]))
    return rows, set()


def m_five_columns(rows, today):
    """Раскладка по пять колонок на группу: пустая колонка после каждого блока."""
    s = Sheet(rows)
    for c in reversed(s.blocks):
        insert_columns(rows, c + 4, lambda i: [""])
    return rows, set()


def m_roman_numbers(rows, today):
    roman = {"1": "I", "2": "II", "3": "III", "4": "IV", "5": "V", "6": "VI"}
    s = Sheet(rows)
    for i in s.day_rows(s.future_day(today)):
        rows[i][1] = roman.get(rows[i][1].strip(), rows[i][1])
    return rows, set()


def m_week_retyped(rows, today):
    """Последняя неделя набрана заново внизу листа, слово в слово."""
    s = Sheet(rows)
    last = s.days[-1][1].isocalendar()[:2]
    first = min(i for i, d in s.days if d.isocalendar()[:2] == last)
    end = s.day_rows(s.days[-1][0]).stop
    rows.extend([list(r) for r in rows[first:end]])
    return rows, set()


def m_reordered_header(rows, today):
    """Повторная шапка перед будущим днём, в ней две соседние группы
    поменялись колонками — и их пары под ней тоже. Правда та же, что в чистом."""
    s = Sheet(rows)
    at = s.future_day(today)
    a, b = s.blocks[len(s.blocks) // 2], s.blocks[len(s.blocks) // 2 + 1]
    for i in range(at, len(rows)):
        r = rows[i]
        r[a:a + 4], r[b:b + 4] = r[b:b + 4], r[a:a + 4]
    head = [list(r) for r in rows[s.heads[0]:s.names[0] + 1]]
    for r in head:
        r[0] = r[1] = ""
        r[a:a + 4], r[b:b + 4] = r[b:b + 4], r[a:a + 4]
    head[-1][1] = "№"
    rows[at:at] = head
    return rows, set()


def _neighbours(rows, s: Sheet) -> tuple[int, int]:
    """Две соседние колонки середины листа, у обеих имя в шапке."""
    names = rows[s.names[0]]
    for k in range(len(s.blocks) // 2, len(s.blocks) - 1):
        a, b = s.blocks[k], s.blocks[k + 1]
        if names[a].strip() and names[b].strip():
            return a, b
    raise LookupError("нет двух соседних названных колонок")


def _swap(row, a: int, b: int) -> None:
    row[a:a + 4], row[b:b + 4] = row[b:b + 4], row[a:a + 4]


def m_header_previous_name(rows, today):
    """Повторная шапка перед будущим понедельником: две соседние группы
    поменялись колонками вместе с парами, а одна записана прежним видом имени
    («СИС(а)-926/1»). Чужое имя тогда одно — оно похоже на опечатку."""
    s = Sheet(rows)
    at = next(i for i, d in s.days if d > today and d.weekday() == 0)
    a, b = _neighbours(rows, s)
    for i in range(at, len(rows)):
        _swap(rows[i], a, b)
    names = list(rows[s.names[0]])
    names[0], names[1] = "", "№"
    _swap(names, a, b)
    names[b] = re.sub(r"^([А-ЯЁA-Z]+)", r"\1(а)", names[b], count=1)
    top = ["Дисциплина" if c >= 2 and v.strip() else "" for c, v in enumerate(names)]
    rows[at:at] = [top, ["Преподаватель" if v else "" for v in top], names]
    return rows, set()


def m_retyped_with_header(rows, today):
    """Неделя с будущего понедельника набрана заново внизу листа со своей шапкой
    столбиком, и в ней две соседние группы поменялись колонками (пары тоже).
    Правда та же, что в чистом листе."""
    s = Sheet(rows)
    lo = s.future_day(today)
    week = dict(s.days)[lo].isocalendar()[:2]
    days = [i for i, d in s.days if i >= lo and d.isocalendar()[:2] == week]
    copy = [list(r) for r in rows[lo:s.day_rows(days[-1]).stop]]
    head = [list(r) for r in rows[s.heads[-1]:s.names[-1] + 1]]
    a, b = _neighbours(rows, s)
    for r in head + copy:
        _swap(r, a, b)
    rows.extend(head + copy)
    return rows, set()



def m_retyped_then_draft(rows, today):
    """Неделя с будущего понедельника набрана заново внизу, прежняя копия сверху
    устарела (у второй и третьей пары занятия местами), а под новой начата
    следующая неделя: набраны два первых дня. Правда — новая копия, то есть чистый лист."""
    s = Sheet(rows)
    lo = next(i for i, d in s.days if d > today and d.weekday() == 0)
    week = dict(s.days)[lo].isocalendar()[:2]
    days = [i for i, d in s.days if i >= lo and d.isocalendar()[:2] == week]
    hi = s.day_rows(days[-1]).stop
    copy = [list(r) for r in rows[lo:hi]]
    nxt = [i for i, d in s.days if i >= hi and d.isocalendar()[:2] != week]
    later = dict(s.days)[nxt[0]].isocalendar()[:2]
    nxt = [i for i in nxt if dict(s.days)[i].isocalendar()[:2] == later]
    draft = [list(r) for r in rows[nxt[0]:s.day_rows(nxt[1]).stop]]
    for d in days:
        p2 = d + 2
        for k in range(2):
            rows[p2 + k][2:], rows[p2 + 2 + k][2:] = rows[p2 + 2 + k][2:], rows[p2 + k][2:]
    rows.extend(copy + draft)
    return rows, set()


def m_two_days_copied(rows, today):
    """Два будущих дня скопированы блоками под лист, даты старые, и в копии
    у второй и третьей пары поменялись занятия (номера на месте): заготовка
    другой недели. Правда та же, что в чистом листе."""
    s = Sheet(rows)
    a, b = s.future_day(today), s.future_day(today, 1)
    copy = [list(r) for r in rows[a:s.day_rows(b).stop]]
    for p2 in (2, b - a + 2):
        for k in range(2):
            copy[p2 + k][2:], copy[p2 + 2 + k][2:] = copy[p2 + 2 + k][2:], copy[p2 + k][2:]
    rows.extend(copy)
    return rows, set()

def m_latin_letter(rows, today):
    """В имени одной группы кириллическая буква заменена латинской двойницей."""
    s = Sheet(rows)
    twins = str.maketrans("АВЕКМНОРСТХ", "ABEKMHOPCTX")
    for c in s.blocks[len(s.blocks) // 3:]:
        name = rows[s.names[0]][c]
        if name.translate(twins) != name:
            for n in s.names:
                if rows[n][c] == name:
                    rows[n][c] = name.translate(twins)
            return rows, set()
    raise LookupError("нет имени с буквой-двойницей")


def m_pairs_swapped(rows, today):
    """Вторая и третья пара дня поменялись строками (номера едут с ними)."""
    s = Sheet(rows)
    p2 = s.future_day(today) + 2
    rows[p2:p2 + 4] = rows[p2 + 2:p2 + 4] + rows[p2:p2 + 2]
    return rows, set()


def m_spaces(rows, today):
    """Пробелы и неразрывные пробелы вокруг имён групп и дат."""
    s = Sheet(rows)
    for n in s.names:
        rows[n] = [f" {c} " if c.strip() else c for c in rows[n][:2]] + \
                  [f" {c} " if c.strip() else c for c in rows[n][2:]]
    for i, _ in s.days:
        rows[i][0] = f" {rows[i][0]} "
    return rows, set()


BREAKS = {
    "как_есть": (m_as_is, "контроль: основа без правок"),
    "пустой_блок": (m_empty_block, "вставлены 4 пустые колонки посреди листа"),
    "новая_группа": (m_new_group, "посреди листа новая группа без пар"),
    "группа_удалена": (m_group_removed, "блок группы вырезан, правее съехали"),
    "день_съехал": (m_day_shifted, "данные одного дня сдвинуты на колонку вправо"),
    "лист_съехал": (m_sheet_shifted, "все данные под шапкой сдвинуты на колонку"),
    "чужая_дата": (m_foreign_date, "у дня дата другого дня листа"),
    "дата_с_опечаткой": (m_date_typo, "несуществующая дата 31.11"),
    "дата_иначе": (m_date_other_way, "дата записана по-другому: «понедельник 9.11.26»"),
    "дата_стёрта": (m_date_erased, "ячейка даты дня пуста"),
    "недели_местами": (m_weeks_swapped, "две будущие недели поменялись местами"),
    "строка_преподавателей": (m_teacher_row_lost, "у одной пары пропала строка преподавателей"),
    "пустая_строка": (m_blank_row, "пустая строка посреди дня"),
    "пять_колонок": (m_five_columns, "по пять колонок на группу"),
    "римские_номера": (m_roman_numbers, "номера пар римскими цифрами"),
    "неделя_повторена": (m_week_retyped, "последняя неделя ещё раз внизу, слово в слово"),
    "шапка_с_перестановкой": (m_reordered_header, "повторная шапка, две группы поменялись колонками"),
    "шапка_прежнее_имя": (m_header_previous_name, "повторная шапка, две группы местами, одна прежним видом имени"),
    "неделя_заново_со_шапкой": (m_retyped_with_header, "неделя заново внизу со своей шапкой, две группы местами"),
    "заново_и_черновик": (m_retyped_then_draft, "неделя заново внизу, сверху устарела, под ней два дня следующей"),
    "два_дня_скопированы": (m_two_days_copied, "два дня блоками под листом, в копии правят"),
    "латинская_буква": (m_latin_letter, "в имени группы латинская буква-двойница"),
    "пары_местами": (m_pairs_swapped, "вторая и третья пара поменялись строками"),
    "пробелы": (m_spaces, "пробелы вокруг имён групп и дат"),
}


# --- сверка ------------------------------------------------------------------

def compare(clean, broken, today, gone_names, allow=None) -> dict:
    gone = {g.id for g in clean.groups if g.name in gone_names}
    cov_clean = set(published(clean, today))
    cov_broken = set(published(broken, today))
    wrong, missing_groups, flagged = [], [], 0
    for g in clean.groups:
        if g.id in gone:
            continue
        if g.id not in broken.schedule:
            missing_groups.append(g.name)
            continue
        for day in sorted(cov_clean & cov_broken):
            if day in clean.unread.get(g.id, {}):
                continue
            if day in broken.unread.get(g.id, {}):
                flagged += 1
                continue
            want = clean.schedule.get(g.id, {}).get(day, [])
            got = broken.schedule[g.id].get(day, [])
            if want != got and not (allow and allow(day, want, got)):
                wrong.append((g.name, day, want, got))
    known = {g.id for g in clean.groups}
    extra = [g.name for g in broken.groups
             if g.id not in known and any(broken.schedule.get(g.id, {}).values())]
    hidden = sorted(cov_clean - cov_broken)
    added = sorted(d for d in cov_broken - cov_clean
                   if any(broken.schedule.get(g, {}).get(d) for g in broken.schedule))
    return {"wrong": wrong, "missing": missing_groups, "extra": extra,
            "flagged": flagged, "hidden": hidden, "added": added}


def brief(lessons) -> str:
    return "; ".join(f"{x.number} {x.subject[:24]}{' отм' if x.cancelled else ''}" for x in lessons) or "пусто"


def run(name, base_text, today) -> tuple[str, list[str]]:
    func, _ = BREAKS[name]
    try:
        broken_rows, gone, *allow = func(rows_of(base_text), today)
    except LookupError as e:
        return "неприменимо", [str(e)]
    bench = Bench()
    bench.feed(base_text, today)
    clean = bench.store.snapshot
    result = bench.feed(text_of(broken_rows), today)
    if result["decision"] == "отвергнут":
        return "отказ", [(result["error"] or "")[:200]]
    diff = compare(clean, bench.store.snapshot, today, gone, *allow)
    notes = []
    if diff["missing"]:
        notes.append(f"пропали группы: {', '.join(diff['missing'][:5])} (всего {len(diff['missing'])})")
    if diff["extra"]:
        notes.append(f"лишние группы с парами: {', '.join(diff['extra'][:5])}")
    if diff["added"]:
        notes.append(f"лишние дни в cov: {', '.join(map(str, diff['added'][:5]))}")
    for gname, day, want, got in diff["wrong"][:4]:
        notes.append(f"{gname} {day}: надо [{brief(want)}], выложено [{brief(got)}]")
    if diff["wrong"]:
        notes.append(f"всего неверных дней групп: {len(diff['wrong'])}")
    if diff["missing"] or diff["extra"] or diff["added"] or diff["wrong"]:
        return "БАГ", notes
    if diff["hidden"]:
        return "скрыто", [f"дни выпали из cov: {', '.join(map(str, diff['hidden'][:5]))}"]
    if diff["flagged"]:
        return "не прочитан", [f"помечено дней групп: {diff['flagged']}"]
    return "верно", []


def main() -> None:
    import logging

    logging.basicConfig(level=logging.ERROR, format="    журнал: %(levelname)s %(message)s")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", type=pathlib.Path, help="версия из архива (по умолчанию последняя принятая)")
    ap.add_argument("--today", type=dt.date.fromisoformat, help="по умолчанию — дата основы")
    ap.add_argument("--only", help="поломки через запятую")
    ap.add_argument("--list", action="store_true", help="перечислить поломки")
    args = ap.parse_args()
    if args.list:
        for name, (_, what) in BREAKS.items():
            print(f"{name:<24} {what}")
        return
    base = args.base or max(p for p in (replay.ARCHIVE / replay.GID).glob("*.csv.gz")
                            if "rejected" not in p.name)
    today = args.today or stamp(base)[0]
    text = read(base)
    names = args.only.split(",") if args.only else list(BREAKS)
    print(f"основа {base.name}, сегодня {today}")
    bugs = 0
    for name in names:
        started = time.monotonic()
        verdict, notes = run(name, text, today)
        bugs += verdict == "БАГ"
        print(f"{name:<24} {verdict:<12} {time.monotonic() - started:5.1f} с")
        for note in notes:
            print(f"    {note}")
    print(f"поломок {len(names)}, багов {bugs}")
    sys.exit(1 if bugs else 0)


if __name__ == "__main__":
    main()
