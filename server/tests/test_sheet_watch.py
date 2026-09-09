"""Слежка за книгой: узнать о новом листе в тот же час, а не следующей ночью.

Переход между листами — самое опасное место службы, и раньше он целиком
приходился на утро понедельника: неделю выкладывали в пятницу, а искали мы её
в субботу в 03:30. Теперь книга проверяется между делом — одним запросом,
и только если в ней появилось незнакомое имя, запускается настоящий поиск.
"""

from whensclass.service.refresher import Refresher
from whensclass.sources import sheet_index
from whensclass.sources.gsheets import SheetInfo


class FakeStore:
    snapshot = None


def watcher(tmp_path, monkeypatch, key="ключ"):
    monkeypatch.setattr(sheet_index.settings, "sheets_api_key", key)
    refresher = Refresher(FakeStore(), tmp_path)
    monkeypatch.setattr(
        "whensclass.service.refresher.settings.sheets_api_key", key, raising=False
    )
    calls = []
    monkeypatch.setattr(
        refresher, "refresh", lambda force=False: calls.append(force) or True
    )
    return refresher, calls


def book(monkeypatch, *titles):
    sheets = [
        SheetInfo(title=title, gid=str(number), hidden=False)
        for number, title in enumerate(titles, 1)
    ]
    monkeypatch.setattr(sheet_index, "list_sheets", lambda: sheets)


def test_first_look_only_remembers(tmp_path, monkeypatch):
    """Первый заход после запуска ничего не запускает: сравнивать не с чем."""
    refresher, calls = watcher(tmp_path, monkeypatch)
    book(monkeypatch, "расписание групп 01.-05.09")

    assert refresher.look_for_new_sheet() is False
    assert calls == []


def test_unchanged_book_costs_one_request(tmp_path, monkeypatch):
    """Книга та же — поиск не трогаем. Иначе он шёл бы каждые полчаса."""
    refresher, calls = watcher(tmp_path, monkeypatch)
    book(monkeypatch, "расписание групп 01.-05.09")
    refresher.look_for_new_sheet()

    assert refresher.look_for_new_sheet() is False
    assert calls == []


def test_new_sheet_starts_the_search(tmp_path, monkeypatch):
    """Появилось незнакомое имя — вот ради этого всё и заведено."""
    refresher, calls = watcher(tmp_path, monkeypatch)
    book(monkeypatch, "расписание групп 01.-05.09")
    refresher.look_for_new_sheet()

    book(monkeypatch, "расписание групп 01.-05.09", "расписание групп 14.-19.09")

    assert refresher.look_for_new_sheet() is True
    assert calls == [True], "искать надо принудительно, иначе возьмём лист из памяти"


def test_new_sheet_starts_the_search_once(tmp_path, monkeypatch):
    """Найденный лист перестаёт быть новостью сразу, а не после разбора.

    Разбор может его и отвергнуть — календарный график, например. Если бы
    новизна снималась только удачным разбором, служба искала бы заново каждые
    полчаса до скончания века.
    """
    refresher, calls = watcher(tmp_path, monkeypatch)
    book(monkeypatch, "расписание групп 01.-05.09")
    refresher.look_for_new_sheet()
    book(monkeypatch, "расписание групп 01.-05.09", "Календарный график 2026-2027г.")
    refresher.look_for_new_sheet()

    assert refresher.look_for_new_sheet() is False
    assert calls == [True]


def test_without_key_we_do_not_look(tmp_path, monkeypatch):
    """Без ключа список листов — двадцать мегабайт. Раз в полчаса нельзя."""
    refresher, calls = watcher(tmp_path, monkeypatch, key=None)

    def explode():
        raise AssertionError("без ключа книгу трогать нельзя")

    monkeypatch.setattr(sheet_index, "list_sheets", explode)

    assert refresher.look_for_new_sheet() is False
    assert calls == []


def test_unreachable_book_is_not_a_failure(tmp_path, monkeypatch):
    """Не дозвонились — молчим: ночной поиск никуда не делся."""
    refresher, calls = watcher(tmp_path, monkeypatch)

    def explode():
        raise ConnectionError("подстроено тестом")

    monkeypatch.setattr(sheet_index, "list_sheets", explode)

    assert refresher.look_for_new_sheet() is False
    assert calls == []
