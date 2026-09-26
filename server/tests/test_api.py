"""Поведение API: условные запросы, отсутствующие данные, границы дат."""

import datetime as dt

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from whensclass.api.routes import router
from whensclass.domain.models import SheetPlace
from whensclass.parser.csv_schedule import FIXTURE, parse_csv


class FakeStore:
    def __init__(self, snapshot, known=None):
        from whensclass.domain.teachers import build_index

        self.snapshot = snapshot
        self.teachers = build_index(snapshot) if snapshot is not None else None
        self.generated = dt.datetime(2026, 9, 7, 3, 32, 11, tzinfo=dt.timezone.utc)
        self.known = known or {}

    def known_teacher(self, teacher_id):
        return self.known.get(teacher_id)


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


def test_teacher_without_lessons_this_sheet_is_not_gone(tmp_path, fixture_csv):
    """Преподаватель, у которого в новом листе нет пар, — «пар нет», а не 404 и
    «вас больше нет в таблице» (второй аудит, В18)."""
    import dataclasses

    from whensclass.storage.snapshot_store import SnapshotStore

    store = SnapshotStore(tmp_path)
    before = parse_csv(fixture_csv, "лист", FIXTURE)
    store.put(before, dt.datetime(2026, 9, 7, tzinfo=dt.timezone.utc))
    tid, name = next(iter(store.teachers.names.items()))
    after = parse_csv(fixture_csv, "лист", FIXTURE)
    after.schedule = {
        gid: {day: [dataclasses.replace(x, teachers=tuple(t for t in x.teachers if t != name))
                    for x in lessons] for day, lessons in by_date.items()}
        for gid, by_date in after.schedule.items()
    }
    store.put(after, dt.datetime(2026, 9, 8, tzinfo=dt.timezone.utc))
    assert tid not in store.teachers.names

    app = FastAPI()
    app.include_router(router)
    app.state.store = store
    app.state.refresher = FakeRefresher()
    client = TestClient(app)
    response = client.get(f"/v1/teacher/{tid}?from=2026-09-07&days=3")
    assert response.status_code == 200
    assert response.json()["gn"] == name
    assert all(day["l"] == [] for day in response.json()["days"])
    assert client.get("/v1/teacher/nikogda-ne-bylo?from=2026-09-07").status_code == 404


def test_schedule_is_revalidated_every_time(client):
    """max-age=300 давал телефону пять минут отдавать кэш как свежий (второй
    аудит, В14): хранить можно, отдавать без сверки ETag — нет."""
    response = client.get("/v1/schedule/isp-924-2?from=2026-09-07&days=3")
    assert "no-cache" in response.headers["cache-control"]
    assert "max-age" not in response.headers["cache-control"]


def test_autodocs_are_not_served():
    """/docs и /openapi.json отвечали всем — службе они ни к чему."""
    from whensclass.main import app as real_app

    client = TestClient(real_app)  # без with: служба не стартует, нужны только маршруты
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404


def test_watchdogs_may_use_head(client):
    """nginx пропускает HEAD, а служба на @router.get отвечала на него 405."""
    assert client.head("/v1/meta").status_code == 200
    assert client.head("/v1/schedule/isp-924-2?from=2026-09-07&days=3").status_code == 200


def test_known_teacher_without_lessons_gets_free_days_but_fixed_typo_gets_404(fixture_csv):
    """Знакомый преподаватель без пар в листе (отпуск) — «пар нет», не 404
    (второй аудит, В18). Но если колледж исправил опечатку в его имени и тот
    же человек с парами стоит под другим id, пустые дни 60 дней говорили бы
    «пар нет»: тогда 404, и приложение предложит выбрать заново (третий
    аудит, В19 прогона 1)."""
    snapshot = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    app = FastAPI()
    app.include_router(router)
    store = FakeStore(snapshot)
    real = next(iter(store.teachers.names.values()))
    surname, *rest = real.split()
    typo = " ".join([surname, *(w[0] + "ъ" + w[1:] for w in rest)])
    store.known = {"otpusk-o-o": "Отпусков Олег Олегович", "typo-id": typo}
    app.state.store = store
    app.state.refresher = FakeRefresher()
    client = TestClient(app)

    away = client.get("/v1/teacher/otpusk-o-o?from=2026-09-07&days=3")
    assert away.status_code == 200
    assert all(day["l"] == [] for day in away.json()["days"])
    assert client.get("/v1/teacher/typo-id?from=2026-09-07&days=3").status_code == 404


def test_health_is_503_when_today_is_only_a_skeleton(fixture_csv):
    """Третий аудит, В8 прогона 1: дата сегодня в листе есть (каркас вписан
    заранее), а пар почти ни у кого — это не «здоров», хотя статус ok."""
    snapshot = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    today = dt.date(2026, 9, 8)
    keep = snapshot.groups[0].id
    for gid, by_date in snapshot.schedule.items():
        if gid != keep:
            by_date.pop(today, None)
    app = FastAPI()
    app.include_router(router)
    app.state.store = FakeStore(snapshot)
    app.state.refresher = FakeRefresher()
    response = TestClient(app).get("/healthz")
    assert response.status_code == 503
    assert "не дописан" in response.json()["reason"]


