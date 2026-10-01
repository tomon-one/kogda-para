"""Окно из нескольких листов: следующий лист недописан, отвергнут или ещё
не начался, а текущий исправен — правки текущего доходят до людей.
"""

import datetime as dt

from test_refresh_failures import TODAY, a_teacher, cell_replace
from whensclass.config import settings
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.parser.export import read_csv
from whensclass.service import refresher as refresher_mod
from whensclass.service.refresher import Refresher
from whensclass.sources import gsheets, sheet_index
from whensclass.storage.snapshot_store import SnapshotStore


# --- Недописанный следующий лист -------------------------------------------


def _first_day_only(fixture_csv: str, new_date: str) -> str:
    """Следующий лист, который колледж только начал: шапка и один день."""
    import csv
    import io

    from whensclass.parser.dates import date_rows

    rows = read_csv(fixture_csv)
    starts = sorted(date_rows([r[0] if r else "" for r in rows]).values())
    first, second = starts[0] - 1, starts[1] - 1
    kept = rows[:second]
    kept[first][0] = new_date
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(kept)
    return buf.getvalue()


def test_half_built_next_sheet_is_left_out_not_fatal(tmp_path, sent, fixture_csv, monkeypatch):
    """Колледж завёл следующий лист и вписал в него один день: лист
    пропускается до поры, а не валит весь набор со stale у всех."""
    texts = {
        "лист": fixture_csv,
        "следующий": _first_day_only(fixture_csv, "14.09.2026 понедельник"),
    }
    monkeypatch.setattr(
        sheet_index, "resolve_window", lambda *a, **k: [("лист", "1"), ("следующий", "2")]
    )
    monkeypatch.setattr(gsheets, "fetch_sheet_csv", lambda gid=None, title=None: texts[title])
    monkeypatch.setattr(refresher_mod, "_limits", lambda: FIXTURE)

    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    assert r.status == "ok" and not sent
    assert max(store.snapshot.dates) < dt.date(2026, 9, 14)
    rejected = list((tmp_path / "history" / "2").glob("*-rejected.txt"))
    assert rejected and "недописан" in rejected[0].read_text("utf-8")

    # Правка сегодняшнего листа доходит, пока следующий недописан.
    name = a_teacher(fixture_csv)
    texts["лист"] = cell_replace(fixture_csv, name, "Смит А. А.")
    assert r.refresh(today=TODAY) is True
    assert "Смит А. А." in store.teachers.names.values()


def test_next_sheet_with_a_format_error_is_left_out_with_an_alarm(
    tmp_path, sent, fixture_csv, monkeypatch
):
    """Черновик следующей вкладки с ошибкой формата (день недели не тот)
    выпадает из окна, а владельцу — тревога. Если без него окну нечем покрыть
    сегодня — это отказ."""
    texts = {
        "лист": fixture_csv,
        "следующий": cell_replace(fixture_csv, "03.09.2026 четверг", "03.09.2026 пятница"),
    }
    monkeypatch.setattr(
        sheet_index, "resolve_window", lambda *a, **k: [("лист", "1"), ("следующий", "2")]
    )
    monkeypatch.setattr(gsheets, "fetch_sheet_csv", lambda gid=None, title=None: texts[title])
    monkeypatch.setattr(refresher_mod, "_limits", lambda: FIXTURE)

    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    assert r.status == "ok"
    assert len(sent) == 1 and "'следующий'" in sent[0]["message"], sent
    rejected = list((tmp_path / "history" / "2").glob("*-rejected.txt"))
    assert rejected and "пятница" in rejected[0].read_text("utf-8")

    other = SnapshotStore(tmp_path / "позже")
    late = Refresher(other, tmp_path / "позже")
    assert late.refresh(today=dt.date(2026, 9, 14)) is False
    assert "лист 'следующий'" in late.last_error


def _weeks_later(fixture_csv: str, weeks: int) -> str:
    """Тот же лист, но даты на `weeks` недель позже (дни недели те же)."""
    import re

    def move(m):
        day = dt.date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
        day += dt.timedelta(weeks=weeks)
        return f"{day:%d.%m.%Y}"

    return re.sub(r"\b(\d{2})\.(\d{2})\.(\d{4})\b", move, fixture_csv)


def test_unfilled_sheet_after_a_break_is_not_a_failure(tmp_path, sent, fixture_csv, monkeypatch):
    """Каникулы: старый лист кончился, колледж заводит следующий и дописывает
    группу за группой. Недописанный лист, который начнётся позже сегодня, —
    единственный в окне: это прежний снимок при ok, а не stale у всех."""
    from whensclass.parser.csv_schedule import Limits

    texts = {"старый": fixture_csv, "новый": _weeks_later(fixture_csv, 3)}
    window = [[("старый", "1")]]
    monkeypatch.setattr(sheet_index, "resolve_window", lambda *a, **k: window[0])
    monkeypatch.setattr(gsheets, "fetch_sheet_csv", lambda gid=None, title=None: texts[title])
    limits = [FIXTURE]
    monkeypatch.setattr(refresher_mod, "_limits", lambda: limits[0])

    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    before = store.snapshot
    window[0] = [("новый", "2")]
    limits[0] = Limits(FIXTURE.min_groups, FIXTURE.min_dates, 10**6)
    monday = dt.date(2026, 9, 14)
    assert r.refresh(today=monday, force=True) is False
    assert r.status == "ok" and store.snapshot is before and not sent
    assert list((tmp_path / "history" / "2").glob("*-rejected.txt"))

    # Колледж дописал — лист принят.
    limits[0] = FIXTURE
    assert r.refresh(today=monday, force=True) is True
    assert min(store.snapshot.dates) == dt.date(2026, 9, 23)

    # Недописан лист, который уже идёт, — отказ.
    limits[0] = Limits(FIXTURE.min_groups, FIXTURE.min_dates, 10**6)
    assert r.refresh(today=dt.date(2026, 9, 24), force=True) is False
    assert r.status == "stale"


