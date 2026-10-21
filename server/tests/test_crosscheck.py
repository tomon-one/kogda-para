"""Сверка с листом преподавателей: разбор листа и что считается расхождением."""

import csv
import datetime as dt
import io

from whensclass.domain.models import GroupRef, Lesson, Snapshot
from whensclass.parser.teacher_sheet import parse_teacher_sheet, sheet_week
from whensclass.service import crosscheck as crosscheck_service
from whensclass.service.crosscheck import compare

MON, TUE = dt.date(2026, 10, 19), dt.date(2026, 10, 20)


def _sheet(rows):
    out = io.StringIO()
    csv.writer(out).writerows(rows)
    return out.getvalue()


# Раскладка как у настоящего листа: у дня четыре колонки, пара — две строки.
SHEET = _sheet([
    ["Расписание занятий"],
    ["19.10.2026-24.10.2026"],
    ["Преподаватель", "Пара", "Время", "19.10.2026\n \n ПОНЕДЕЛЬНИК", "", "", "", "20.10.2026\n \n ВТОРНИК", "", "", ""],
    ["", "", "", "Дисциплина", "", "", "Ауд.", "Дисциплина", "", "", "Ауд."],
    ["Иванов Иван Иванович", "1", "", "", "", "", "", "", "", "", ""],
    ["", "", "", "", "", "", "", "", "", "", ""],
    ["", "2", "10.40-12.10", "Физика (Лек)", "", "", "251", "Физика (Пр)", "", "", "онлайн"],
    ["", "", "", "А-1", "", "", "", "А-1 и Б-1", "", "", ""],
    ["", "3", "12.30-14.00", "Химия (Пр)", "", "", "252", "", "", "", ""],
    ["", "", "", "А-1", "", "", "", "", "", "", ""],
])


def test_pairs_are_read_with_day_number_and_group():
    pairs = parse_teacher_sheet(SHEET)
    assert [(p.teacher, p.day, p.number, p.subject, p.room, p.group) for p in pairs] == [
        ("Иванов Иван Иванович", MON, 2, "Физика (Лек)", "251", "А-1"),
        ("Иванов Иван Иванович", TUE, 2, "Физика (Пр)", "онлайн", "А-1 и Б-1"),
        ("Иванов Иван Иванович", MON, 3, "Химия (Пр)", "252", "А-1"),
    ]
    assert sheet_week("расписание преподавателей 19.10-24.10", dt.date(2026, 10, 16)) == (MON, dt.date(2026, 10, 24))
    assert sheet_week("расписание групп 01.-19.09", dt.date(2026, 10, 16)) is None


def _snapshot(a1_mon):
    groups = [GroupRef(name="А-1", id="a-1", column=4), GroupRef(name="Б-1", id="b-1", column=8)]
    return Snapshot("лист", groups=groups, dates=[MON, TUE], schedule={
        "a-1": {MON: a1_mon, TUE: [Lesson(number=2, subject="Физика")]},
        "b-1": {TUE: [Lesson(number=2, subject="Физика")]},
    })


def test_matching_day_is_quiet():
    honest = [Lesson(number=2, subject="Физика"), Lesson(number=3, subject="Химия")]
    assert compare(_snapshot(honest), parse_teacher_sheet(SHEET), MON) == []


def test_neighbours_lessons_are_a_mismatch():
    """Колонку читаем не там: у группы чужие предметы."""
    shifted = [Lesson(number=2, subject="История"), Lesson(number=3, subject="Литература")]
    found = compare(_snapshot(shifted), parse_teacher_sheet(SHEET), MON)
    assert [(m.group, m.day, m.matched, m.total) for m in found] == [("А-1", MON, 0, 2)]
    # Прошедшие дни не сверяем.
    assert compare(_snapshot(shifted), parse_teacher_sheet(SHEET), TUE) == []


def test_one_late_edit_is_not_a_mismatch():
    """Одна несошедшаяся пара из двух — поздняя правка листа групп, не сдвиг."""
    edited = [Lesson(number=2, subject="Кураторский час"), Lesson(number=3, subject="Химия")]
    assert compare(_snapshot(edited), parse_teacher_sheet(SHEET), MON) == []


def test_group_sheet_mark_after_the_subject_is_the_same_lesson():
    """В листе групп «Физика ЗАЧЕТ», в листе преподавателей «Физика (Лек)»."""
    marked = [Lesson(number=2, subject="Физика ЗАЧЕТ"), Lesson(number=3, subject="Химия ЗАЧЕТ")]
    assert compare(_snapshot(marked), parse_teacher_sheet(SHEET), MON) == []


def test_day_missing_from_the_group_sheet_is_not_checked():
    """Следующий лист групп не принят — его дни сверять не с чем."""
    snap = _snapshot([Lesson(number=2, subject="История"), Lesson(number=3, subject="Литература")])
    snap.dates = [TUE]
    assert compare(snap, parse_teacher_sheet(SHEET), MON) == []


def test_week_names_as_the_college_writes_them():
    today = dt.date(2026, 10, 16)
    for title in ("расписание преподавателей 26.-31.10", "Расписание преподавателей 26.10.-31.10",
                  "расписание преподавателей 26.10.2026-31.10.2026", "расписание преподавателей 26.10—31.10"):
        assert sheet_week(title, today) == (dt.date(2026, 10, 26), dt.date(2026, 10, 31)), title
    # Неделя через Новый год кончается в следующем.
    assert sheet_week("расписание преподавателей 28.12-02.01", dt.date(2026, 12, 20)) == (
        dt.date(2026, 12, 28), dt.date(2027, 1, 2))


