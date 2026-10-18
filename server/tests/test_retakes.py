"""Лист пересдач: разбор, выбор листа в книге, ответ API."""

import csv
import datetime as dt
import io

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from whensclass.api.routes import router
from whensclass.domain.models import SourceFormatChanged
from whensclass.parser.retakes import parse_retakes
from whensclass.service import retakes as retakes_service
from whensclass.sources.gsheets import SheetInfo


def _sheet(rows):
    out = io.StringIO()
    csv.writer(out).writerows(rows)
    return out.getvalue()


# Раскладка — как у настоящего листа: таблица сдвинута вправо, заголовок
# выше, под таблицей — «обращаться к», имена чьих долгов — в колонке дат.
SHEET = _sheet([
    ["", "", "", "", "", "", "", ""],
    ["", "", "", "", "", "График пересдач, октябрь 2026 г.", "", ""],
    ["", "", "", "№ п/п", "ФИО преподавателя", "Дата пересдачи", "Время пересдачи", "Кабинет"],
    ["", "", "", "1", "Иванов И.И.", "12-23.10", "16.00-17.00", "265"],
    ["", "", "", "2", "Петрова  П.П.", "14.10; 16.10", "в течение дня", "по расписанию занятий"],
    ["", "", "", "3", "Сидоров С.С.", "12-23.10", "онлайн (по готовности)", ""],
    ["", "", "", "", "", "", "", ""],
    ["", "", "Обращаться к преподавателям по академической задолженности по расписанию:", "", "", "", "", ""],
    ["", "", "", "", "", "", "", ""],
    ["", "", "", "1", "Иванов И.И.", "Орлова О.О.", "", ""],
    ["", "", "", "", "", "Белов Б.Б.", "", ""],
    ["", "", "", "", "", "", "", ""],
    ["", "", "", "2", "каб.263 (онлайн-тестирование)", "Козлов К.К., Ершов,Львов", "", ""],
])


def test_rows_are_given_as_written():
    parsed = parse_retakes(SHEET)
    assert parsed.title == "График пересдач, октябрь 2026 г."
    assert [(r.teacher, r.dates, r.time, r.room) for r in parsed.rows] == [
        ("Иванов И.И.", "12-23.10", "16.00-17.00", "265"),
        ("Петрова П.П.", "14.10; 16.10", "в течение дня", "по расписанию занятий"),
        ("Сидоров С.С.", "12-23.10", "онлайн (по готовности)", ""),
    ]


def test_dates_become_slots_paired_with_times():
    from whensclass.parser.retakes import parse_slots

    def days(dates, time):
        return [(s.start.isoformat(), s.end.isoformat(), s.time) for s in parse_slots(dates, time, dt.date(2026, 10, 16))]

    assert days("12-23.10", "16.00-17.00") == [("2026-10-12", "2026-10-23", "16:00–17:00")]
    assert days("14.10; 16.10", "10.00-14.30; 9.30-17.30") == [
        ("2026-10-14", "2026-10-14", "10:00–14:30"), ("2026-10-16", "2026-10-16", "9:30–17:30")]
    assert days("17.10.; 24.10.", "в течение дня") == [
        ("2026-10-17", "2026-10-17", "в течение дня"), ("2026-10-24", "2026-10-24", "в течение дня")]
    assert days("16.10.", "9.00-10.30; 14.20-17.10") == [("2026-10-16", "2026-10-16", "9:00–10:30, 14:20–17:10")]
    # Хоть одна дата не разобралась — показывать текст как есть.
    assert days("на неделе; 16.10", "16.00-17.00") == []
    assert days("31.02", "16.00-17.00") == []
    # Дата с годом и одно время без конца.
    assert days("22.10.2026", "17.40") == [("2026-10-22", "2026-10-22", "17:40")]
    assert days("12-23.10.26", "16.00-17.00") == [("2026-10-12", "2026-10-23", "16:00–17:00")]
    assert days("22.13.2026", "17.40") == []


def test_hundreds_of_dates_and_times_stay_text():
    """Сотни дат и времён через «;» в двух ячейках не размножаются в ответ
    размером с их произведение: ячейка остаётся текстом."""
    from whensclass.parser.retakes import parse_slots

    dates = "; ".join(["14.10"] * 500)
    times = "; ".join(["10.00-11.00"] * 300)
    assert parse_slots(dates, times, dt.date(2026, 10, 16)) == []
    assert len(parse_slots("14.10; 16.10", "10.00-14.30; 9.30-17.30", dt.date(2026, 10, 16))) == 2


