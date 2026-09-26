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
from ..domain.models import SheetTooSmall, SourceFormatChanged, a1_column
from ..domain.teachers import build_index
from ..parser.csv_schedule import Limits, _check_shift, parse_export, shift_seed
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
        # Сбой из одних сетевых чихов: такой наружу не показываем, пока не
        # пролежал FETCH_GRACE, — как и тревогу владельцу (второй аудит, М25).
        self._fetch_only = True
        # Тревога «диск» уже ушла: следующая — только после окна тишины
        # (второй аудит, В16). Окна тишины помнятся между перезапусками (М35).
        alerts.keep_in(state_dir / "alerts.json")
        self._disk_alerted = alerts.sent_recently("disk")
        self._load_failing()
        # Сколько заходов подряд начались и не кончились: служба умерла
        # посреди разбора (память — MemoryMax) и перезапустилась. Перезапуск
        # по systemd стирал бы это без следа: status ok, тревоги нет, и так
        # по кругу (второй аудит, М36).
        self._running_path = state_dir / "refresh.running"
        try:
            self._crashes = int(self._running_path.read_text("utf-8").strip() or 0)
        except (OSError, ValueError):
            self._crashes = 0
        # Хеш текста каждого листа с прошлого удачного разбора: экспорт не
        # отдаёт ETag, «не изменилось» узнаём сами.
        self._source_hashes: dict[str, str] = {}
        # Листы окна, пропущенные в последнем разборе (заголовок -> почему).
        self._dropped: dict[str, str] = {}
        self._sheets: list[tuple[str, str | None]] | None = None
        # Имена листов, которые мы уже видели в книге. None — ещё не смотрели.
        # Имена листов, которые слежка уже видела, — на диске: после
        # перезапуска первый взгляд только запоминал имена, и лист, заведённый
        # колледжем, пока служба перезапускалась, искался только ночью
        # (третий аудит, М50 прогона 2).
        self._seen_path = state_dir / "seen_titles.json"
        self._seen_titles: set[str] | None = self._load_seen_titles()
        # Новые листы «групп», которые поиск пока не принял (пустые, одна
        # шапка): слежка перепроверяет их, а не забывает до ночи (М14).
        self._retry_titles: dict[str, int] = {}
        # Задачи планировщика идут в разных потоках и в начале каждого часа
        # совпадают: обновление раз в 20 минут и проверка книги раз в 30.
        # Без замка они переписывали друг другу status и набор листов —
        # неудачный заход мог объявить stale поверх только что удавшегося.
        # Замок повторный: слежка за книгой сама зовёт refresh.
        self._lock = threading.RLock()

    def refresh(self, today: dt.date | None = None, force: bool = False) -> bool:
        """Перечитывает таблицу. True, если снимок обновился."""
        with self._lock:
            if settings.freeze:
                self._freeze()
                return False
            if self._crashes >= CRASHES_TO_ALERT:
                self._fail(
                    f"служба {self._crashes} раза подряд умерла посреди захода — "
                    "скорее всего, разбору не хватило памяти (MemoryMax)",
                    kind="crash",
                )
            self._mark_running()
            try:
                return self._refresh(today or _today(), force)
            except Exception as exc:
                # Страховка на весь заход. Исключение, которое никто ниже не
                # ждал, раньше уходило в планировщик: заход молча не
                # случался, status оставался ok, checked свежел, тревоги не
                # было — и так на каждом заходе (второй аудит, К2).
                log.exception("заход обновления упал")
                self._fail(
                    f"служба споткнулась при обновлении: {type(exc).__name__}: "
                    f"{sheet_index._hide_key(exc)}",
                    kind="error",
                    public=f"служба споткнулась при обновлении: {type(exc).__name__}",
                )
                return False
            finally:
                self._clear_running()

    def _load_seen_titles(self) -> set[str] | None:
        try:
            data = json.loads(self._seen_path.read_text("utf-8"))
        except (OSError, ValueError):
            return None
        return set(data) if isinstance(data, list) else None

    def _save_seen_titles(self) -> None:
        try:
            tmp = self._seen_path.with_suffix(".tmp")
            names = sorted(self._seen_titles or ())
            tmp.write_text(json.dumps(names, ensure_ascii=False), "utf-8")
            tmp.replace(self._seen_path)
        except OSError as exc:
            log.warning("имена листов для слежки не записались: %s", exc)

    def _freeze(self) -> None:
        """Заморожено владельцем: прежний снимок, stale, в сеть не ходим."""
        self.checked_at = dt.datetime.now(dt.timezone.utc)
        if self.failing_since is None:
            self.failing_since = self.checked_at
            log.warning("служба заморожена (WHENSCLASS_FREEZE) — в таблицу не хожу")
        self._fetch_only = False
        self.last_error = "заморожено владельцем: отдаётся прежнее расписание"
        self.status = self._visible_status()
        self._save_failing()

    def restore_status(self) -> None:
        """Состояние после перезапуска: какое было — такое и есть."""
        self.status = self._visible_status()

    def _refresh(self, today: dt.date, force: bool, retried: bool = False) -> bool:
        self.checked_at = dt.datetime.now(dt.timezone.utc)
        texts: list[tuple[str, str | None, str, str]] = []
        try:
            if self._sheets is None or force:
                # С понедельника, как окно приложения: иначе в воскресенье на
                # стыке листов в снимке оставался только будущий лист, и вся
                # прожитая неделя пропадала с экрана (третий аудит, М25
                # прогона 1).
                monday = today - dt.timedelta(days=today.weekday())
                self._sheets = sheet_index.resolve_window(
                    monday, settings.window_days + today.weekday(), self.state_dir, deep=force
                )

            for title, gid in self._sheets:
                try:
                    text = gsheets.fetch_sheet_csv(gid=gid, title=title or None)
                except httpx.HTTPStatusError as exc:
                    # 400 — мёртвый gid (проверено curl), 404 — на всякий
                    # случай. Остальные 4xx (429, 403) — Google на минуту,
                    # это обычный сетевой сбой: индекс цел, тревога подождёт.
                    if exc.response.status_code in (400, 404) and not retried:
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
                # Не изменился ни один лист — перерисовывать нечего. Но если
                # сегодня уже за краем прежнего снимка, набор листов пора
                # пересобрать — как и при разборе ниже.
                coverage = self.store.snapshot.coverage if self.store.snapshot else None
                if coverage and not (coverage[0] <= today <= coverage[1]):
                    self._sheets = None
                self.status = "ok"
                self._recovered()
                # Лист тот же — и состав id тот же: переименование, которое
                # ждёт подтверждения, дождалось его временем (второй аудит, В7).
                try:
                    self.renames.tick()
                except Exception as exc:
                    log.warning("книга переименований не подтвердилась: %s", exc)
                return False

            parsed = self._parse(texts, today)
            if parsed is None:
                self._fail(f"не нашёл лист на {today}: набор листов пуст", kind="sheet")
                return False
            snapshot, teachers = parsed
            _check_today_kept(self.store.snapshot, snapshot, today)
        except SourceFormatChanged as exc:
            # Самый опасный случай: таблицу переделали. Держим прежнее —
            # а отвергнутый лист кладём в архив вместе с причиной: разбирать
            # инцидент по листу, который колледж уже переправил, нельзя.
            # Отвергнутым в архив — только упавший лист; исправный сосед по
            # окну ложится обычной копией, а не с чужой причиной (третий аудит,
            # М39 прогона 2).
            failed = getattr(exc, "title", None)
            for title, gid, text, digest in texts:
                reason = str(exc) if failed is None or title == failed else None
                history.archive(self.state_dir, gid, text, digest, rejected=reason)
            self._fail(f"формат таблицы изменился: {exc}", kind="format")
            return False
        except sheet_index.SheetNotFound as exc:
            # Лист прочитан, но отвергнут воротами «пропал сегодняшний день»:
            # это такая же версия листа, как отвергнутая по формату, и
            # разбирать инцидент без неё нечем (второй аудит, М20).
            for _, gid, text, digest in texts:
                history.archive(self.state_dir, gid, text, digest, rejected=f"не нашёл лист на {today}: {exc}")
            self._fail(f"не нашёл лист на {today}: {exc}", kind="sheet")
            return False
        except gsheets.SheetClosed as exc:
            # Страница входа вместо CSV: таблицу закрыли. Это не формат (смотреть
            # разборщик незачем) и не сеть (само не пройдёт) — сказать сразу и
            # прямо (второй аудит, М7).
            self._fail(f"таблица колледжа закрыта: {exc}", kind="closed")
            return False
        except (httpx.HTTPError, OSError) as exc:
            # Сетью считается только сеть. Раньше здесь стоял голый Exception,
            # и любая ошибка разбора выглядела «таблица не прочиталась» — с
            # тревогой через полчаса и без архива; всё прочее теперь ловит
            # страховка в refresh() и говорит сразу.
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
        try:
            previous_teachers = self.store.teachers
        except Exception as exc:
            # Индекс прежнего снимка нужен только книге переименований.
            log.warning("индекс преподавателей прежнего снимка не собрался: %s", exc)
            previous_teachers = None
        try:
            self.store.put(snapshot, dt.datetime.now(dt.timezone.utc), teachers=teachers)
        except OSError as exc:
            # Диск: в памяти снимок уже свежий, телефоны его получат, но
            # перезапуск поднимет прежний. Хеши не запоминаем — следующий
            # заход попробует записать снова. Сказать человеку — сразу, но
            # один раз: дальше обычное окно тишины, а не полсотни сообщений
            # в сутки (второй аудит, В16).
            log.error("снимок не записался на диск: %s", exc)
            sent = alerts.notify(
                "disk",
                f"Снимок не записался на диск: {type(exc).__name__}. В памяти свежее "
                "расписание, после перезапуска поднимется прежнее.",
                force=not self._disk_alerted,
            )
            self._disk_alerted = self._disk_alerted or sent
            # Лист разобран и принят — прежний сбой закрыт, хоть снимок и
            # не лёг на диск: иначе failing_since жил дальше, «починилось»
            # не уходило, а первый чих сети сразу давал stale (третий аудит,
            # М10 прогона 1). О диске — своя тревога выше.
            self.status = "ok"
            self._recovered()
            return True
        if self._disk_alerted:
            self._disk_alerted = False
            alerts.forget("disk")
            alerts.notify(
                "disk-ok", "Снимок снова записывается на диск.", force=True, good=True
            )
        self._source_hashes = {title: digest for title, _, _, digest in texts}
        for title, gid, text, digest in texts:
            dropped = self._dropped.get(title)
            history.archive(self.state_dir, gid, text, digest, rejected=dropped)
        self.status = "ok"
        self._recovered()
        if self._suspicions:
            # Лист принят, но похож на сдвиг, которого признак соседа не увидел
            # (третий аудит, М41 прогона 2). Раз в шесть часов, как всё.
            alerts.notify(
                "strangers",
                "Лист принят, но: " + "; ".join(self._suspicions[:2])
                + ". Присмотреться — не сдвиг ли колонок (docs/deploy.md, «Когда что-то "
                "не так»).",
            )
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
        if not settings.sheets_api_key or settings.freeze:
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
            # Самый первый взгляд — файла ещё нет: запоминаем, с чем сравнивать.
            # Гнать поиск прямо сейчас незачем — служба и так обновилась на старте.
            self._seen_titles = titles
            self._save_seen_titles()
            return False

        fresh = (titles - self._seen_titles) | (set(self._retry_titles) & titles)
        self._seen_titles = titles
        self._save_seen_titles()
        if not fresh:
            return False

        log.info("в книге появился лист: %s — ищу заново", ", ".join(sorted(fresh)))
        changed = self.refresh(force=True)
        # Лист «групп», заведённый пустым, поиск отвергает, а заполненный
        # позже служба узнавала только ночью (третий аудит, М14 прогона 1):
        # такие имена перепроверяются на следующих слежках, до RETRY_LOOKS раз.
        known = set(sheet_index.SheetIndex(self.state_dir).known)
        for title in fresh:
            if title in known or not sheet_index.looks_like_groups(title):
                self._retry_titles.pop(title, None)
                continue
            tries = self._retry_titles.get(title, 0) + 1
            if tries > RETRY_LOOKS:
                self._retry_titles.pop(title, None)
            else:
                self._retry_titles[title] = tries
        return changed

    def _parse(self, texts, today: dt.date):
        """Тексты листов -> (снимок, индекс преподавателей) или None.

        Индекс строится здесь, до записи снимка, а не лениво после неё: снимок,
        на котором индекс не собирается, раньше успевал лечь на диск и
        замораживал службу — даже перезапуск поднимал его снова (К2).
        ValueError разборщика — это тоже формат: лист отвергнут, а не «сеть»
        с тревогой через полчаса и без архива (В26).
        """
        self._dropped = {}
        self._suspicions: list[str] = []
        try:
            snapshot = None
            for title, gid, text, _ in texts:
                name = title or f"gid {gid}"
                try:
                    current = parse_export(text, name, gid, limits=_limits(), around=today)
                    # Сдвиг ищется по порядку колонок, а он у каждого листа
                    # свой: проверять лист отдельно, до склейки. Историю даёт
                    # прежний снимок и — для следующего листа — только что
                    # разобранный текущий (второй аудит, М9).
                    seed = shift_seed(self.store.snapshot, current)
                    if snapshot is not None:
                        for group, traces in shift_seed(snapshot, current).items():
                            seed.setdefault(group, set()).update(traces)
                    self._suspicions += _check_shift(
                        current, seed=seed, previous=self.store.snapshot
                    )
                    _check_lost_names(self.store.snapshot, current, gid)
                except SheetTooSmall as exc:
                    if snapshot is None:
                        raise SheetRejected(title, f"лист {name!r}: {exc}") from exc
                    # Следующий лист колледж только начал: без него окно
                    # короче, и за краем — «ещё не опубликовано». Раньше его
                    # отказ валил весь набор, и правки сегодняшнего листа не
                    # доходили до телефонов (третий аудит, В18 прогона 1).
                    log.warning(
                        "следующий лист %r ещё недописан (%s) — пока без него", name, exc
                    )
                    self._dropped[title] = f"следующий лист ещё недописан: {exc}"
                    continue
                except SourceFormatChanged as exc:
                    # Имя листа — в тексте: в окне их бывает два.
                    raise SheetRejected(title, f"лист {name!r}: {exc}") from exc
                snapshot = current if snapshot is None else snapshot.merged_with(current)
            if snapshot is None:
                return None
            _check_group_drop(self.store.snapshot, snapshot)
            teachers = build_index(snapshot)
        except ValueError as exc:
            raise SourceFormatChanged(f"разбор споткнулся о значение: {exc}") from exc
        return snapshot, teachers

    def _visible_status(self, now: dt.datetime | None = None) -> str:
        if self.store.snapshot is None:
            return "empty"
        if self.failing_since is None:
            return "ok"
        now = now or dt.datetime.now(dt.timezone.utc)
        if self._fetch_only and now - self.failing_since < FETCH_GRACE:
            # Чих Google: владельцу о нём не говорят полчаса, и телефонам
            # незачем полчаса показывать «сбой на нашем сервере» (М25).
            return "ok"
        return "stale"

    def _fail(self, message: str, kind: str = "error", public: str | None = None) -> None:
        now = dt.datetime.now(dt.timezone.utc)
        first = self.failing_since is None
        if first:
            self.failing_since = now
            self._alerted = False
            self._fetch_only = True
        self._fetch_only = self._fetch_only and kind == "fetch"
        # Сеть отсчитывается от первого сетевого отказа подряд, а не от начала
        # всего сбоя: один таймаут посреди отказа по формату сразу уходил
        # тревогой «таблица не прочиталась» и подменял err — а по runbook это
        # «чинится само» (третий аудит, В14 прогона 1).
        self._fetch_since = (self._fetch_since or now) if kind == "fetch" else None
        if kind != "fetch" or self._fetch_only:
            self.last_error = public or message
        self.status = self._visible_status(now)
        self._sheets = None
        log.error("%s (состояние: %s)", message, self.status)

        # Формат и поиск листа сами не чинятся — говорить сразу. Сеть и
        # Google чинятся к следующему заходу: о них — только если сеть лежит
        # дольше получаса, иначе каждый чих Google будит человека дважды.
        if kind != "fetch" or now - self._fetch_since >= FETCH_GRACE:
            sent = alerts.notify(
                kind,
                f"{message}. Состояние: {self.status}, "
                + ("отдаётся прежнее расписание." if self.status == "stale"
                   else "отдавать нечего.")
                + ("" if first else
                   f" Лежим с {self.failing_since.astimezone(_zone()):%d.%m %H:%M}."),
                force=not self._alerted,
            )
            if sent:
                self._alerted = True
                self._alerted_kinds[kind] = now
        self._save_failing()

    def _recovered(self) -> None:
        """Обновление удалось после сбоя: сказать, что и сколько лежало."""
        since = self.failing_since
        if since is None:
            return
        lying = dt.datetime.now(dt.timezone.utc) - since
        if self._alerted:
            alerts.notify(
                "recovered",
                f"Расписание снова обновляется. Лежало {_lying(lying)}, "
                f"с {since.astimezone(_zone()):%d.%m %H:%M}.",
                force=True, good=True,
            )
        for kind in FAIL_KINDS:
            alerts.forget(kind)
        self.failing_since, self.last_error, self._alerted = None, None, False
        self._alerted_kinds = {}
        self._fetch_since = None
        self._fetch_only = True
        self._save_failing()

    def _save_failing(self) -> None:
        try:
            if self.failing_since is None:
                self._failing_path.unlink(missing_ok=True)
                return
            tmp = self._failing_path.with_suffix(".tmp")
            tmp.write_text(json.dumps({
                "since": self.failing_since.isoformat(),
                "error": self.last_error,
                "fetch_only": self._fetch_only,
                "alerted": self._alerted,
                # Когда ушла тревога — по каждому виду: окно тишины живёт в
                # памяти, и перезапуск посреди сбоя повторял её сразу (М23), а
                # с одним «последним видом» — тревогу о формате после сетевой
                # (третий аудит, В14 прогона 1).
                "alerted_kinds": {k: at.isoformat() for k, at in self._alerted_kinds.items()},
                "fetch_since": self._fetch_since.isoformat() if self._fetch_since else None,
            }, ensure_ascii=False), "utf-8")
            tmp.replace(self._failing_path)
        except OSError as exc:
            log.warning("состояние сбоя не записалось: %s", exc)

    def _load_failing(self) -> None:
        self._alerted_kinds: dict[str, dt.datetime] = {}
        self._fetch_since: dt.datetime | None = None
        try:
            data = json.loads(self._failing_path.read_text("utf-8"))
            self.failing_since = dt.datetime.fromisoformat(data["since"])
            self.last_error = data.get("error")
            self._alerted = bool(data.get("alerted"))
            # Старые файлы без признака — сбой не сетевой: показывать как был.
            self._fetch_only = bool(data.get("fetch_only", False))
            kinds = dict(data.get("alerted_kinds") or {})
            if data.get("alerted_at") and data.get("alerted_kind"):
                # Файл до 26.09.2026: одна тревога.
                kinds.setdefault(data["alerted_kind"], data["alerted_at"])
            for kind, at in kinds.items():
                self._alerted_kinds[kind] = dt.datetime.fromisoformat(at)
                alerts.remember(kind, self._alerted_kinds[kind])
            if data.get("fetch_since"):
                self._fetch_since = dt.datetime.fromisoformat(data["fetch_since"])
        except (OSError, ValueError, KeyError, TypeError):
            self.failing_since, self.last_error, self._alerted = None, None, False

    def _mark_running(self) -> None:
        try:
            self._running_path.write_text(str(self._crashes + 1), "utf-8")
        except OSError as exc:
            log.warning("отметка захода не записалась: %s", exc)

    def _clear_running(self) -> None:
        self._crashes = 0
        try:
            self._running_path.unlink(missing_ok=True)
        except OSError as exc:
            log.warning("отметка захода не снялась: %s", exc)


