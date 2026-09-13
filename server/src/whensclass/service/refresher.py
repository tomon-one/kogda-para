"""Обновление расписания из таблицы.

Главное правило: неудачное обновление не должно делать пользователей слепыми.
Если таблица не читается или перестала соответствовать формату, прежний снимок
остаётся в силе, а сервис уходит в состояние stale — показать вчерашнее лучше,
чем показать пустоту.
"""

from __future__ import annotations

import datetime as dt
import logging
import pathlib
import threading
import zoneinfo

from ..config import settings
from ..domain.models import SheetPlace, SourceFormatChanged
from ..parser.csv_schedule import date_rows, parse_csv
from ..sources import gsheets, sheet_index
from . import alerts
from .renames import RenameBook
from ..storage.snapshot_store import SnapshotStore

log = logging.getLogger(__name__)


def _today() -> dt.date:
    """Сегодня по часовому поясу колледжа, а не по поясу машины.

    WHENSCLASS_TIMEZONE управлял только планировщиком, а вся арифметика дат шла
    через date.today(). На сервере в UTC ночная переиндексация в 03:30 по
    Новосибирску считала вчерашнюю дату и искала лист на вчера.
    """
    return dt.datetime.now(zoneinfo.ZoneInfo(settings.timezone)).date()


class Refresher:
    def __init__(self, store: SnapshotStore, state_dir: pathlib.Path):
        self.store = store
        self.state_dir = state_dir
        self.status = "empty"          # empty | ok | stale
        self.checked_at: dt.datetime | None = None
        # Кого как переименовали: по старому id отвечаем расписанием нового.
        self.renames = RenameBook(state_dir)
        self._source_etags: dict[str, str | None] = {}
        self._sheets: list[tuple[str, str | None]] | None = None
        # Имена листов, которые мы уже видели в книге. None — ещё не смотрели.
        self._seen_titles: set[str] | None = None
        # Задачи планировщика идут в разных потоках и в начале каждого часа
        # совпадают: обновление раз в 20 минут и проверка книги раз в 30.
        # Без замка они переписывали друг другу status и набор листов —
        # неудачный заход мог объявить stale поверх только что удавшегося.
        # Замок повторный: слежка за книгой сама зовёт refresh.
        self._lock = threading.RLock()

    def refresh(self, today: dt.date | None = None, force: bool = False) -> bool:
        """Перечитывает таблицу. True, если снимок обновился."""
        with self._lock:
            return self._refresh(today or _today(), force)

    def _refresh(self, today: dt.date, force: bool) -> bool:
        self.checked_at = dt.datetime.now(dt.timezone.utc)
        try:
            if self._sheets is None or force:
                self._sheets = sheet_index.resolve_window(
                    today, settings.window_days, self.state_dir, deep=force
                )

            snapshot = None
            for title, gid in self._sheets:
                text, etag = gsheets.fetch_sheet_csv(
                    gid=gid,
                    title=title or None,
                    etag=None if force else self._source_etags.get(title),
                )
                if text is None:
                    # Google ответил 304: этот лист не менялся. Но снимок
                    # собирается из всех листов окна, и без прежнего содержимого
                    # он получился бы только из изменившегося: покрытие
                    # схлопнулось бы на одну неделю, а сегодняшний день пропал.
                    # Поэтому перечитываем его без условного запроса.
                    text, etag = gsheets.fetch_sheet_csv(gid=gid, title=title or None)
                    if text is None:
                        continue
                self._source_etags[title] = etag
                current = parse_csv(text, title or f"gid {gid}")
                current.places = self._places(title, gid)
                snapshot = current if snapshot is None else snapshot.merged_with(current)

            if snapshot is None:
                # Не изменился ни один лист — перерисовывать нечего.
                self.status = "ok"
                return False
        except SourceFormatChanged as exc:
            # Самый опасный случай: таблицу переделали. Держим прежнее.
            self._fail(f"формат таблицы изменился: {exc}", kind="format")
            return False
        except LookupError as exc:
            self._fail(f"не нашёл лист на {today}: {exc}", kind="sheet")
            return False
        except Exception as exc:
            self._fail(f"таблица не прочиталась: {exc}", kind="fetch")
            return False

        coverage = snapshot.coverage
        if coverage and not (coverage[0] <= today <= coverage[1]):
            # Сегодняшний день вышел за край листа — набор листов пора
            # пересобрать. То, что окно шире листа, поводом не считаем:
            # следующий лист ищется ночью, чтобы не выгружать книгу впустую.
            log.info(
                "лист %r больше не покрывает %s — пересоберу набор",
                snapshot.sheet_title, today,
            )
            self._sheets = None

        previous = self.store.snapshot
        previous_teachers = self.store.teachers
        self.store.put(snapshot, dt.datetime.now(dt.timezone.utc))
        self.status = "ok"
        if previous is not None and previous_teachers is not None:
            try:
                self.renames.record(previous, snapshot, previous_teachers, self.store.teachers)
            except Exception as exc:
                # Книга — удобство поверх расписания, ронять из-за неё
                # обновление нельзя.
                log.warning("книга переименований не обновилась: %s", exc)
        log.info(
            "снимок обновлён: лист %r, %d групп, %d пар",
            snapshot.sheet_title, len(snapshot.groups), snapshot.total_lessons(),
        )
        return True

    def _places(self, title: str, gid: str | None) -> dict[dt.date, SheetPlace]:
        """Где в листе лежит каждый день — для ссылки «открыть таблицу».

        Номера строк есть только у Sheets API (см. `date_rows`); без ключа или
        без ответа ссылка откроет лист и колонку, но не подведёт к дню. Это
        удобство, а не расписание: ронять из-за него обновление нельзя.
        """
        if not settings.sheets_api_key or not title or gid is None:
            return {}
        try:
            column = gsheets.fetch_first_column_via_api(settings.sheets_api_key, title)
        except Exception as exc:
            log.warning(
                "строки дней листа %r не прочитались: %s", title, sheet_index._hide_key(exc)
            )
            return {}
        return {day: SheetPlace(gid=gid, row=row) for day, row in date_rows(column).items()}

    def look_for_new_sheet(self) -> bool:
        """Не появился ли в книге лист, которого мы ещё не видели.

        Проверка дешёвая: один маленький запрос к Sheets API. Нужна ради
        перехода между листами — самого опасного места в службе. Раньше новый
        лист искался только ночью, и о неделе, выложенной в пятницу днём, мы
        узнавали в субботу. Три дня форы на починку стоят одного запроса
        в полчаса.

        Без ключа не работает и не должна: там список листов — это выгрузка
        книги на два десятка мегабайт.
        """
        if not settings.sheets_api_key:
            return False
        with self._lock:
            return self._look()

    def _look(self) -> bool:
        try:
            titles = {
                sheet.title
                for sheet in sheet_index.candidates(sheet_index.list_sheets(), _today())
            }
        except Exception as exc:
            # Не дозвонились — не беда: ночной поиск никуда не делся.
            log.warning("список листов не посмотрелся: %s", exc)
            return False

        if self._seen_titles is None:
            # Первый заход после запуска: запоминаем, с чем сравнивать. Гнать
            # поиск прямо сейчас незачем — служба и так обновилась на старте.
            self._seen_titles = titles
            return False

        fresh = titles - self._seen_titles
        self._seen_titles = titles
        if not fresh:
            return False

        log.info("в книге появился лист: %s — ищу заново", ", ".join(sorted(fresh)))
        return self.refresh(force=True)

    def _fail(self, message: str, kind: str = "error") -> None:
        self.status = "stale" if self.store.snapshot else "empty"
        self._sheets = None
        log.error("%s (состояние: %s)", message, self.status)
        alerts.notify(
            kind,
            f"Когда пара?: {message}. "
            f"Состояние службы: {self.status}. "
            + (
                "Отдаётся прежнее расписание."
                if self.status == "stale"
                else "Расписание отдавать нечего."
            ),
        )


def state_dir() -> pathlib.Path:
    return pathlib.Path(settings.state_dir).resolve()
