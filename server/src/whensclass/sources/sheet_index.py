"""Поиск листа, покрывающего нужную дату.

Лист в книге живёт две-три недели и растёт на месте: «расписание групп
01.-05.09» стал «…01.-19.09» с тем же gid. Имя при этом врёт про даты, поэтому
годится только чтобы упорядочить кандидатов, а решение принимается по
содержимому. Читается лист только по gid — значит, список листов нужен из
Sheets API: без ключа gid нет, и служба честно уходит в stale.
"""

from __future__ import annotations

import datetime as dt
import logging
import pathlib
import re

import httpx

from ..api.payloads import filled_until
from ..config import settings
from ..domain.models import SheetTooSmall, SourceFormatChanged
from ..parser.csv_schedule import Limits
from ..parser.export import parse_csv
from ..service import alerts
from . import gsheets
from .gsheets import SheetInfo
from .sheet_memory import SheetIndex, _recent_miss, _remember_miss

log = logging.getLogger(__name__)

# Листов в книге больше сотни: экзамены, аудитории, графики. Расписание групп
# ищем среди тех, чьё имя говорит о расписании и не говорит об индивидуальном.
_LOOKS_LIKE_SCHEDULE = re.compile(r"расписан|график", re.IGNORECASE)
_NOT_SCHEDULE = re.compile(r"индивидуальн|преподавател|аудитор|экз", re.IGNORECASE)
# Лист, который по имени и есть расписание групп. Нужен, чтобы отличить
# честный отказ («это календарный график») от тревожного («это наш лист,
# но прочитать его не вышло»).
_LOOKS_LIKE_GROUPS = re.compile(r"групп", re.IGNORECASE)
_NUMBERS = re.compile(r"\d{1,2}")


class SheetNotFound(LookupError):
    """Листа на нужный день нет или до него не добраться.

    Свой класс, а не голый LookupError: тот ловил заодно любой KeyError и
    IndexError из кода обновления, и ошибка в коде выглядела как «колледж
    ещё не выложил».
    """


def _api_error(exc: Exception) -> str:
    """Что сказать о сбое API наружу: код ответа или тип, без адреса и ключа."""
    response = getattr(exc, "response", None)
    code = getattr(response, "status_code", None)
    return f"ответ {code}" if code else type(exc).__name__


def hide_key(error: object) -> str:
    """Прячет ключ API в тексте ошибки.

    httpx кладёт в исключение полный адрес запроса вместе с ?key=..., а ключ
    держат в файле с правами 600 именно для того, чтобы он не разошёлся по
    машине. В журнал он попадал открытым текстом при первой же ошибке Google.
    """
    return re.sub(r"key=[^&\s]+", "key=***", str(error))


# Последний список листов от Sheets API и когда он получен: при сбое API служба
# живёт им, а не выгружает книгу в xlsx — та gid не даёт.
_LIST_KEEP = dt.timedelta(hours=6)
_last_list: list[SheetInfo] = []
_last_list_at: dt.datetime | None = None
# Сколько раз подряд Sheets API не ответил. Мёртвый ключ (отозван, квота)
# раньше был виден только как WARNING раз в полчаса, а тревога приходила,
# лишь когда кончалось покрытие запомненного листа — в воскресенье ночью.
# Один отказ — чих, два подряд (полчаса) — тревога.
_api_failures = 0
API_FAILURES_TO_ALERT = 2
_KEY_CODES = (400, 401, 403, 429)


def looks_like_groups(title: str) -> bool:
    """Лист по имени — расписание групп (кандидат, до которого стоит достучаться)."""
    return bool(_LOOKS_LIKE_GROUPS.search(title))