def test_id_waiting_for_rename_confirmation_is_503_not_free_days_or_404(fixture_csv):
    """Третий аудит, В26 прогона 1: переименование, которое книга распознала,
    40 минут ждёт подтверждения — и по старому id шли пустые дни. Телефон
    писал их поверх своих и слал «убрали пару». А 404 через час давал «вас
    больше нет» — вечером подтверждение ждёт ночи (М20). Теперь «временно»."""
    snapshot = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    app = FastAPI()
    app.include_router(router)
    store = FakeStore(snapshot, known={"otpusk-o-o": "Отпусков Олег Олегович"})
    app.state.store = store

    class Renaming(FakeRenames):
        pending_teachers = {"otpusk-o-o": {"to": "otpuskov-o-o", "seen": 1}}
        pending_groups = {"isp-924-2-staroe": {"to": "isp-924-2", "seen": 1}}

    refresher = FakeRefresher()
    refresher.renames = Renaming()
    app.state.refresher = refresher
    client = TestClient(app)
    assert client.get("/v1/teacher/otpusk-o-o?from=2026-09-07&days=3").status_code == 503
    assert client.get("/v1/schedule/isp-924-2-staroe?from=2026-09-07").status_code == 503
    assert client.get("/v1/schedule/nikogda-ne-bylo?from=2026-09-07").status_code == 404


# --- Третий аудит, прогон 2: В11 и В12 ---------------------------------------

def test_cancelled_lesson_carries_x_and_c(client, fixture_csv):
    """В11 прогона 2: признаки отмены и причины в JSON не проверял ни один тест —
    выброси их, и отменённые пары у всех пришли бы как обычные."""
    snapshot = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    gid, day, lesson = next(
        (gid, day, x)
        for gid, by_date in snapshot.schedule.items()
        for day, lessons in by_date.items()
        for x in lessons
        if x.cancelled and x.note
    )
    body = client.get(f"/v1/schedule/{gid}?from={day}&days=1").json()
    [sent] = [x for x in body["days"][0]["l"] if x["n"] == lesson.number and x.get("x")]
    assert sent["x"] == 1 and sent["c"] == lesson.note


def test_routes_cut_the_unpublished_tail_by_today(monkeypatch):
    """В12 прогона 2: убери today=_today() из маршрутов — и починка 24.09
    (недописанная неделя — «ещё не опубликовано») пропадала молча."""
    from whensclass.api import routes
    from whensclass.domain.models import GroupRef, Lesson, Snapshot

    week = [dt.date(2026, 9, 21) + dt.timedelta(days=d) for d in range(6)]
    nxt = [d + dt.timedelta(days=7) for d in week]
    refs = [GroupRef(name=f"Г-{n}", id=f"g-{n}", column=2 + 4 * n) for n in range(4)]
    snap = Snapshot(sheet_title="лист", groups=refs, dates=week + nxt)
    lesson = Lesson(number=1, subject="Физика", teachers=("Иванов И. И.",))
    for n, ref in enumerate(refs):
        snap.schedule[ref.id] = {d: [lesson] for d in week + (nxt if n == 0 else [])}
    monkeypatch.setattr(routes, "_today", lambda: dt.date(2026, 9, 24))
    app = FastAPI()
    app.include_router(router)
    app.state.store = FakeStore(snap)
    app.state.refresher = FakeRefresher()
    client = TestClient(app)
    body = client.get("/v1/schedule/g-2?from=2026-09-21&days=13").json()
    assert body["cov"] == ["2026-09-21", "2026-09-26"]
    teacher = client.get("/v1/teacher/ivanov-i-i?from=2026-09-21&days=13").json()
    assert teacher["cov"] == ["2026-09-21", "2026-09-26"]


def test_teacher_route_follows_the_rename_book(fixture_csv, monkeypatch):
    """В12 прогона 2: обращение /v1/teacher к книге переименований тоже не было
    закреплено: старый id отвечает расписанием нового, в ответе — новый g."""
    snapshot = parse_csv(fixture_csv, "расписание групп 01.-05.09", FIXTURE)
    app = FastAPI()
    app.include_router(router)
    store = FakeStore(snapshot)
    app.state.store = store
    real = next(iter(store.teachers.names))

    class Book(FakeRenames):
        teachers = {"old-teacher": real}

    refresher = FakeRefresher()
    refresher.renames = Book()
    app.state.refresher = refresher
    body = TestClient(app).get("/v1/teacher/old-teacher?from=2026-09-07&days=3")
    assert body.status_code == 200 and body.json()["g"] == real
