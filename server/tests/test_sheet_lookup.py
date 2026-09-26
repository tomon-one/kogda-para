"""Поиск листа: чем «колледж не выложил» отличается от «мы не посмотрели».

Снаружи эти два случая выглядят одинаково — расписания на сегодня нет, — но
говорить о них приложение должно по-разному. Раньше поиск в обоих случаях
молча брал ближайший известный лист, служба оставалась в `ok`, и человек читал
«расписание ещё не опубликовано» — утверждение о колледже, которого мы в тот
момент проверить не могли.

Разница решается одним признаком: добрались ли мы до содержимого кандидатов.
"""

import datetime as dt

import pytest

from whensclass.domain.models import SourceFormatChanged
from whensclass.sources import sheet_index as si
from whensclass.sources.gsheets import SheetInfo

DAY = dt.date(2026, 9, 14)


def visible(*titles):
    return [
        SheetInfo(title=title, gid=str(number), hidden=False)
        for number, title in enumerate(titles, 1)
    ]


def setup_lookup(monkeypatch, sheets, behaviour):
    """Подменяет книгу и чтение листов.

    `behaviour` — что делает каждый лист: пара дат (покрытие), класс исключения
    или None, если лист не отдаёт содержимого вовсе.
    """
    monkeypatch.setattr(si, "list_sheets", lambda: sheets)

    def fetch(gid=None, title=None):
        outcome = behaviour[title]
        if outcome is None:
            return ""
        if isinstance(outcome, type) and issubclass(outcome, Exception):
            raise outcome("подстроено тестом")
        return title

    def parse(text, title, limits=None, around=None):
        outcome = behaviour[title]
        if isinstance(outcome, type) and issubclass(outcome, Exception):
            raise outcome("подстроено тестом")
        first, last = outcome
        return Snapshot(dt.date.fromisoformat(first), dt.date.fromisoformat(last))

    monkeypatch.setattr(si.gsheets, "fetch_sheet_csv", fetch)
    monkeypatch.setattr(si, "parse_csv", parse)


def Snapshot(first, last, filled_to=None):
    """Лист с днями first..last; пары — у одной группы до `filled_to` (по
    умолчанию до конца): дальше — пустой каркас дат."""
    from whensclass.domain.models import GroupRef, Lesson
    from whensclass.domain.models import Snapshot as Real

    days = [first + dt.timedelta(days=n) for n in range((last - first).days + 1)]
    group = GroupRef(name="А-1", id="a-1", column=2)
    snap = Real(sheet_title="лист", groups=[group], dates=days)
    snap.schedule = {"a-1": {d: [Lesson(number=1, subject="Х")] for d in days
                             if d <= (filled_to or last)}}
    return snap


def test_all_sheets_read_and_none_covers_the_day(tmp_path, monkeypatch):
    """Прочитали всё, ни один лист не покрывает день — колледж не выложил.

    Это не поломка: между листами есть воскресенье, а новую неделю выкладывают
    когда захотят. Берём ближайший известный и молчим.
    """
    sheets = visible("расписание групп 01.-05.09")
    setup_lookup(monkeypatch, sheets, {"расписание групп 01.-05.09": ("2026-09-02", "2026-09-12")})

    title, _ = si.resolve_for(DAY, tmp_path)

    assert title == "расписание групп 01.-05.09"


def test_unreachable_sheet_breaks_the_lookup(tmp_path, monkeypatch):
    """До листа не добрались — молчать нельзя, даже если есть чем прикрыться."""
    sheets = visible("расписание групп 01.-05.09", "расписание групп 14.-19.09")
    setup_lookup(
        monkeypatch,
        sheets,
        {
            "расписание групп 01.-05.09": ("2026-09-02", "2026-09-12"),
            "расписание групп 14.-19.09": ConnectionError,
        },
    )

    with pytest.raises(LookupError, match="добраться не вышло"):
        si.resolve_for(DAY, tmp_path)


def test_sheet_without_content_counts_as_unreachable(tmp_path, monkeypatch):
    """Пустой ответ — тоже «не посмотрели», а не «посмотрели и не подошло»."""
    sheets = visible("расписание групп 01.-05.09", "расписание групп 14.-19.09")
    setup_lookup(
        monkeypatch,
        sheets,
        {
            "расписание групп 01.-05.09": ("2026-09-02", "2026-09-12"),
            "расписание групп 14.-19.09": None,
        },
    )

    with pytest.raises(LookupError):
        si.resolve_for(DAY, tmp_path)


