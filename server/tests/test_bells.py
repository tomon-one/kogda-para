"""Сетка звонков колледжа."""

from whensclass.service.bells import BELLS


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