def list_sheets() -> list[SheetInfo]:
    """Список листов через Sheets API. Без ключа и без свежего списка — нет.

    При сбое API — последний удачный список, если ему меньше шести часов:
    gid листов от этого не меняются.
    """
    global _api_failures, _last_list, _last_list_at

    if not settings.sheets_api_key:
        raise SheetNotFound(
            "ключа Sheets API нет — gid листов взять неоткуда"
        )
    try:
        sheets = gsheets.list_sheets_via_api(settings.sheets_api_key)
    except Exception as exc:  # ключ протух, квота, сеть
        log.warning("Sheets API не ответил (%s)", hide_key(exc))
        # Отказом ключа считаются только ответы Google о ключе и квоте; сеть
        # — сетевой тревогой, иначе на одну беду приходили две, и одна звала
        # проверять ключ.
        code = getattr(getattr(exc, "response", None), "status_code", None)
        if code in _KEY_CODES:
            _api_failures += 1
        if _api_failures >= API_FAILURES_TO_ALERT and code in _KEY_CODES:
            alerts.notify(
                "key",
                f"Sheets API не отвечает {_api_failures} раз подряд "
                f"({_api_error(exc)}). Без него gid листов не узнать: пока "
                "служба живёт запомненным листом, а когда его покрытие кончится, "
                "уйдёт в stale. Проверить ключ и его ограничение по адресу "
                "сервера — руководство по серверу, «Ключ Sheets API».",
            )
        now = dt.datetime.now(dt.timezone.utc)
        if _last_list and _last_list_at and now - _last_list_at < _LIST_KEEP:
            log.info("список листов — прежний, от %s", _last_list_at)
            return _last_list
        if isinstance(exc, httpx.TransportError):
            # Сеть — это сеть: полчаса льготы и сетевая тревога. Раньше обрыв
            # здесь становился «не нашёл лист» — stale и тревога сразу, хотя
            # лист никуда не девался.
            raise
        raise SheetNotFound(f"Sheets API не ответил: {_api_error(exc)}") from exc
    if _api_failures >= API_FAILURES_TO_ALERT:
        alerts.forget("key")
        # Тот же процесс, без перезапуска: ключ не меняли, Google вернулся сам.
        alerts.notify("key-ok", "Само исправилось. Sheets API снова отвечает: ключ работает.",
                      force=True, good=True)
    _api_failures = 0
    _last_list, _last_list_at = sheets, dt.datetime.now(dt.timezone.utc)
    return sheets


def candidates(sheets: list[SheetInfo], day: dt.date) -> list[SheetInfo]:
    """Кандидаты в порядке правдоподобия: сперва те, чьи числа ближе к дате."""
    picked = [
        s for s in sheets
        if not s.hidden
        and _LOOKS_LIKE_SCHEDULE.search(s.title)
        and not _NOT_SCHEDULE.search(s.title)
    ]

    def distance(sheet: SheetInfo) -> int:
        numbers = [int(n) for n in _NUMBERS.findall(sheet.title)]
        days = [n for n in numbers if 1 <= n <= 31]
        return min((abs(n - day.day) for n in days), default=99)

    return sorted(picked, key=distance)


