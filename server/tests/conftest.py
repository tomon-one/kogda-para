import gzip
import pathlib

import pytest

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
