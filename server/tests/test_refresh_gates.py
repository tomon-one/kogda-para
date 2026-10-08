"""Ворота обновления: лист, похожий на поломку, не выходит наружу.

Прочерк вместо имени преподавателя не должен замораживать снимок при status
ok, а прочерк вместо имени группы — выглядеть сетевым сбоем. Сверка с прежним
снимком — сдвиг, пропавшие группы, опустевший день — держит прежний снимок;
рычаг accept-next пропускает законную правку один раз.
"""

import datetime as dt

import pytest

from test_refresh_failures import TODAY, a_teacher
from whensclass.parser import cells
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.parser.export import parse_csv, read_csv
from whensclass.service import gates, refresher as refresher_mod
from whensclass.service.refresher import Refresher
from whensclass.storage.snapshot_store import SnapshotStore


def _csv(rows) -> str:
    import csv
    import io

    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    return buf.getvalue()


# --- Прочерк вместо преподавателя -------------------------------------------


@pytest.mark.parametrize("junk", ["-", "—", "?", ".", "..."])
def test_punctuation_is_not_a_teacher(junk):
    assert cells.split_teachers(junk) == ()
    assert cells.split_teachers(f"Миллер Д. Х., {junk}") == ("Миллер Д. Х.",)


def test_dash_under_empty_subject_is_no_lesson():
    assert cells.parse_lesson(1, "", "", "-") is None


def test_punctuation_teacher_does_not_freeze_snapshot(tmp_path, sheet, sent, fixture_csv):
    """Точка после запятой в ячейке преподавателя: снимок обновляется, индекс цел."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    name = a_teacher(fixture_csv)
    # Через csv.writer, с кавычками: без них запятая режет ячейку, и «.» уезжает
    # в соседнюю колонку, которую разбор не читает.
    rows = read_csv(fixture_csv)
    i, j = next(
        (i, j) for i, row in enumerate(rows) for j, cell in enumerate(row) if name in cell
    )
    rows[i][j] = rows[i][j].replace(name, f"{name}, .")
    sheet["text"] = _csv(rows)
    assert r.refresh(today=TODAY, force=True) is True
    assert r.status == "ok" and "." not in store.teachers.names.values()
    # И дальше обновляется: колледж правит лист — изменения доходят.
    sheet["text"] = fixture_csv
    assert r.refresh(today=TODAY, force=True) is True


# --- Прочерк вместо имени группы --------------------------------------------


def test_dash_instead_of_group_name_is_format_not_network(tmp_path, sheet, sent, fixture_csv):
    """Колонка без имени — пропуск блока с предупреждением, а не ValueError."""
    rows = read_csv(fixture_csv)
    names = next(i for i, r in enumerate(rows) if sum(c.strip() == "Преподаватель" for c in r) >= 3) + 1
    # Колонка имени — та, где выше стоит «Преподаватель», а не первая непустая:
    # первая непустая — «№», и тест прошёл бы впустую.
    col = next(c for c, v in enumerate(rows[names - 1]) if v.strip() == "Преподаватель")
    known = {g.name for g in parse_csv(fixture_csv, "ф", FIXTURE).groups}
    assert rows[names][col].strip() in known, "портим именно имя группы"
    rows[names][col] = "-"
    sheet["text"] = _csv(rows)
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    r.refresh(today=TODAY)
    # Либо лист принят без этой колонки, либо отвергнут как формат — но не «сеть».
    assert not (r.last_error or "").startswith("таблица не прочиталась")
    assert r.status == "ok" or "формат" in (r.last_error or "")


# --- from далеко от сегодня -------------------------------------------------


def test_far_from_is_422_not_500(fixture_csv, monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from whensclass.api import routes
    from whensclass.api.routes import router

    # «Сегодня» — своё: с 09.09.2027 настоящее сделало бы from=2026-09-07
    # далёким, и красный тест остановил бы выкладку.
    monkeypatch.setattr(routes, "_today", lambda: dt.date(2026, 9, 8))

    class Store:
        snapshot = parse_csv(fixture_csv, "ф", FIXTURE)
        generated = dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc)
        teachers = None

    class Ref:
        status, last_error, failing_since, checked_at = "ok", None, None, None

    app = FastAPI()
    app.include_router(router)
    app.state.store, app.state.refresher = Store(), Ref()
    client = TestClient(app, raise_server_exceptions=False)
    group = Store.snapshot.groups[0].id
    assert client.get(f"/v1/schedule/{group}?from=9999-12-31").status_code == 422
    assert client.get(f"/v1/schedule/{group}?from=0001-01-01&days=14").status_code == 422
    assert client.get(f"/v1/schedule/{group}?from=2026-09-07").status_code == 200


# --- Сверка с прежним снимком ----------------------------------------------


def _shift_one_day(text: str, column: int) -> str:
    """«Вставить ячейки, сдвиг вправо» на блок — на строках первого дня листа."""
    import csv
    import io

    from whensclass.parser.dates import date_rows

    rows = read_csv(text)
    starts = sorted(date_rows([r[0] if r else "" for r in rows]).values())
    for i in range(starts[0] - 1, starts[1] - 1):
        rows[i][column:column] = [""] * 4
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows)
    return buf.getvalue()


@pytest.mark.parametrize("gate", ["группы", "сдвиг", "объём"])
def test_refresher_gates_keep_the_previous_snapshot(
    tmp_path, sheet, sent, fixture_csv, monkeypatch, gate
):
    """Ворота — через заход, а не отдельными функциями: отказ, stale, тревога и
    прежний снимок на месте."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    before = store.snapshot
    if gate == "группы":
        # Два блока из пяти (две группы из шести) удалены целиком — треть.
        import csv
        import io

        rows = read_csv(fixture_csv)
        for row in rows:
            del row[6:14]
        buf = io.StringIO()
        csv.writer(buf, lineterminator="\n").writerows(rows)
        sheet["text"] = buf.getvalue()
        reason = "пропало 2 групп"
    elif gate == "сдвиг":
        sheet["text"] = _shift_one_day(fixture_csv, 6)
        reason = "сдвиг"
    else:
        from whensclass.parser.csv_schedule import Limits

        monkeypatch.setattr(refresher_mod, "_limits", lambda: Limits(3, 2, 10**6))
        reason = "ожидал не меньше 1000000"
    assert r.refresh(today=TODAY, force=True) is False
    assert r.status == "stale" and store.snapshot is before
    assert reason in r.last_error and sent, r.last_error


