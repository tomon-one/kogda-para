"""Поведение API: условные запросы, отсутствующие данные, границы дат."""

import datetime as dt

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from whensclass.api.routes import router
from whensclass.domain.models import SheetPlace
from whensclass.parser.csv_schedule import FIXTURE, parse_csv


class FakeStore:
    def __init__(self, snapshot):
        self.snapshot = snapshot
        self.generated = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.timezone.utc)


class FakeRenames:
    """Книга переименований: одна группа и один преподаватель под старыми id."""

    groups = {"isp-924-2-old": "isp-924-2"}
    teachers: dict[str, str] = {}

    def group(self, gid):
        return self.groups.get(gid)

    def teacher(self, tid):
        return self.teachers.get(tid)


class FakeRefresher:
    status = "ok"
    checked_at = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.timezone.utc)
    renames = FakeRenames()
    failing_since = None
    last_error = None


@pytest.fixture(autouse=True)
def today_inside_the_fixture(monkeypatch):
    """Фикстура покрывает 02.09–12.09; «сегодня» для /healthz — вторник внутри."""
    from whensclass.api import routes

    monkeypatch.setattr(routes, "_today", lambda: dt.date(2026, 9, 8))


@pytest.fixture(scope="module")
def client(fixture_csv):
    snapshot = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    # Строки дней служба берёт из Sheets API; здесь они выдуманы, но лист тот.
    snapshot.places = {
        day: SheetPlace(gid="656498718", row=6 + 12 * i) for i, day in enumerate(snapshot.dates)
    }
    app = FastAPI()
    app.include_router(router)
    app.state.store = FakeStore(snapshot)
    app.state.refresher = FakeRefresher()
    return TestClient(app)


def test_renamed_group_answers_under_its_old_id(client):
    """Старый id отвечает расписанием новой группы, а в ответе — её новый id.

    По нему приложение перепишет выбор у себя и перестанет зависеть от памяти
    сервера. Без книги переименований старый id — это 404 и пустой виджет
    у всей группы.
    """
    fresh = client.get("/v1/schedule/isp-924-2?from=2026-09-07&days=3").json()
    old = client.get("/v1/schedule/isp-924-2-old?from=2026-09-07&days=3")
    assert old.status_code == 200
    assert old.json() == fresh
    assert old.json()["g"] == "isp-924-2"


def test_schedule_points_into_the_sheet(client):
    """Ссылка «открыть таблицу» ведёт на лист, а колонка и строка — к ячейке."""
    body = client.get("/v1/schedule/isp-924-2?from=2026-09-07&days=3").json()
    assert body["src_url"].endswith("#gid=656498718")
    assert body["col"] == "S"
    assert all(isinstance(day["row"], int) and day["row"] > 1 for day in body["days"])


def test_meta_points_at_the_sheet_of_today(client):
    body = client.get("/v1/meta").json()
    # Сегодня в фикстуру не попадает, берётся ближайший день — с того же листа.
    assert body["src_url"].endswith("#gid=656498718")


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


def test_health_is_503_when_stale_or_today_is_uncovered(fixture_csv, monkeypatch):
    """/healthz отвечает по существу: stale и непокрытый учебный день — 503.

    Раньше 200 отвечался при любом снимке, и двое суток stale снаружи
    выглядели здоровьем.
    """
    from whensclass.api import routes

    snapshot = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    app = FastAPI()
    app.include_router(router)
    app.state.store = FakeStore(snapshot)
    refresher = FakeRefresher()
    app.state.refresher = refresher
    client = TestClient(app)

    refresher.status = "stale"
    refresher.last_error = "формат таблицы изменился: повторный заголовок"
    refresher.failing_since = dt.datetime(2026, 9, 11, 4, 0, tzinfo=dt.timezone.utc)
    response = client.get("/healthz")
    assert response.status_code == 503
    assert "повторный заголовок" in response.json()["reason"]
    meta = client.get("/v1/meta").json()
    assert meta["since"] == "2026-09-11T04:00:00Z"
    assert meta["err"].startswith("формат таблицы")

    refresher.status = "ok"
    refresher.last_error = refresher.failing_since = None
    assert "since" not in client.get("/v1/meta").json()
    # Сторожки ходят и HEAD-ом: код ответа тот же.
    assert client.head("/healthz").status_code == 200
    # Понедельник за краем листа — 503; воскресенье за краем — норма.
    monkeypatch.setattr(routes, "_today", lambda: dt.date(2026, 9, 14))
    assert client.get("/healthz").status_code == 503
    monkeypatch.setattr(routes, "_today", lambda: dt.date(2026, 9, 13))
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


def test_release_url_is_https(client, monkeypatch):
    """Ссылка на сборку уходит наружу и живёт в чатах: только https.

    Служба не верит заголовкам от nginx (иначе в журнал попадал бы адрес
    телефона), поэтому сама она видит http. Раньше этот адрес и попадал
    в /v1/app, и приложение шло качать обновление по незащищённому каналу.
    """
    from whensclass.api import routes

    monkeypatch.setattr(routes, "latest_release", lambda _: {
        "versionCode": 1,
        "versionName": "b-Тест.0.0.1",
        "file": "kogda-para-1.apk",
    })
    body = client.get("/v1/app").json()
    assert body["url"].startswith("https://"), body["url"]
    assert body["url"].endswith("/download/kogda-para-1.apk")