def test_group_sheet_that_stopped_parsing_is_alarming(tmp_path, monkeypatch):
    """Лист групп, переставший разбираться, — тревога, а не честный отказ.

    Либо колледж переделал формат, либо мы разучились его читать. И то и другое
    значит, что расписания у нас может не быть по нашей вине.
    """
    sheets = visible("расписание групп 01.-05.09", "расписание групп 14.-19.09")
    setup_lookup(
        monkeypatch,
        sheets,
        {
            "расписание групп 01.-05.09": ("2026-09-02", "2026-09-12"),
            "расписание групп 14.-19.09": SourceFormatChanged,
        },
    )

    with pytest.raises(LookupError):
        si.resolve_for(DAY, tmp_path)


def test_calendar_that_is_not_a_schedule_is_not_alarming(tmp_path, monkeypatch):
    """А календарный график отказом не тревожит: он и не должен разбираться.

    В книге сотня листов, и добрая часть кандидатов по имени — не расписание
    групп вовсе. Считать каждый такой отказ поломкой значит выть постоянно.
    """
    sheets = visible("Календарный график 2026-2027г.", "расписание групп 01.-05.09")
    setup_lookup(
        monkeypatch,
        sheets,
        {
            "Календарный график 2026-2027г.": SourceFormatChanged,
            "расписание групп 01.-05.09": ("2026-09-02", "2026-09-12"),
        },
    )

    title, _ = si.resolve_for(DAY, tmp_path)

    assert title == "расписание групп 01.-05.09"


def test_covering_sheet_wins_over_earlier_trouble(tmp_path, monkeypatch):
    """Нашли покрывающий лист — тревожиться не о чем, даже если по пути споткнулись.

    Расписание у человека будет правильное, а значит поломки для него нет.
    """
    sheets = visible("расписание групп 06.-12.09", "расписание групп 14.-19.09")
    setup_lookup(
        monkeypatch,
        sheets,
        {
            "расписание групп 06.-12.09": ConnectionError,
            "расписание групп 14.-19.09": ("2026-09-14", "2026-09-26"),
        },
    )

    title, _ = si.resolve_for(DAY, tmp_path)

    assert title == "расписание групп 14.-19.09"


def test_miss_is_remembered_and_not_rescanned_every_refresh(tmp_path, monkeypatch):
    """За краем покрытия служба каждые 20 минут заново качала и разбирала всех
    кандидатов: теперь «листа нет» помнится два часа, а
    глубокий поиск (ночью и при новом имени в книге) идёт мимо этой памяти."""
    setup_lookup(monkeypatch, visible("расписание групп 01.-05.09"),
                 {"расписание групп 01.-05.09": ("2026-09-01", "2026-09-05")})
    fetched = []
    real = si.gsheets.fetch_sheet_csv
    monkeypatch.setattr(si.gsheets, "fetch_sheet_csv",
                        lambda gid=None, title=None: fetched.append(title) or real(gid, title))

    si.resolve_for(DAY, tmp_path)  # ближайший известный — прошлый лист
    assert len(fetched) == 1
    si.resolve_for(DAY, tmp_path)
    assert len(fetched) == 1, "второй заход в пределах двух часов — без скачивания"
    si.resolve_for(DAY, tmp_path, deep=True)
    assert len(fetched) == 2, "глубокий поиск идёт в сеть всегда"


def test_unreadable_candidate_is_not_remembered_as_a_miss(tmp_path, monkeypatch):
    """«Не добрались» — не то же, что «листа нет»: такое повторяем на каждом заходе."""
    setup_lookup(monkeypatch, visible("расписание групп 14.-19.09"),
                 {"расписание групп 14.-19.09": RuntimeError})
    for _ in range(2):
        with pytest.raises(si.SheetNotFound, match="добраться не вышло"):
            si.resolve_for(DAY, tmp_path)


