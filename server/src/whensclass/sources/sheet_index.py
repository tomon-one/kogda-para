"""Поиск листа, покрывающего нужную дату.

Лист в книге живёт две-три недели и растёт на месте: «расписание групп
01.-05.09» стал «…01.-19.09» с тем же gid. Имя при этом врёт про даты, поэтому
годится только чтобы упорядочить кандидатов, а решение принимается по
содержимому. Читается лист только по gid — значит, список листов нужен из
Sheets API: без ключа gid нет, и служба честно уходит в stale.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import pathlib
import re

from ..config import settings
from ..domain.models import SourceFormatChanged
from ..parser.csv_schedule import parse_csv
from . import gsheets
from .gsheets import SheetInfo

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


class SheetIndex:
    """Помнит, какой лист какие даты покрывает, чтобы не искать каждый раз."""

    def __init__(self, state_dir: pathlib.Path):
        self.path = state_dir / "sheet_index.json"
        self.known: dict[str, dict] = {}
        self._load()

    def _load(self) -> None:
        try:
            self.known = json.loads(self.path.read_text("utf-8"))
        except (OSError, ValueError):
            self.known = {}

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.known, ensure_ascii=False, indent=1), "utf-8")
        tmp.replace(self.path)

    def remember(self, title: str, gid: str | None, first: dt.date, last: dt.date) -> None:
        if gid is not None:
            # Лист переименовали, не пересоздавая: 11 сентября 2026 «расписание
            # групп 01.-05.09» стал «…01.-19.09» с тем же gid. Старое имя с тем
            # же gid — тот же лист; оставить его значит подписывать снимок
            # старым именем и читать один лист дважды.
            for other, info in list(self.known.items()):
                if other != title and info.get("gid") == gid:
                    del self.known[other]
        self.known[title] = {
            "gid": gid,
            "from": first.isoformat(),
            "to": last.isoformat(),
            "probed": dt.date.today().isoformat(),
        }
        self.save()

    def forget(self, title: str) -> None:
        """Лист удалили или пересоздали — запись о нём больше не правда."""
        if title in self.known:
            del self.known[title]
            self.save()

    def nearest(self, day: dt.date) -> tuple[str, str | None] | None:
        """Ближайший по датам лист, когда день не покрыт ни одним.

        Воскресений в листах колледжа нет, поэтому день между двумя листами не
        покрыт ничем: старый кончился в субботу, новый начнётся в понедельник.
        Раньше это роняло поиск, служба уходила в stale на все сутки и отдавала
        пустую неделю — при том что новый лист уже был опубликован и найден.

        Ближайший лист лучше пустоты, и будущий предпочтительнее прошедшего:
        в воскресенье человек смотрит на неделю, которая начнётся завтра.
        """
        best = None
        for title, info in self.known.items():
            try:
                first = dt.date.fromisoformat(info["from"])
                last = dt.date.fromisoformat(info["to"])
            except (KeyError, ValueError):
                continue
            if day < first:
                key = (0, (first - day).days)
            elif day > last:
                key = (1, (day - last).days)
            else:
                key = (0, 0)
            if best is None or key < best[0]:
                best = (key, (title, info.get("gid")))
        return best[1] if best else None

    def following(self, after: dt.date) -> tuple[str, str | None] | None:
        """Ближайший лист, начинающийся позже указанного дня.

        Спрашивать вместо этого `covering(after + 1)` нельзя, хотя раньше так и
        было: между листами всегда лежит воскресенье, которого нет ни в одном
        из них. Память отвечала «не знаю» ровно на той границе, ради которой её
        и спрашивают, — и неделя вперёд в пятницу обрывалась субботой, даже
        когда следующий лист был давно найден и записан.
        """
        best = None
        for title, info in self.known.items():
            try:
                first = dt.date.fromisoformat(info["from"])
            except (KeyError, ValueError):
                continue
            if first > after and (best is None or first < best[0]):
                best = (first, (title, info.get("gid")))
        return best[1] if best else None

    def covering(self, day: dt.date) -> tuple[str, str | None] | None:
        for title, info in self.known.items():
            try:
                first = dt.date.fromisoformat(info["from"])
                last = dt.date.fromisoformat(info["to"])
            except (KeyError, ValueError):
                continue
            if first <= day <= last:
                return title, info.get("gid")
        return None


def _hide_key(error: object) -> str:
    """Прячет ключ API в тексте ошибки.

    httpx кладёт в исключение полный адрес запроса вместе с ?key=..., а ключ
    держат в файле с правами 600 именно для того, чтобы он не разошёлся по
    машине. В журнал он попадал открытым текстом при первой же ошибке Google.
    """
    return re.sub(r"key=[^&\s]+", "key=***", str(error))


# Выгрузка всей книги весит около двадцати мегабайт и занимает секунд двадцать.
# Пока лист на сегодня не находится, resolve_for зовётся при каждом заходе
# планировщика, то есть каждые двадцать минут: это гигабайт в сутки к Google с
# одного адреса и полсотни длинных блокировок потока. Комментарии рядом обещали
# «раз в сутки» — теперь это правда и для случая, когда лист не нашёлся.
_XLSX_COOLDOWN = dt.timedelta(hours=6)
_last_xlsx: dt.datetime | None = None
_cached_sheets: list[SheetInfo] = []


def list_sheets() -> list[SheetInfo]:
    """Список листов: через API, если есть ключ, иначе через выгрузку xlsx."""
    global _last_xlsx, _cached_sheets

    if settings.sheets_api_key:
        try:
            return gsheets.list_sheets_via_api(settings.sheets_api_key)
        except Exception as exc:  # ключ протух, квота, сеть
            log.warning("Sheets API не ответил (%s), иду через xlsx", _hide_key(exc))

    now = dt.datetime.now(dt.timezone.utc)
    if _cached_sheets and _last_xlsx and now - _last_xlsx < _XLSX_COOLDOWN:
        log.info("список листов беру из памяти: книгу выгружали %s", _last_xlsx)
        return _cached_sheets

    sheets = gsheets.list_sheets_via_xlsx()
    _last_xlsx, _cached_sheets = now, sheets
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
    это выгрузка всей книги, и делать её каждые двадцать минут незачем.
    """
    first = resolve_for(start, state_dir)
    sheets = [first]

    if settings.sheet_title or settings.sheet_gid:
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
            # Искать соседний лист в сети — это выгрузка всей книги на два
            # десятка мегабайт. Раз в сутки (deep) не жалко, каждые двадцать
            # минут — уже расточительство.
            return sheets
        try:
            following = resolve_for(covered_to + dt.timedelta(days=1), state_dir)
        except LookupError:
            log.info("следующий лист ещё не опубликован, отдаём что есть")
            return sheets
    if following and following[0] != first[0]:
        sheets.append(following)
    return sheets