def resolve_window(
    start: dt.date, days: int, state_dir: pathlib.Path, deep: bool = False
) -> list[tuple[str, str | None]]:
    """Листы, покрывающие ближайшие `days` дней, по порядку.

    Неделя вперёд часто перешагивает границу листа: лист живёт две недели,
    а в пятницу человек уже смотрит понедельник. Поэтому берём не один лист,
    а все, что попадают в окно, — если следующий уже опубликован.

    С `deep=False` соседний лист берём только из памяти: искать его в сети —
    это читать листы-кандидаты целиком, и делать это каждые двадцать минут
    незачем.
    """
    first = resolve_for(start, state_dir, deep=deep)
    sheets = [first]

    if settings.sheet_gid:
        return sheets

    end = start + dt.timedelta(days=days - 1)
    index = SheetIndex(state_dir)
    covered_to = _covered_to(index, first[0])
    if covered_to is None or covered_to >= end:
        return sheets

    # Окно выходит за край текущего листа — ищем следующий.
    following = index.following(covered_to)
    if following is None:
        if not deep:
            # Искать соседний лист в сети — это читать листы-кандидаты
            # целиком, по мегабайту и больше. Раз в сутки (deep) не жалко,
            # каждые двадцать минут — уже расточительство.
            return sheets
        try:
            # Глубоко, как и весь заход: без deep промах, записанный меньше
            # двух часов назад, прятал только что появившийся лист до ночи.
            following = resolve_for(covered_to + dt.timedelta(days=1), state_dir, deep=True)
        except SheetNotFound:
            log.info("следующий лист ещё не опубликован, отдаём что есть")
            return sheets
        except (httpx.HTTPError, OSError) as exc:
            # И 429 или 5xx от Google, и ошибка чтения кандидата: следующий
            # лист не главнее текущего, заход не валится.
            log.info("следующий лист не посмотрелся (%s), отдаём что есть", type(exc).__name__)
            return sheets
    if following:
        same = following[1] == first[1] if first[1] else following[0] == first[0]
        if not same:
            sheets.append(following)
        elif following[0] != first[0]:
            # Тот же лист, переименованный на месте: тот же gid под новым
            # именем. Второй раз его не качать — взять свежее имя.
            sheets[0] = following
    return sheets


def _covered_to(index: SheetIndex, title: str) -> dt.date | None:
    info = index.known.get(title)
    if not info:
        return None
    try:
        return dt.date.fromisoformat(info["to"])
    except (KeyError, ValueError):
        return None


def _candidate_limits() -> Limits:
    """Пороги, по которым поиск узнаёт наш лист среди кандидатов.

    Узнаёт по заголовку групп — это отличает расписание групп от графиков.
    Объём здесь не спрашиваем: новый лист, в который вписали одну-две даты,
    иначе считался «не похожим на расписание», поиск уходил в «добраться не
    вышло», а рычаг WHENSCLASS_MIN_DATES сюда не доходил.
    Судит объём тот же разбор в обновлении — со своими порогами из настроек и
    с честным «нашёл всего 2 дня» в err.
    """
    return Limits(
        min_groups=settings.min_groups, min_dates=1, min_lessons=1,
        max_gap_days=settings.max_gap_days,
        max_days_ahead=settings.max_days_ahead,
    )