def test_new_sheet_with_two_dates_is_found(tmp_path, monkeypatch, fixture_csv):
    """Новый лист, где заполнены только понедельник и вторник, — наш лист:
    поиск узнаёт его по заголовку групп, а не по объёму."""
    from whensclass.parser.csv_schedule import FIXTURE, collapse_export, read_csv

    rows = collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)
    third = [i for i, r in enumerate(rows) if r and r[0].strip()[:2].isdigit()][2]
    import csv, io
    out = io.StringIO()
    csv.writer(out).writerows(rows[:third])
    monkeypatch.setattr(si.settings, "min_groups", FIXTURE.min_groups)
    monkeypatch.setattr(si, "list_sheets", lambda: visible("расписание групп 02.-03.09"))
    monkeypatch.setattr(si.gsheets, "fetch_sheet_csv", lambda gid=None, title=None: out.getvalue())
    title, gid = si.resolve_for(dt.date(2026, 9, 3), tmp_path)
    assert title == "расписание групп 02.-03.09" and gid == "1"


def test_empty_date_skeleton_does_not_count_as_covered(tmp_path, monkeypatch):
    """Колледж вписал даты на месяц вперёд без
    пар. Память поиска считала лист покрывающим весь месяц и новую вкладку
    не читала бы вовсе. Покрытие листа для поиска — до последнего дописанного
    дня."""
    sheets = visible("расписание групп 01.-19.09")
    setup_lookup(
        monkeypatch, sheets, {"расписание групп 01.-19.09": ("2026-09-02", "2026-11-02")}
    )
    monkeypatch.setattr(
        si, "parse_csv",
        lambda text, title, limits=None, around=None: Snapshot(
            dt.date(2026, 9, 2), dt.date(2026, 11, 2), filled_to=dt.date(2026, 9, 26)
        ),
    )
    si.resolve_for(dt.date(2026, 9, 21), tmp_path, deep=True)
    index = si.SheetIndex(tmp_path)
    assert index.known["расписание групп 01.-19.09"]["to"] == "2026-09-26"
    assert index.covering(dt.date(2026, 9, 28)) is None


def test_closed_table_during_search_is_closed_not_unreachable(tmp_path, monkeypatch):
    """Закрытая таблица при поиске листа
    выглядела «добраться не вышло»."""
    sheets = visible("расписание групп 28.09-03.10")
    setup_lookup(monkeypatch, sheets, {"расписание групп 28.09-03.10": si.gsheets.SheetClosed})
    with pytest.raises(si.gsheets.SheetClosed):
        si.resolve_for(dt.date(2026, 9, 28), tmp_path, deep=True)


def test_next_sheet_is_searched_deep_when_the_refresh_is_deep(tmp_path, monkeypatch):
    """Поиск следующего листа шёл без deep, и
    свежий промах прятал только что появившийся лист до ночи."""
    index = si.SheetIndex(tmp_path)
    index.remember("лист A", "1", dt.date(2026, 9, 21), dt.date(2026, 9, 26))
    asked = []

    def resolve_for(day, state_dir, deep=False):
        asked.append((day, deep))
        if day == dt.date(2026, 9, 25):
            return "лист A", "1"
        return "лист B", "2"

    monkeypatch.setattr(si, "resolve_for", resolve_for)
    window = si.resolve_window(dt.date(2026, 9, 25), 8, tmp_path, deep=True)
    assert window == [("лист A", "1"), ("лист B", "2")]
    assert asked[-1] == (dt.date(2026, 9, 27), True)


@pytest.mark.parametrize("trouble", [ConnectionError, None])
def test_calendar_that_did_not_come_is_not_alarming_either(tmp_path, monkeypatch, trouble):
    """«тревожимся только за листы групп» держал
    тест лишь в ветке отказа разбора; ветки «не прочитался» и «пустой ответ»
    можно было откатить молча."""
    sheets = visible("Календарный график 2026-2027г.", "расписание групп 01.-05.09")
    setup_lookup(
        monkeypatch, sheets,
        {"Календарный график 2026-2027г.": trouble,
         "расписание групп 01.-05.09": ("2026-08-24", "2026-08-29")},
    )
    # День не покрыт никем: решает только то, до чего не добрались. График —
    # не повод; берётся ближайший лист групп, а не «добраться не вышло».
    title, _ = si.resolve_for(DAY, tmp_path)
    assert title == "расписание групп 01.-05.09"
