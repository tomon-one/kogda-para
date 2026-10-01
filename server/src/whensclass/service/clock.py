"""Часы колледжа: сегодня и часовой пояс — по настройке, а не по машине."""

from __future__ import annotations

import datetime as dt
import zoneinfo

from ..config import settings


def _today() -> dt.date:
    """Сегодня по часовому поясу колледжа, а не по поясу машины.

    WHENSCLASS_TIMEZONE управлял только планировщиком, а вся арифметика дат шла
    через date.today(). На сервере в UTC ночная переиндексация в 03:30 по
    Новосибирску считала вчерашнюю дату и искала лист на вчера.
    """
    return dt.datetime.now(zoneinfo.ZoneInfo(settings.timezone)).date()


def _zone() -> zoneinfo.ZoneInfo:
    return zoneinfo.ZoneInfo(settings.timezone)
