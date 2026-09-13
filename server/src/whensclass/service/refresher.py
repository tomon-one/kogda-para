"""Обновление расписания из таблицы.

Главное правило: неудачное обновление не должно делать пользователей слепыми.
Если таблица не читается или перестала соответствовать формату, прежний снимок
остаётся в силе, а сервис уходит в состояние stale — показать вчерашнее лучше,
чем показать пустоту.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import logging
import pathlib
import threading
import zoneinfo

import httpx

from ..config import settings
from ..domain.models import SourceFormatChanged
from ..parser.csv_schedule import Limits, parse_export
from ..sources import gsheets, sheet_index
from ..storage import history
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
        # С какого момента и почему не обновляемся. Лежит на диске: службу
        # перезапускают при каждой выкладке, а «лежим с четверга» должно
        # пережить перезапуск, иначе двое суток выглядят как минута.
        self._failing_path = state_dir / "failing.json"
        self.failing_since: dt.datetime | None = None
        self.last_error: str | None = None
        self._alerted = False
        self._load_failing()
        # Хеш текста каждого листа с прошлого удачного разбора: экспорт не
        # отдаёт ETag, «не изменилось» узнаём сами.
        self._source_hashes: dict[str, str] = {}
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

    def _refresh(self, today: dt.date, force: bool, retried: bool = False) -> bool:
        self.checked_at = dt.datetime.now(dt.timezone.utc)
        texts: list[tuple[str, str | None, str, str]] = []
        try:
            if self._sheets is None or force:
                self._sheets = sheet_index.resolve_window(
                    today, settings.window_days, self.state_dir, deep=force
                )

            for title, gid in self._sheets:
                try:
                    text = gsheets.fetch_sheet_csv(gid=gid, title=title or None)
                except httpx.HTTPStatusError as exc:
                    if 400 <= exc.response.status_code < 500 and not retried:
                        # Лист удалили или пересоздали: gid из памяти мёртв.
                        # Забываем его и ищем заново в том же заходе — иначе
                        # stale держался бы до конца запомненного покрытия.
                        log.warning(
                            "лист %r по gid %s не отдаётся (%s) — забываю и ищу заново",
                            title, gid, exc.response.status_code,
                        )
                        sheet_index.SheetIndex(self.state_dir).forget(title)
                        self._sheets = None
                        return self._refresh(today, force=True, retried=True)
                    raise
                texts.append((title, gid, text, hashlib.sha256(text.encode("utf-8")).hexdigest()))

            if not force and all(self._source_hashes.get(t) == h for t, _, _, h in texts):
                # Не изменился ни один лист — перерисовывать нечего.
                self.status = "ok"
                self._recovered()
                return False

            snapshot = None
            for title, gid, text, _ in texts:
                current = parse_export(
                    text, title or f"gid {gid}", gid, limits=_limits(), around=today
                )
                snapshot = current if snapshot is None else snapshot.merged_with(current)
            if snapshot is None:
                self._fail(f"не нашёл лист на {today}: набор листов пуст", kind="sheet")
                return False
            _check_group_drop(self.store.snapshot, snapshot)
        except SourceFormatChanged as exc:
            # Самый опасный случай: таблицу переделали. Держим прежнее —
            # а отвергнутый лист кладём в архив вместе с причиной: разбирать
            # инцидент по листу, который колледж уже переправил, нельзя.
            for _, gid, text, digest in texts:
                history.archive(self.state_dir, gid, text, digest, rejected=str(exc))
            self._fail(f"формат таблицы изменился: {exc}", kind="format")
            return False
        except LookupError as exc:
            self._fail(f"не нашёл лист на {today}: {exc}", kind="sheet")
            return False
        except Exception as exc:
            # Наружу — только тип: в тексте httpx приводит адрес запроса с
            # параметрами, а /v1/meta открыт всем.
            self._fail(
                f"таблица не прочиталась: {exc}", kind="fetch",
                public=f"таблица не прочиталась: {type(exc).__name__}",
            )
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
        self._source_hashes = {title: digest for title, _, _, digest in texts}
        for _, gid, text, digest in texts:
            history.archive(self.state_dir, gid, text, digest)
        self.status = "ok"
        self._recovered()
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

    def _fail(self, message: str, kind: str = "error", public: str | None = None) -> None:
        self.status = "stale" if self.store.snapshot else "empty"
        self._sheets = None
        log.error("%s (состояние: %s)", message, self.status)

        now = dt.datetime.now(dt.timezone.utc)
        first = self.failing_since is None
        if first:
            self.failing_since = now
            self._alerted = False
        self.last_error = public or message

        # Формат и поиск листа сами не чинятся — говорить сразу. Сеть и
        # Google чинятся к следующему заходу: о них — только если лежим
        # дольше получаса, иначе каждый чих Google будит человека дважды.
        lying = (now - self.failing_since).total_seconds()
        if kind != "fetch" or lying >= 30 * 60:
            sent = alerts.notify(
                kind,
                f"{message}. Состояние: {self.status}, "
                + ("отдаётся прежнее расписание." if self.status == "stale"
                   else "отдавать нечего.")
                + ("" if first else
                   f" Лежим с {self.failing_since.astimezone(_zone()):%d.%m %H:%M}."),
                force=not self._alerted,
            )
            self._alerted = self._alerted or sent
        self._save_failing(kind)

    def _recovered(self) -> None:
        """Обновление удалось после сбоя: сказать, что и сколько лежало."""
        since = self.failing_since
        if since is None:
            return
        lying = dt.datetime.now(dt.timezone.utc) - since
        hours = lying.total_seconds() / 3600
        if self._alerted:
            alerts.notify(
                "recovered",
                f"Расписание снова обновляется. Лежало {hours:.1f} ч, "
                f"с {since.astimezone(_zone()):%d.%m %H:%M}.",
                force=True, good=True,
            )
        for kind in ("format", "sheet", "fetch", "error"):
            alerts.forget(kind)
        self.failing_since, self.last_error, self._alerted = None, None, False
        self._save_failing(None)

    def _save_failing(self, kind: str | None) -> None:
        try:
            if self.failing_since is None:
                self._failing_path.unlink(missing_ok=True)
                return
            tmp = self._failing_path.with_suffix(".tmp")
            tmp.write_text(json.dumps({
                "since": self.failing_since.isoformat(),
                "error": self.last_error,
                "kind": kind,
                "alerted": self._alerted,
            }, ensure_ascii=False), "utf-8")
            tmp.replace(self._failing_path)
        except OSError as exc:
            log.warning("состояние сбоя не записалось: %s", exc)

    def _load_failing(self) -> None:
        try:
            data = json.loads(self._failing_path.read_text("utf-8"))
            self.failing_since = dt.datetime.fromisoformat(data["since"])
            self.last_error = data.get("error")
            self._alerted = bool(data.get("alerted"))
        except (OSError, ValueError, KeyError, TypeError):
            self.failing_since, self.last_error, self._alerted = None, None, False


# Набор групп упал больше чем на треть между двумя снимками одного и того же
# листа — это не «колледж убрал группы», а сломанный заголовок: 105 из 187
# групп проходят порог в сто, и 80 групп молча получают 404. Самая крупная
# когорта (-926) — 28 % от всех, уход целого курса под отказ не попадает.
MAX_GROUP_DROP = 0.3


def _check_group_drop(previous, current) -> None:
    """Отказ, если группы пропали толпой при тех же датах."""
    if previous is None or not previous.groups:
        return
    if not (set(previous.dates) & set(current.dates)):
        # Новый лист, новый состав — сравнивать не с чем.
        return
    kept = {g.id for g in current.groups}
    lost = [g.name for g in previous.groups if g.id not in kept]
    if len(lost) > MAX_GROUP_DROP * len(previous.groups):
        raise SourceFormatChanged(
            f"пропало {len(lost)} групп из {len(previous.groups)}: "
            + ", ".join(lost[:10]) + ("…" if len(lost) > 10 else "")
        )


def _limits() -> Limits:
    return Limits(
        min_groups=settings.min_groups,
        min_dates=settings.min_dates,
        min_lessons=settings.min_lessons,
    )


def _zone() -> zoneinfo.ZoneInfo:
    return zoneinfo.ZoneInfo(settings.timezone)


def state_dir() -> pathlib.Path:
    return pathlib.Path(settings.state_dir).resolve()