def test_too_small_current_sheet_still_fails_and_names_itself(
    tmp_path, sheet, sent, fixture_csv
):
    """Мал сам сегодняшний лист — это отказ, и err называет лист."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    sheet["text"] = _first_day_only(fixture_csv, "08.09.2026 вторник")
    assert r.refresh(today=TODAY) is False
    assert r.status in ("stale", "empty") and "лист 'лист'" in r.last_error


def test_window_of_sheets_starts_on_monday_like_the_app(tmp_path, sheet, sent, monkeypatch):
    """Набор листов — с понедельника, как окно приложения: иначе в воскресенье
    на стыке листов в снимке останется только будущий лист."""
    asked = []

    def window(start, days, state_dir, deep=False):
        asked.append((start, days))
        return [("лист", "656498718")]

    monkeypatch.setattr(sheet_index, "resolve_window", window)
    r = Refresher(SnapshotStore(tmp_path), tmp_path)
    r.refresh(today=dt.date(2026, 9, 13))  # воскресенье
    assert asked[0] == (dt.date(2026, 9, 7), settings.window_days + 6)


def test_only_the_failing_sheet_is_archived_as_rejected(
    tmp_path, sent, fixture_csv, monkeypatch
):
    """Отвергнутым в архив ложится только упавший лист: исправный текущий не
    получает чужое «нашёл всего». Отвергнутый следующий выпадает из окна,
    отвергнутый текущий — отказ всего захода."""
    broken = fixture_csv.replace("Дисциплина", "Предмет")
    texts = {"лист": fixture_csv, "следующий": broken}
    monkeypatch.setattr(
        sheet_index, "resolve_window", lambda *a, **k: [("лист", "1"), ("следующий", "2")]
    )
    monkeypatch.setattr(gsheets, "fetch_sheet_csv", lambda gid=None, title=None: texts[title])
    monkeypatch.setattr(refresher_mod, "_limits", lambda: FIXTURE)
    r = Refresher(SnapshotStore(tmp_path / "а"), tmp_path / "а")
    assert r.refresh(today=TODAY) is True
    assert not list((tmp_path / "а" / "history" / "1").glob("*-rejected*"))
    assert list((tmp_path / "а" / "history" / "2").glob("*-rejected.txt"))

    texts.update({"лист": broken, "следующий": fixture_csv})
    r = Refresher(SnapshotStore(tmp_path / "б"), tmp_path / "б")
    assert r.refresh(today=TODAY) is False
    assert list((tmp_path / "б" / "history" / "1").glob("*-rejected.txt"))
    assert not list((tmp_path / "б" / "history" / "2").glob("*-rejected*"))


def test_one_changed_sheet_of_two_rebuilds_the_whole_window(
    tmp_path, sent, fixture_csv, monkeypatch
):
    """Окно из двух листов, изменился один —
    снимок пересобирается из обоих."""
    later = fixture_csv
    for old, new in (("02.09.2026", "14.09.2026"), ("03.09.2026", "15.09.2026"),
                     ("04.09.2026", "16.09.2026"), ("05.09.2026", "17.09.2026"),
                     ("07.09.2026", "18.09.2026"), ("08.09.2026", "19.09.2026"),
                     ("09.09.2026", "21.09.2026"), ("10.09.2026", "22.09.2026"),
                     ("11.09.2026", "23.09.2026"), ("12.09.2026", "24.09.2026")):
        later = later.replace(old, new)
    import re as _re

    later = _re.sub(r"(\d\d\.09\.2026)\s+\w+", r"\1", later)
    texts = {"лист": fixture_csv, "следующий": later}
    monkeypatch.setattr(
        sheet_index, "resolve_window", lambda *a, **k: [("лист", "1"), ("следующий", "2")]
    )
    monkeypatch.setattr(gsheets, "fetch_sheet_csv", lambda gid=None, title=None: texts[title])
    monkeypatch.setattr(refresher_mod, "_limits", lambda: FIXTURE)
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    texts["лист"] = cell_replace(fixture_csv, a_teacher(fixture_csv), "Смит А. А.")
    assert r.refresh(today=TODAY) is True
    days = set(store.snapshot.dates)
    assert dt.date(2026, 9, 2) in days and dt.date(2026, 9, 24) in days
