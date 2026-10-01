"""Ворота обновления: новый снимок против прежнего принятого.

Лист разобрался без ошибок формата, но похож на поломку, если сравнить его с
прежним: группы пропали толпой, выложенный день опустел, над колонкой с парами
пропало имя, сегодняшний день исчез. Такой лист не принимается; после
каникул пропажа групп — только подозрение для тревоги.
"""

from __future__ import annotations

import datetime as dt

from ..domain.models import ChangedAgainstPrevious, a1_column
from ..sources import sheet_index


# Набор групп упал больше чем на 30 % между двумя снимками одного и того же
# листа — это не «колледж убрал группы», а сломанный заголовок: 105 из 187
# групп проходят порог в сто, и 80 групп молча получают 404. Самая крупная
# когорта (-926) — 28 % от всех, уход целого курса под отказ не попадает.
MAX_GROUP_DROP = 0.3
# Выложенный день, опустевший у стольких групп — не меньше DAY_EMPTIED_COUNT
# и доли DAY_EMPTIED_SHARE, — отказ (`_check_days_emptied`). Честно за заход
# день опустевает целиком максимум у 2 групп (архив 14–29.09), а при пороге
# 30 % четверть очищенного листа прошла бы валом «убрали». У дней,
# где пары были меньше чем у DAY_EMPTIED_MIN_GROUPS групп, не проверяется:
# суббота у пары десятков групп опустеет и честно.
DAY_EMPTIED_SHARE = 0.1
DAY_EMPTIED_COUNT = 12
DAY_EMPTIED_MIN_GROUPS = 20


def _check_group_drop(previous, current) -> str | None:
    """Отказ, если группы пропали толпой при тех же датах или на соседней
    неделе.

    Новый лист, впервые увиденный сразу первым (колледж выложил его в
    понедельник утром), с прежним дат не делит, поэтому соседний лист — зазор
    до трёх дней, как у `shift_seed`, — сверяется так же. После каникул состав
    законно другой: там — подозрение строкой, для тревоги, а не отказ.
    """
    if previous is None or not previous.groups or not previous.dates or not current.dates:
        return None
    kept = {g.id for g in current.groups}
    lost = [g.name for g in previous.groups if g.id not in kept]
    if len(lost) <= MAX_GROUP_DROP * len(previous.groups):
        return None
    message = (
        f"пропало {len(lost)} групп из {len(previous.groups)}: "
        + ", ".join(lost[:10]) + ("…" if len(lost) > 10 else "")
    )
    gap = (min(current.dates) - max(previous.dates)).days
    if set(previous.dates) & set(current.dates) or 0 <= gap <= 3:
        raise ChangedAgainstPrevious(message)
    return f"новый лист после перерыва в {gap} дн.: {message}"


def _check_days_emptied(previous, current, today: dt.date, dropped: bool = False) -> None:
    """Отказ, если на уже выложенном дне (сегодня и дальше) пары пропали
    целиком у многих групп.

    Так выглядит день, вырезанный вместо копирования или очищенный по ошибке:
    лист проходит все пороги объёма, а подписчикам сайта в том же заходе ушло
    бы «убрали N пару», и вторым валом — «добавилась», когда колледж вернёт
    день. Честно за заход пары убирают у 12 групп из 190 (15.09, архив
    14–28.09). Если колледж правда отменил день — рычаг accept-next.
    """
    if previous is None:
        return
    for day in sorted(set(previous.dates) & set(current.dates)):
        if day < today:
            continue
        had = [g.id for g in previous.groups if previous.schedule.get(g.id, {}).get(day)]
        if len(had) < DAY_EMPTIED_MIN_GROUPS:
            continue
        # Следующий лист выпал из окна, а в текущем на его даты — пустой
        # каркас: пары этих дней приходили из выпавшего, и «опустели» они
        # вместе с ним, а не вырезаны.
        if dropped and not any(current.schedule.get(g.id, {}).get(day) for g in current.groups):
            continue
        emptied = [gid for gid in had if not current.schedule.get(gid, {}).get(day)]
        if len(emptied) >= max(DAY_EMPTIED_COUNT, DAY_EMPTIED_SHARE * len(had)):
            raise ChangedAgainstPrevious(
                f"{day} у {len(emptied)} групп из {len(had)} пропали все пары — похоже на "
                "вырезанный или очищенный день"
            )


def _check_lost_names(previous, current, gid: str | None) -> None:
    """Отказ, если группа пропала, а её колонка на месте и с парами.

    Так выглядит стёртое или испорченное имя в главном заголовке, которого
    нет и в повторных: блок разбирается безымянным, и группа молча ушла бы в
    404 при ok. Пропажа одной группы не доходит до `_check_group_drop`, а
    колонка с парами — не убранная группа.
    """
    if previous is None or not current.unnamed or not gid:
        return
    if not (set(previous.dates) & set(current.dates)):
        return
    was = previous.sheet_columns.get(gid, {})
    kept = {g.id for g in current.groups}
    for group in previous.groups:
        column = was.get(group.id)
        if group.id not in kept and column in current.unnamed:
            raise ChangedAgainstPrevious(
                f"в главном заголовке у колонки {a1_column(column)} пропало имя группы "
                f"{group.name}, а пары под ним на месте ({current.unnamed[column]})"
            )


def _check_today_kept(previous, current, today: dt.date) -> None:
    """Прежний снимок знал сегодняшний день, новый — нет: это не обновление.

    Так выглядит подмена рабочего листа соседним: gid умер (или Google
    ответил 400), поиск не нашёл лист на сегодня и взял «ближайший» — и
    телефоны увидели бы «пар нет» при ok там, где минуту назад были пары.
    """
    if previous is None or today not in previous.dates or today in current.dates:
        return
    raise sheet_index.SheetNotFound(
        f"новый набор листов ({current.sheet_title!r}) не покрывает {today}, прежний покрывал"
    )
