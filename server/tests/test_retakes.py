"""Лист пересдач: разбор, выбор листа в книге, ответ API."""

import csv
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
    monkeypatch.setattr(retakes_service.sheet_index, "list_sheets", lambda: sheets)
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
