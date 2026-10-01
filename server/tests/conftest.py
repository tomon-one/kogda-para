import gzip
import pathlib

import pytest

from whensclass.config import settings
from whensclass.parser.csv_schedule import FIXTURE
from whensclass.service import alerts, refresher as refresher_mod
from whensclass.sources import gsheets, sheet_index

FIXTURES = pathlib.Path(__file__).parent / "fixtures"
GOLDEN = pathlib.Path(__file__).parent / "golden"


@pytest.fixture(scope="session")
def fixture_csv() -> str:
    """Урезанный до нескольких групп лист от 02.09.2026 (см. tools/make_fixture.py)."""
    return gzip.decompress((FIXTURES / "2026-09-02.csv.gz").read_bytes()).decode("utf-8")


@pytest.fixture(autouse=True)
def _fresh_header_warnings():
    """Неувязки заголовка пишутся в журнал раз на процесс — тестам нужен чистый."""
    from whensclass.parser import groups

    groups._warned.clear()


@pytest.fixture
def sent(monkeypatch):
    box = []

    class Client:
        def __init__(self, *args, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def post(self, url, json=None):
            box.append(json)

            class R:
                status_code = 200

                def raise_for_status(self):
                    return None
            return R()

    monkeypatch.setattr(alerts.httpx, "Client", Client)
    monkeypatch.setattr(settings, "ntfy_topic", "тема")
    alerts._last_sent.clear()
    return box


@pytest.fixture
def sheet(monkeypatch, fixture_csv):
    """Лист, который служба «скачивает»: текст можно подменить в тесте."""
    box = {"text": fixture_csv}
    monkeypatch.setattr(sheet_index, "resolve_window", lambda *a, **k: [("лист", "656498718")])
    monkeypatch.setattr(gsheets, "fetch_sheet_csv", lambda gid=None, title=None: box["text"])
    monkeypatch.setattr(refresher_mod, "_limits", lambda: FIXTURE)
    return box