def test_year_is_the_nearest_to_today():
    from whensclass.parser.retakes import parse_slots

    def days(dates, today):
        return [(s.start.isoformat(), s.end.isoformat()) for s in parse_slots(dates, "", today)]

    # В декабре январские даты — следующего года, в январе декабрьские — прошлого.
    assert days("12-23.01; 28.12", dt.date(2026, 12, 25)) == [
        ("2027-01-12", "2027-01-23"), ("2026-12-28", "2026-12-28")]
    assert days("22.12; 24.12", dt.date(2027, 1, 11)) == [
        ("2026-12-22", "2026-12-22"), ("2026-12-24", "2026-12-24")]


def test_year_follows_the_sheet_title_not_the_reading_day():
    """Октябрьский лист, пролежавший до апреля, не «переезжает» в следующий октябрь."""
    parsed = parse_retakes(SHEET, dt.date(2027, 4, 28))
    assert parsed.rows[0].slots[0].start == dt.date(2026, 10, 12)
    january = SHEET.replace("октябрь 2026", "январь 2027").replace("12-23.10", "12-23.01")
    assert parse_retakes(january, dt.date(2026, 12, 20)).rows[0].slots[0].start == dt.date(2027, 1, 12)


def test_redirect_word_inside_a_row_does_not_end_the_table():
    rows = list(csv.reader(io.StringIO(SHEET)))
    rows.insert(4, ["", "", "", "2", "Носов Н.Н.", "обращаться к зав. отделением", "", ""])
    parsed = parse_retakes(_sheet(rows))
    assert [r.teacher for r in parsed.rows] == ["Иванов И.И.", "Носов Н.Н.", "Петрова П.П.", "Сидоров С.С."]
    assert [r.to for r in parsed.redirects] == ["Иванов И.И.", "каб.263 (онлайн-тестирование)"]


def test_cleared_table_means_no_retakes():
    rows = list(csv.reader(io.StringIO(SHEET)))[:3]
    parsed = parse_retakes(_sheet(rows))
    assert parsed.rows == [] and parsed.redirects == []


def test_teacher_found_by_initials_with_current_subjects():
    import datetime as dt

    from whensclass.domain.models import Lesson
    from whensclass.domain.teachers import TeacherIndex, TeacherLesson

    def lesson(subject):
        return TeacherLesson(lesson=Lesson(number=1, subject=subject), group_name="ИСП-924/1")

    day = dt.date(2026, 10, 12)
    index = TeacherIndex(
        names={"ivanov-ivan-ivanovich": "Иванов Иван Иванович", "petrova-p": "Петрова Полина Павловна",
               "petrov-p": "Петрова Пелагея Петровна"},
        schedule={"ivanov-ivan-ivanovich": {day: [lesson("Физика."), lesson(".Физика"), lesson("Химия")]},
                  "petrova-p": {day: [lesson("Химия")]}, "petrov-p": {day: [lesson("Химия")]}},
    )
    assert retakes_service._who(index, "Иванов И.И.") == ("ivanov-ivan-ivanovich", ["Физика", "Химия"])
    # Двое с теми же фамилией и инициалами — не угадываем.
    assert retakes_service._who(index, "Петрова П.П.") == (None, [])
    # Во второй таблице — те же id: клиенты пишут имена полностью.
    redirect = retakes_service.Redirect(to="Иванов И.И.", teachers=["Петрова П.П.", "Иванов И.И.", "Ершов"])
    assert retakes_service._redirect(redirect, index) == {
        "to": "Иванов И.И.", "teachers": ["Петрова П.П.", "Иванов И.И.", "Ершов"],
        "to_id": "ivanov-ivan-ivanovich", "teacher_ids": [None, "ivanov-ivan-ivanovich", None],
    }
    room = retakes_service.Redirect(to="каб.263 (онлайн-тестирование)", teachers=[])
    assert retakes_service._redirect(room, index)["to_id"] is None


def test_redirects_collect_names_by_line_and_by_comma():
    parsed = parse_retakes(SHEET)
    assert [(r.to, r.teachers) for r in parsed.redirects] == [
        ("Иванов И.И.", ["Орлова О.О.", "Белов Б.Б."]),
        ("каб.263 (онлайн-тестирование)", ["Козлов К.К.", "Ершов", "Львов"]),
    ]


def test_sheet_without_header_is_a_format_change():
    with pytest.raises(SourceFormatChanged):
        parse_retakes(_sheet([["Пересдачи"], ["Иванов", "12.10"]]))


@pytest.fixture
def source(tmp_path, monkeypatch):
    sent = []
    monkeypatch.setattr(retakes_service.alerts, "notify", lambda *a, **k: sent.append(a))
    monkeypatch.setattr(retakes_service.alerts, "forget", lambda *a: None)
    src = retakes_service.RetakesSource(tmp_path)
    src.sent = sent
    return src