def _run(tmp_path, monkeypatch, snap, text, sent, quiet=None):
    from whensclass.sources.gsheets import SheetInfo

    monkeypatch.setattr(crosscheck_service.sheet_index, "list_sheets",
                        lambda **_: [SheetInfo("расписание преподавателей 19.10-24.10", "5")])
    monkeypatch.setattr(crosscheck_service.gsheets, "fetch_sheet_csv", text)
    def notify(kind, *a, **k):
        sent.append(kind)
        if quiet is not None:
            quiet.append(k.get("quiet", False))

    monkeypatch.setattr(crosscheck_service.alerts, "notify", notify)
    monkeypatch.setattr(crosscheck_service.alerts, "forget", lambda *a: None)

    class Now(dt.datetime):
        @classmethod
        def now(cls, tz=None):
            return dt.datetime(2026, 10, 19, 6, 37, tzinfo=tz)

    monkeypatch.setattr(crosscheck_service.dt, "datetime", Now)
    crosscheck_service.CrossCheck(lambda: snap, tmp_path).run()


def test_same_mismatch_is_told_once_across_restarts_and_failed_reads(tmp_path, monkeypatch):
    import httpx

    snap = _snapshot([Lesson(number=2, subject="История"), Lesson(number=3, subject="Литература")])
    sent = []
    _run(tmp_path, monkeypatch, snap, lambda gid, title=None: SHEET, sent)
    assert sent == ["crosscheck"]

    def down(gid, title=None):
        raise httpx.ConnectError("нет сети")

    _run(tmp_path, monkeypatch, snap, down, sent)
    _run(tmp_path, monkeypatch, snap, lambda gid, title=None: SHEET, sent)
    assert sent == ["crosscheck"]


def test_unreadable_teacher_sheet_is_a_quiet_alert(tmp_path, monkeypatch):
    sent = []
    snap = _snapshot([Lesson(number=2, subject="Физика"), Lesson(number=3, subject="Химия")])
    _run(tmp_path, monkeypatch, snap, lambda gid, title=None: "не тот лист", sent)
    assert sent == ["crosscheck-sheet"]


def test_google_asking_to_slow_down_skips_the_check(tmp_path, monkeypatch):
    """После 429 большие листы преподавателей не выгружаются: им отдана бы доля расписания."""
    import time

    monkeypatch.setattr(crosscheck_service.gsheets, "_last_429", time.monotonic())
    read = []
    snap = _snapshot([Lesson(number=2, subject="История")])
    _run(tmp_path, monkeypatch, snap, lambda gid, title=None: read.append(gid) or SHEET, [])
    assert read == []


def test_full_disk_does_not_swallow_the_crosscheck_alert(tmp_path, monkeypatch):
    """crosscheck.json не записался — сверка не падает до тревоги: запись
    «о чём сказано» не роняет сам рассказ."""
    import datetime as dt

    def full(*a, **k):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(crosscheck_service, "write_json", full)
    check = crosscheck_service.CrossCheck(lambda: None, tmp_path)
    check.reported = {("ИСП-924/2", dt.date(2026, 9, 3))}
    check._save()


def test_empty_date_frame_of_a_dropped_sheet_is_not_compared():
    """Следующий лист выпал, в текущем на его даты пустой каркас: листы
    преподавателей этой недели с ним не сверяются — это не ошибка чтения."""
    import datetime as dt

    from whensclass.domain.models import GroupRef, Lesson, Snapshot
    from whensclass.parser.teacher_sheet import TeacherPair

    today, later = dt.date(2026, 10, 2), dt.date(2026, 10, 5)
    groups = [GroupRef(name=f"ИСП-9{i:02d}", id=f"isp-9{i:02d}", column=4 * i) for i in range(10)]
    snap = Snapshot("лист", groups=groups, dates=[today, later],
                    schedule={g.id: {today: [Lesson(number=1, subject="Физика")]} for g in groups})
    pairs = [TeacherPair("Ковач Т.", later, n, "Физика", "369", g.name) for g in groups for n in (1, 2, 3)]
    assert compare(snap, pairs, today) == []


def test_one_group_with_two_pairs_comes_without_sound(tmp_path, monkeypatch):
    """Колледж поправил один лист без другого — сказать, но не будить."""
    snap = _snapshot([Lesson(number=2, subject="История"), Lesson(number=3, subject="Литература")])
    sent, quiet = [], []
    _run(tmp_path, monkeypatch, snap, lambda gid, title=None: SHEET, sent, quiet)
    assert sent == ["crosscheck"] and quiet == [True]


def test_shifted_column_wakes_the_owner():
    """Весь день группы или две группы в один день — похоже на сдвиг колонки."""
    m = crosscheck_service.Mismatch
    one = m("А-1", MON, 0, 2, False)
    assert not crosscheck_service.loud([one], [one])
    whole_day = m("А-1", MON, 0, 3, False)
    assert crosscheck_service.loud([whole_day], [whole_day])
    neighbour = m("Б-1", MON, 0, 2, False)
    assert crosscheck_service.loud([one, neighbour], [neighbour])
    # Другая группа в другой день — не соседи.
    other_day = m("Б-1", TUE, 0, 2, False)
    assert not crosscheck_service.loud([one, other_day], [other_day])