def _drop_two_groups(fixture_csv: str) -> str:
    """Два блока из пяти (две группы из шести) удалены целиком — треть."""
    rows = read_csv(fixture_csv)
    for row in rows:
        del row[6:14]
    return _csv(rows)


def test_accept_next_lever_passes_a_sheet_rejected_against_previous(
    tmp_path, sheet, sent, fixture_csv
):
    """Отказ по сверке с прежним снимком залипает, пока колледж держит правку.
    Убрать snapshot.json не поможет: поднимется snapshot.prev.json. Рычаг
    accept-next пропускает один лист без сверки и снимается; в тревоге сказано
    о нём, в открытом err — нет."""
    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    sheet["text"] = _drop_two_groups(fixture_csv)
    assert r.refresh(today=TODAY, force=True) is False
    assert "accept-next" in sent[-1]["message"] and "accept-next" not in r.last_error
    (tmp_path / "accept-next").touch()
    assert r.refresh(today=TODAY, force=True) is True
    assert r.status == "ok" and len(store.snapshot.groups) == 4
    assert not (tmp_path / "accept-next").exists()
    # Рычаг разовый: следующая пропажа снова отказ.
    sheet["text"] = _drop_two_groups(sheet["text"])
    assert r.refresh(today=TODAY, force=True) is False