def _book(monkeypatch, sheets, text=SHEET):
    monkeypatch.setattr(retakes_service.sheet_index, "list_sheets", lambda **_: sheets)
    monkeypatch.setattr(retakes_service.gsheets, "fetch_sheet_csv", lambda gid, title=None: text)


def test_picks_visible_retakes_sheet_and_survives_restart(source, monkeypatch, tmp_path):
    _book(monkeypatch, [
        SheetInfo("расписание групп 01.-19.09", "1"),
        SheetInfo("График пересдач июнь 2026", "2", hidden=True),
        SheetInfo("График пересдач октябрь 2026", "3"),
    ])
    source.refresh()
    assert source.payload["sheet"] == "График пересдач октябрь 2026"
    assert len(source.payload["rows"]) == 3
    again = retakes_service.RetakesSource(tmp_path)
    assert again.payload == source.payload


def test_unreadable_sheet_keeps_the_previous_list(source, monkeypatch):
    _book(monkeypatch, [SheetInfo("График пересдач октябрь 2026", "3")])
    source.refresh()
    before = source.payload
    _book(monkeypatch, [SheetInfo("График пересдач октябрь 2026", "3")], text="что-то другое")
    source.refresh()
    assert source.payload == before
    assert source.sent


def test_google_hiccup_is_not_an_alert_until_it_lasts(source, monkeypatch):
    import httpx

    _book(monkeypatch, [SheetInfo("График пересдач октябрь 2026", "3")])
    source.refresh()

    def busy(gid, title=None):
        request = httpx.Request("GET", "https://docs.google.com/x")
        raise httpx.HTTPStatusError("429", request=request, response=httpx.Response(429, request=request))

    monkeypatch.setattr(retakes_service.gsheets, "fetch_sheet_csv", busy)
    source.refresh()
    assert source.sent == []
    source._network_since -= retakes_service.NETWORK_GRACE
    source.refresh()
    assert len(source.sent) == 1


def test_sheet_gone_means_no_retakes(source, monkeypatch):
    _book(monkeypatch, [SheetInfo("График пересдач октябрь 2026", "3")])
    source.refresh()
    _book(monkeypatch, [SheetInfo("расписание групп 01.-19.09", "1")])
    source.refresh()
    assert source.payload["rows"] == [] and source.payload["title"] is None


def test_api_serves_payload_or_503(source, monkeypatch):
    app = FastAPI()
    app.include_router(router)
    app.state.retakes = source
    client = TestClient(app)
    assert client.get("/v1/retakes").status_code == 503
    _book(monkeypatch, [SheetInfo("График пересдач октябрь 2026", "3")])
    source.refresh()
    response = client.get("/v1/retakes")
    assert response.status_code == 200
    assert response.json()["rows"][0]["teacher"] == "Иванов И.И."
    assert client.get("/v1/retakes", headers={"If-None-Match": response.headers["ETag"]}).status_code == 304


def test_google_asking_to_slow_down_keeps_the_previous_list(source, monkeypatch):
    import time

    _book(monkeypatch, [SheetInfo("График пересдач октябрь 2026", "3")])
    source.refresh()
    before = source.payload
    monkeypatch.setattr(retakes_service.gsheets, "_last_429", time.monotonic())
    _book(monkeypatch, [SheetInfo("График пересдач октябрь 2026", "3")], text="что-то другое")
    source.refresh()
    assert source.payload == before and source.sent == []


def test_freeze_keeps_retakes_away_from_google(tmp_path, monkeypatch):
    """Рычаг заморозки — в таблицу не ходит никто, пересдачи тоже."""
    from whensclass.config import settings
    from whensclass.service.retakes import RetakesSource
    from whensclass.sources import sheet_index

    monkeypatch.setattr(settings, "freeze", True)

    def touched(*a, **k):
        raise AssertionError("пошёл в Google")

    monkeypatch.setattr(sheet_index, "list_sheets", touched)
    RetakesSource(tmp_path).refresh()


def test_academic_year_without_month_puts_spring_into_the_second_year():
    """«2026-2027 уч. год» без месяца: январь и февраль — 2027-й, октябрь — 2026-й."""
    from whensclass.parser.retakes import _anchor, parse_slots

    for title in ("График пересдач 2026-2027 уч. год", "Пересдачи 2026/2027", "Пересдачи 2026–27"):
        anchor = _anchor(title, dt.date(2026, 10, 16))
        assert [s.start for s in parse_slots("25.01; 14.10", "", anchor)] == \
            [dt.date(2027, 1, 25), dt.date(2026, 10, 14)], title
