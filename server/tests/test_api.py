"""Поведение API: условные запросы, отсутствующие данные, границы дат."""

import datetime as dt

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from whensclass.api.routes import router
from whensclass.parser.csv_schedule import FIXTURE, parse_csv


class FakeStore:
    def __init__(self, snapshot):
        self.snapshot = snapshot
        self.generated = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.timezone.utc)


class FakeRefresher:
    status = "ok"
    checked_at = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.timezone.utc)


@pytest.fixture(scope="module")
def client(fixture_csv):
    snapshot = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    app = FastAPI()
    app.include_router(router)
    app.state.store = FakeStore(snapshot)
    app.state.refresher = FakeRefresher()
    return TestClient(app)


def test_groups(client):
    response = client.get("/v1/groups")
    assert response.status_code == 200
    ids = [g["id"] for g in response.json()["groups"]]
    assert "isp-924-2" in ids


def test_schedule(client):
    response = client.get("/v1/schedule/isp-924-2", params={"from": "2026-09-07"})
    assert response.status_code == 200
    body = response.json()
    assert body["gn"] == "ИСП-924/2"
    assert [d["d"] for d in body["days"]] == ["2026-09-07", "2026-09-08", "2026-09-09"]


def test_unchanged_answer_is_not_sent_twice(client):
    """Второй запрос с тем же ETag должен получить 304 и пустое тело."""
    first = client.get("/v1/schedule/isp-924-2", params={"from": "2026-09-07"})
    tag = first.headers["etag"]
    again = client.get(
        "/v1/schedule/isp-924-2",
        params={"from": "2026-09-07"},
        headers={"If-None-Match": tag},
    )
    assert again.status_code == 304
    assert not again.content


def test_unknown_group_is_404(client):
    assert client.get("/v1/schedule/нет-такой").status_code == 404


def test_days_limit_is_enforced(client):
    assert client.get("/v1/schedule/isp-924-2", params={"days": 99}).status_code == 422


def test_meta_and_health(client):
    meta = client.get("/v1/meta").json()
    assert meta["status"] == "ok"
    assert meta["cov"] == ["2026-09-02", "2026-09-12"]
    assert client.get("/healthz").status_code == 200


def test_empty_store_answers_503(fixture_csv):
    """Пока таблица не разобрана, сервис честно говорит, что данных нет."""
    app = FastAPI()
    app.include_router(router)
    app.state.store = FakeStore(None)
    app.state.refresher = FakeRefresher()
    client = TestClient(app)
    assert client.get("/v1/meta").status_code == 503
    assert client.get("/healthz").status_code == 503


def test_user_agent_is_ascii():
    """Заголовки HTTP кириллицу не переносят — на живом запросе это падало."""
    from whensclass.config import settings

    settings.user_agent.encode("latin-1")
