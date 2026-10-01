"""Идентификатор группы — то, что менять нельзя никогда.

Слаг лежит в настройках на телефонах и в адресах API. Изменится правило
транслитерации — у всех, кто уже выбрал группу, идентификатор перестанет
совпадать с серверным, и приложение покажет пустоту вместо расписания.
Починить это удалённо будет нечем: настройка лежит у человека. Поэтому
функция закреплена поимённо: буква за буквой, случай за случаем.
"""

import pytest

from whensclass.domain.ids import group_id


def test_examples_from_the_docstring():
    """Оба примера, которыми функция себя описывает."""
    assert group_id("ИСП-924/2") == "isp-924-2"
    assert group_id("01-26.РКИ.ОФ.9") == "01-26-rki-of-9"


def test_real_group_names():
    """Имена, которые и правда есть в таблице колледжа."""
    assert group_id("ГД-1124/1") == "gd-1124-1"
    assert group_id("Э-923") == "e-923"
    assert group_id("Ю-923/1") == "yu-923-1"
    assert group_id("Л-923/1;2") == "l-923-1-2"
    # Составная колонка: обе группы делят одно расписание, но и у склейки
    # тоже есть слаг — его отдаёт список групп.
    assert group_id("ДП-923 и ДП-1124") == "dp-923-i-dp-1124"


def test_every_cyrillic_letter():
    """Вся таблица транслитерации разом.

    Проверяется целиком, потому что ошибка в одной букве видна только на
    группе с этой буквой, а групп почти две сотни.
    """
    alphabet = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    expected = (
        "abvgdee" "zh" "z" "i" "y" "klmnoprst" "u" "f" "h" "c" "ch" "sh" "sch"
        "" "y" "" "e" "yu" "ya"
    )
    assert group_id(alphabet) == expected


def test_hard_and_soft_signs_disappear_without_a_trace():
    """Ъ и Ь дают пустоту, а не дефис: иначе «ель» стала бы «e-l»."""
    assert group_id("ель") == "el"
    assert group_id("подъезд") == "podezd"


def test_case_and_spaces_do_not_matter():
    assert group_id("  ИСП-924/2  ") == "isp-924-2"
    assert group_id("исп-924/2") == "isp-924-2"


def test_separators_collapse_and_do_not_dangle():
    """Любой незнакомый знак — дефис, но подряд их не бывает и по краям тоже."""
    assert group_id("А--Б") == "a-b"
    assert group_id("А . , Б") == "a-b"
    assert group_id("-ИСП-") == "isp"


def test_latin_and_digits_pass_through():
    assert group_id("UX-2026") == "ux-2026"


@pytest.mark.parametrize("name", ["", "   ", "///", "---", "ъь"])
def test_name_without_a_single_letter_is_refused(name):
    """Пустой слаг — не идентификатор. Лучше упасть, чем отдать пустоту."""
    with pytest.raises(ValueError):
        group_id(name)