def resolve_for(
    day: dt.date, state_dir: pathlib.Path, deep: bool = False
) -> tuple[str, str | None]:
    """Возвращает (название листа, gid) для даты.

    Сначала смотрит в память — обычно этого хватает и в сеть ходить не надо.
    Аварийная настройка из окружения перебивает всё. `deep` — искать в сети,
    даже если недавно уже искали и не нашли.
    """
    if settings.sheet_gid:
        # Имя — только подпись в ответе и журнале.
        return f"gid {settings.sheet_gid}", settings.sheet_gid

    index = SheetIndex(state_dir)
    remembered = index.covering(day)
    if remembered:
        return remembered
    if not deep and _recent_miss(state_dir, day):
        return _fallback(index, day)

    # Листы, до содержимого которых мы так и не добрались. Если день в итоге
    # окажется не покрыт, разница принципиальная: «колледж ещё не выложил» —
    # это одно, а «мы не смогли посмотреть» — совсем другое.
    unread: list[str] = []
    # Кого из них не прочла сеть: если только сеть, это сетевой сбой (льгота
    # полчаса, тревога «таблица не прочиталась»), а не «лист не нашёлся» —
    # stale сразу и тревога звала смотреть лист.
    network: dict[str, Exception] = {}

    for sheet in candidates(list_sheets(), day):
        if sheet.gid is None:
            # Без gid лист не прочитать: сырой экспорт знает только gid. Sheets
            # API gid отдаёт всегда, так что это его сбой. Молчать нельзя: это
            # «мы не посмотрели».
            log.info("у листа %r нет gid — его не прочитать", sheet.title)
            if _LOOKS_LIKE_GROUPS.search(sheet.title):
                unread.append(sheet.title)
            continue
        try:
            text = gsheets.fetch_sheet_csv(gid=sheet.gid, title=sheet.title)
            if not text.strip():
                if _LOOKS_LIKE_GROUPS.search(sheet.title):
                    unread.append(sheet.title)
                continue
            snapshot = parse_csv(text, sheet.title, limits=_candidate_limits(), around=day)
        except gsheets.SheetClosed:
            # Закрытую таблицу поиск выдавал за «добраться не вышло», и
            # владелец шёл смотреть сеть и ключ, а не доступ. Наверх — как в заходе по gid.
            raise
        except SheetTooSmall as exc:
            # Каркас дат без пар или один-два дня — лист прочитан, колледж его
            # только заводит (каникулы, начало семестра). Это «ещё не
            # выложено», а не «добраться не вышло»: раньше такой лист уводил
            # службу в stale с красным у всех.
            log.info("лист %r ещё не заполнен: %s", sheet.title, exc)
            continue
        except SourceFormatChanged as exc:
            # «Не похож на расписание групп» — обычно честный отказ: в книге
            # лежат и календарный график, и расписание аудиторий. Но если так
            # ответил лист, который по имени и есть расписание групп, то либо
            # формат переделали, либо мы разучились его читать.
            if _LOOKS_LIKE_GROUPS.search(sheet.title):
                unread.append(sheet.title)
            log.info("лист %r не похож на расписание групп: %s", sheet.title, exc)
            continue
        except Exception as exc:
            # Тревожимся только за листы, которые по имени и есть расписание
            # групп: в книге сотня кандидатов, и половина из них — графики
            # и расписания аудиторий, до которых нам дела нет. Раньше эта
            # проверка стояла только в ветке разбора, а тут её не было, и
            # сбой при чтении календарного графика уводил бы службу в stale.
            if _LOOKS_LIKE_GROUPS.search(sheet.title):
                unread.append(sheet.title)
                if _is_network(exc):
                    network[sheet.title] = exc
            log.warning("лист %r не прочитался: %s", sheet.title, exc)
            continue

        coverage = snapshot.coverage
        if not coverage:
            continue
        # До последнего дописанного дня, а не до конца каркаса дат.
        last = filled_until(snapshot) or coverage[1]
        index.remember(sheet.title, sheet.gid, coverage[0], last)
        if coverage[0] <= day <= last:
            return sheet.title, sheet.gid

    # Ни один лист не покрывает день. Это не обязательно поломка: между листами
    # есть воскресенье, которого нет ни в одном из них.
    #
    # Но если мы при этом до чего-то не добрались, промолчать нельзя. Иначе
    # прежний снимок останется под видом свежего, приложение скажет «колледж
    # ещё не выложил» — а проверить это утверждение мы как раз и не смогли.
    # Пусть лучше служба уйдёт в stale и скажет, что беда у нас.
    if unread and set(unread) <= set(network):
        raise network[unread[-1]]
    if unread:
        raise SheetNotFound(
            f"лист на {day.isoformat()} не нашёлся, а до "
            + ", ".join(repr(title) for title in unread)
            + " добраться не вышло"
        )

    _remember_miss(state_dir, day)
    return _fallback(index, day)


def _is_network(exc: Exception) -> bool:
    """Сеть или Google на минуту: обрыв, таймаут, ssh до exit, 429 и 5xx."""
    if isinstance(exc, httpx.HTTPStatusError):
        return exc.response.status_code == 429 or exc.response.status_code >= 500
    return isinstance(exc, (httpx.TransportError, OSError))


def _fallback(index: SheetIndex, day: dt.date) -> tuple[str, str | None]:
    fallback = index.nearest(day)
    if fallback:
        log.info(
            "лист на %s не нашёлся, беру ближайший: %r",
            day.isoformat(), fallback[0],
        )
        return fallback

    raise SheetNotFound(f"не нашёл лист, покрывающий {day.isoformat()}")