def _covered_to(index: SheetIndex, title: str) -> dt.date | None:
    info = index.known.get(title)
    if not info:
        return None
    try:
        return dt.date.fromisoformat(info["to"])
    except (KeyError, ValueError):
        return None


def resolve_for(day: dt.date, state_dir: pathlib.Path) -> tuple[str, str | None]:
    """Возвращает (название листа, gid) для даты.

    Сначала смотрит в память — обычно этого хватает и в сеть ходить не надо.
    Аварийная настройка из окружения перебивает всё.
    """
    if settings.sheet_title or settings.sheet_gid:
        return settings.sheet_title or "", settings.sheet_gid

    index = SheetIndex(state_dir)
    remembered = index.covering(day)
    if remembered:
        return remembered

    # Листы, до содержимого которых мы так и не добрались. Если день в итоге
    # окажется не покрыт, разница принципиальная: «колледж ещё не выложил» —
    # это одно, а «мы не смогли посмотреть» — совсем другое.
    unread: list[str] = []

    for sheet in candidates(list_sheets(), day):
        if sheet.gid is None:
            # Без gid лист не прочитать: сырой экспорт знает только gid, а
            # список без gid бывает лишь из xlsx — когда ключа Sheets API нет
            # или он протух. Молчать нельзя: это «мы не посмотрели».
            log.info("у листа %r нет gid — без ключа Sheets API его не прочитать", sheet.title)
            if _LOOKS_LIKE_GROUPS.search(sheet.title):
                unread.append(sheet.title)
            continue
        try:
            text = gsheets.fetch_sheet_csv(gid=sheet.gid, title=sheet.title)
            if not text.strip():
                if _LOOKS_LIKE_GROUPS.search(sheet.title):
                    unread.append(sheet.title)
                continue
            snapshot = parse_csv(text, sheet.title)
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
            log.warning("лист %r не прочитался: %s", sheet.title, exc)
            continue

        coverage = snapshot.coverage
        if not coverage:
            continue
        index.remember(sheet.title, sheet.gid, *coverage)
        if coverage[0] <= day <= coverage[1]:
            return sheet.title, sheet.gid

    # Ни один лист не покрывает день. Это не обязательно поломка: между листами
    # есть воскресенье, которого нет ни в одном из них.
    #
    # Но если мы при этом до чего-то не добрались, промолчать нельзя. Иначе
    # прежний снимок останется под видом свежего, приложение скажет «колледж
    # ещё не выложил» — а проверить это утверждение мы как раз и не смогли.
    # Пусть лучше служба уйдёт в stale и скажет, что беда у нас.
    if unread:
        raise LookupError(
            f"лист на {day.isoformat()} не нашёлся, а до "
            + ", ".join(repr(title) for title in unread)
            + " добраться не вышло"
        )

    fallback = index.nearest(day)
    if fallback:
        log.info(
            "лист на %s не нашёлся, беру ближайший: %r",
            day.isoformat(), fallback[0],
        )
        return fallback

    raise LookupError(f"не нашёл лист, покрывающий {day.isoformat()}")
