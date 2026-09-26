"""Сетка звонков и её переопределение файлом.

Времена пар живут в конфиге сервера, а не в сборке приложения, — чтобы правка
не требовала релиза APK у полутора сотен человек. Значит путь «прочитать файл
из WHENSCLASS_BELLS» это аварийный выход, которым воспользуются ровно тогда,
когда что-то уже горит. До 9 сентября он не был проверен ничем.
"""

import json

import pytest

from whensclass.service.bells import BELLS, load_bells


def test_without_the_variable_we_take_our_own(monkeypatch):
    monkeypatch.delenv("WHENSCLASS_BELLS", raising=False)

    assert load_bells() == BELLS


def test_file_replaces_the_grid(monkeypatch, tmp_path):
    """Колледж сдвинул звонки — правка одним файлом, без пересборки."""
    path = tmp_path / "bells.json"
    path.write_text(json.dumps({"1": ["08:30", "10:00"]}), encoding="utf-8")
    monkeypatch.setenv("WHENSCLASS_BELLS", str(path))

    assert load_bells() == {"1": ["08:30", "10:00"]}


def test_numbers_become_strings(monkeypatch, tmp_path):
    """В JSON ключи могут прийти числами, а по ключу ищет приложение строкой."""
    path = tmp_path / "bells.json"
    path.write_text('{"1": ["09:00", "10:30"]}'.replace('"1"', "\"1\""), encoding="utf-8")
    monkeypatch.setenv("WHENSCLASS_BELLS", str(path))

    assert list(load_bells()) == ["1"]


def test_broken_file_does_not_leave_us_without_bells(monkeypatch, tmp_path):
    """Битый JSON — возвращаемся к своей сетке, а не отдаём пустоту.

    Без звонков виджет не подсветит идущую пару и не покажет времени, а
    напоминания не поставятся вовсе: их не от чего отсчитывать.
    """
    path = tmp_path / "bells.json"
    path.write_text("{ это не json", encoding="utf-8")
    monkeypatch.setenv("WHENSCLASS_BELLS", str(path))

    assert load_bells() == BELLS


def test_missing_file_does_not_leave_us_without_bells(monkeypatch, tmp_path):
    monkeypatch.setenv("WHENSCLASS_BELLS", str(tmp_path / "нет-такого.json"))

    assert load_bells() == BELLS


def test_the_grid_we_ship_is_the_college_one():
    """Шесть пар по полтора часа, перемены 10 и 20 минут.

    Проверка не на «а вдруг кто-то опечатается»: по этой сетке считаются и
    подсветка идущей пары, и то, о каких парах напоминать.
    """
    assert list(BELLS) == ["1", "2", "3", "4", "5", "6"]
    assert BELLS["1"] == ["09:00", "10:30"]
    assert BELLS["6"] == ["17:40", "19:10"]
    for times in BELLS.values():
        assert len(times) == 2, "у пары есть начало и конец"


@pytest.mark.parametrize(
    "content",
    ['[["09:00", "10:30"]]', "null", '{"1": "09:00-10:30"}', '{"1": ["10:30", "09:00"]}',
     '{"первая": ["09:00", "10:30"]}', "{}"],
)
def test_bells_of_the_wrong_shape_fall_back_to_the_code(tmp_path, monkeypatch, content):
    """Третий аудит, В18 прогона 2: правильный JSON не того вида — массив, null,
    строка вместо пары — валил запуск или раздавался телефонам посимвольно."""
    path = tmp_path / "bells.json"
    path.write_text(content, "utf-8")
    monkeypatch.setenv("WHENSCLASS_BELLS", str(path))
    assert load_bells() == BELLS