def test_group_drop_is_checked_on_the_next_week_sheet_too():
    """Новый лист, впервые увиденный сразу первым, с прежним дат не делит, но
    сверяется: соседняя неделя без трети групп — отказ; после каникул —
    подозрение для тревоги."""
    from whensclass.domain.models import ChangedAgainstPrevious, GroupRef, Snapshot

    groups = [GroupRef(name=f"Г-{i}", id=f"g-{i}", column=4 * i) for i in range(9)]
    old = Snapshot("старый", groups=groups, dates=[dt.date(2026, 9, 12)])
    next_week = Snapshot("новый", groups=groups[:6], dates=[dt.date(2026, 9, 14)])
    with pytest.raises(ChangedAgainstPrevious, match="пропало 3 групп из 9"):
        gates._check_group_drop(old, next_week)
    after_break = Snapshot("новый", groups=groups[:6], dates=[dt.date(2026, 11, 9)])
    assert "после перерыва" in gates._check_group_drop(old, after_break)
    assert gates._check_group_drop(old, Snapshot("новый", groups=groups[:7],
                                                         dates=[dt.date(2026, 9, 14)])) is None


def test_published_day_emptied_for_many_groups_is_rejected():
    """Вырезанный или очищенный день проходит все пороги объёма — отказ, а не
    «убрали N пару» всем подписчикам. Прошедшие дни не в счёт."""
    from whensclass.domain.models import ChangedAgainstPrevious, GroupRef, Lesson, Snapshot

    day, past = dt.date(2026, 9, 29), dt.date(2026, 9, 28)
    groups = [GroupRef(name=f"Г-{i}", id=f"g-{i}", column=4 * i) for i in range(30)]
    pair = [Lesson(number=1, subject="Физика")]

    def sheet(empty_today: int, empty_past: int = 0) -> Snapshot:
        return Snapshot("лист", groups=groups, dates=[past, day], schedule={
            g.id: {past: [] if i < empty_past else pair, day: [] if i < empty_today else pair}
            for i, g in enumerate(groups)
        })

    before = sheet(0)
    gates._check_days_emptied(before, sheet(11), day)
    gates._check_days_emptied(before, sheet(0, empty_past=30), day)
    with pytest.raises(ChangedAgainstPrevious, match="у 12 групп из 30 пропали все пары"):
        gates._check_days_emptied(before, sheet(12), day)
    # День, который прежний снимок не прочитал, держался на парах до него:
    # прочитался пустым — не «вырезан».
    before.unread = {g.id: {day: True} for g in groups[:12]}
    gates._check_days_emptied(before, sheet(12), day)


def test_dropped_next_sheet_over_an_empty_date_frame_is_not_a_cut_day():
    """Текущий лист несёт пустой каркас дат следующего, пары этих дней — из
    следующего. Следующий выпал из окна (недописан или с ошибкой) — его дни
    не «вырезаны», и заход не отвергается."""
    from whensclass.domain.models import ChangedAgainstPrevious, GroupRef, Lesson, Snapshot

    today, later = dt.date(2026, 10, 2), dt.date(2026, 10, 5)
    groups = [GroupRef(name=f"Г-{i}", id=f"g-{i}", column=4 * i) for i in range(30)]
    pair = [Lesson(number=1, subject="Физика")]
    current = Snapshot("текущий", groups=groups, dates=[today, later],
                       schedule={g.id: {today: pair} for g in groups})
    following = Snapshot("следующий", groups=groups, dates=[later],
                         schedule={g.id: {later: pair} for g in groups})
    before = current.merged_with(following)
    gates._check_days_emptied(before, current, today, dropped=True)
    with pytest.raises(ChangedAgainstPrevious):
        gates._check_days_emptied(before, current, today)


def test_forgotten_accept_next_lever_is_not_taken(tmp_path, sheet, sent, fixture_csv):
    """Забытый рычаг (старше двух часов) не пропускает сдвиг через неделю."""
    import os

    store = SnapshotStore(tmp_path)
    r = Refresher(store, tmp_path)
    assert r.refresh(today=TODAY) is True
    lever = tmp_path / "accept-next"
    lever.touch()
    old = lever.stat().st_mtime - refresher_mod.ACCEPT_NEXT_TTL.total_seconds() - 60
    os.utime(lever, (old, old))
    sheet["text"] = _drop_two_groups(fixture_csv)
    assert r.refresh(today=TODAY, force=True) is False
    assert not lever.exists()
