"""Картинка дня: PNG на день группы, кэш на диске и условные запросы."""

import datetime as dt
import io
from copy import deepcopy
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from PIL import Image

from whensclass.api import day_image, routes
from whensclass.config import settings
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.parser.export import parse_csv

DAY = dt.date(2026, 9, 10)          # четверг с четырьмя парами в фикстуре
TODAY = dt.date(2026, 9, 8)


class FakeStore:
    def __init__(self, snapshot):
        self.snapshot = snapshot
        self.generated = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.UTC)


class FakeRefresher:
    status = "ok"
    checked_at = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.UTC)
    failing_since = None
    last_error = None

    def editing(self):
        return False


@pytest.fixture(scope="module")
def snapshot(fixture_csv):
    return parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)


@pytest.fixture
def client(snapshot, tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "state_dir", str(tmp_path))
    monkeypatch.setattr(routes, "_today", lambda: TODAY)
    app = FastAPI()
    app.include_router(routes.router)
    app.state.store = FakeStore(snapshot)
    app.state.refresher = FakeRefresher()
    return TestClient(app)


def _open(png: bytes) -> Image.Image:
    image = Image.open(io.BytesIO(png))
    image.load()
    return image


def test_картинка_дня_png_1080x1920(client):
    response = client.get(f"/v1/day-image/isp-924-2/{DAY}.png")
    assert response.status_code == 200
    assert response.headers["content-type"] == "image/png"
    assert response.headers["cache-control"] == "public, no-cache"
    image = _open(response.content)
    assert image.size == (1080, 1920)
    # Не пустая: на чёрном фоне есть карточки и текст.
    assert image.convert("L").getextrema()[1] > 40
    assert len(image.convert("RGB").getcolors(maxcolors=10000)) > 3


def test_пустой_день_тоже_картинка(client):
    response = client.get("/v1/day-image/isp-924-2/2026-09-12.png")
    assert response.status_code == 200
    assert _open(response.content).size == (1080, 1920)


def test_повтор_по_etag_получает_304(client):
    first = client.get(f"/v1/day-image/isp-924-2/{DAY}.png")
    tag = first.headers["etag"]
    again = client.get(f"/v1/day-image/isp-924-2/{DAY}.png",
                       headers={"If-None-Match": tag})
    assert again.status_code == 304
    assert not again.content


def test_незнакомая_группа_404(client):
    assert client.get(f"/v1/day-image/нет-такой/{DAY}.png").status_code == 404


def test_день_вне_снимка_404(client):
    assert client.get("/v1/day-image/isp-924-2/2025-01-01.png").status_code == 404
    assert client.get("/v1/day-image/isp-924-2/не-дата.png").status_code == 404


def test_без_снимка_503(monkeypatch):
    app = FastAPI()
    app.include_router(routes.router)
    app.state.store = FakeStore(None)
    app.state.refresher = FakeRefresher()
    assert TestClient(app).get(f"/v1/day-image/isp-924-2/{DAY}.png").status_code == 503


def test_второй_запрос_не_рисует(client, monkeypatch):
    calls = []
    drawn = day_image.render

    def counted(payload, day):
        calls.append(day)
        return drawn(payload, day)

    monkeypatch.setattr(day_image, "render", counted)
    url = f"/v1/day-image/isp-924-2/{DAY}.png"
    assert client.get(url).status_code == 200
    assert client.get(url).status_code == 200
    assert len(calls) == 1


def test_отмена_не_входит_в_часы_дня():
    assert day_image._day_span([{"n": 1, "x": 1}, {"n": 2}], routes.BELLS) == ("10:40", "12:10")
    assert day_image._day_span([{"n": 1, "x": 1}], routes.BELLS) is None


def test_смена_графика_меняет_картинку_при_том_же_gen(client):
    states = {DAY.isoformat(): "session"}
    client.app.state.calendar = SimpleNamespace(states=lambda _: states)
    url = f"/v1/day-image/isp-924-2/{DAY}.png"
    first = client.get(url)
    states[DAY.isoformat()] = "practice"
    second = client.get(url, headers={"If-None-Match": first.headers["etag"]})
    assert second.status_code == 200
    assert second.headers["etag"] != first.headers["etag"]


def test_непрочитанный_день_с_графиком_не_показывается_пустым(client):
    group = "isp-924-2"
    snapshot = deepcopy(client.app.state.store.snapshot)
    snapshot.schedule[group].pop(DAY)
    snapshot.unread.setdefault(group, {})[DAY] = False
    client.app.state.store.snapshot = snapshot
    client.app.state.calendar = SimpleNamespace(states=lambda _: {DAY.isoformat(): "session"})
    assert client.get(f"/v1/day-image/{group}/{DAY}.png").status_code == 404


def test_кэш_лежит_в_state_dir(client, tmp_path):
    assert client.get(f"/v1/day-image/isp-924-2/{DAY}.png").status_code == 200
    files = list((tmp_path / "day-image").glob("*.png"))
    assert len(files) == 1
    assert files[0].stat().st_size > 1000


def test_рисует_семь_пар_и_длинный_предмет():
    """Лишние пары — строкой «и ещё N», предмет обрезается, отмена видна."""
    lessons = [
        {
            "n": index % 6 + 1,
            "s": "Очень длинное название предмета, которое не влезает в две строки целиком",
            "t": ["Тестов Т. Т."],
            "r": "101",
            "o": 1,
            "x": 1,
        }
        for index in range(7)
    ]
    payload = {
        "g": "test-1",
        "gn": "ТЕСТ-1",
        "gen": "2026-09-08T10:00:00Z",
        "bells": {"1": ["09:00", "10:30"]},
        "days": [{"d": "2026-09-08", "st": "session", "l": lessons}],
    }
    image = _open(day_image.render(payload, dt.date(2026, 9, 8)))
    assert image.size == (1080, 1920)