class SheetRejected(SourceFormatChanged):
    """Отказ, который знает, какой лист окна его дал."""

    def __init__(self, title: str | None, message: str):
        super().__init__(message)
        self.title = title


# Сколько раз слежка (раз в полчаса) перепроверяет новый лист «групп», который
# поиск ещё не принял: шесть часов (М14).
RETRY_LOOKS = 12
# Сколько сетевой сбой держим за чих: ни тревоги, ни stale наружу (М25).
FETCH_GRACE = dt.timedelta(minutes=30)
# После скольких заходов подряд, умерших посреди разбора, — тревога (М36).
CRASHES_TO_ALERT = 2
# Виды тревог о сбое: все забываются, когда обновление снова удалось.
FAIL_KINDS = ("format", "sheet", "fetch", "error", "closed", "crash")


# Набор групп упал больше чем на 30 % между двумя снимками одного и того же
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


def _check_lost_names(previous, current, gid: str | None) -> None:
    """Отказ, если группа пропала, а её колонка на месте и с парами.

    Так выглядит стёртое или испорченное имя в главном заголовке, которого
    нет и в повторных: блок разбирается безымянным, и группа молча уходила в
    404 при ok (третий аудит, В17 прогона 1). Пропажа одной группы не
    доходит до `_check_group_drop`, а колонка с парами — не убранная группа.
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
            raise SourceFormatChanged(
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


def _lying(delta: dt.timedelta) -> str:
    """Сколько лежали, по-человечески: учебная тревога 14 сентября 2026
    длилась две минуты и отчиталась «лежало 0.0 ч»."""
    minutes = int(delta.total_seconds() // 60)
    if minutes < 60:
        return f"{minutes} мин"
    return f"{minutes / 60:.1f} ч".replace(".", ",")


def _limits() -> Limits:
    return Limits(
        min_groups=settings.min_groups,
        min_dates=settings.min_dates,
        min_lessons=settings.min_lessons,
        max_gap_days=settings.max_gap_days,
        max_days_ahead=settings.max_days_ahead,
    )


def _zone() -> zoneinfo.ZoneInfo:
    return zoneinfo.ZoneInfo(settings.timezone)


def state_dir() -> pathlib.Path:
    return pathlib.Path(settings.state_dir).resolve()
