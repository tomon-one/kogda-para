"""Обновление расписания из таблицы.

Главное правило: неудачное обновление не должно делать пользователей слепыми.
Если таблица не читается или перестала соответствовать формату, прежний снимок
остаётся в силе, а сервис уходит в состояние stale — показать вчерашнее лучше,
чем показать пустоту.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import logging
import pathlib
import threading

import httpx

from ..config import settings
from ..domain.models import ChangedAgainstPrevious, SheetTooSmall, SourceFormatChanged
from ..domain.teachers import build_index
from ..parser.csv_schedule import Limits
from ..parser.export import parse_export
from ..parser.shift import check_shift, shift_seed
from ..sources import gsheets, sheet_index
from ..sources.sheet_memory import SheetIndex
from ..storage import history
from . import alerts
from .clock import _today
from .failing import CRASHES_TO_ALERT, Failing
from .gates import _check_days_emptied, _check_group_drop, _check_lost_names, _check_today_kept
from .sheet_watch import SheetWatch
from ..storage.snapshot_store import SnapshotStore

log = logging.getLogger(__name__)


class Refresher(Failing, SheetWatch):
    def __init__(self, store: SnapshotStore, state_dir: pathlib.Path):
        self.store = store
        self.state_dir = state_dir
        self.status = "empty"          # empty | ok | stale
        # «Само исправилось» — только если починилось без рук: без рычага и
        # без перезапуска (выкладки) посреди сбоя.
        self._started = dt.datetime.now(dt.timezone.utc)
        self._lever_used = False
        self.checked_at: dt.datetime | None = None
        # С какого момента и почему не обновляемся. Лежит на диске: «лежим с
        # четверга» должно пережить перезапуск при выкладке.
        self._failing_path = state_dir / "failing.json"
        self.failing_since: dt.datetime | None = None
        self.last_error: str | None = None
        self._alerted = False
        # Сбой из одних сетевых чихов: такой наружу не показываем, пока не
        # пролежал FETCH_GRACE, — как и тревогу владельцу.
        self._fetch_only = True
        # Тревога «диск» уже ушла: следующая — только после окна тишины
        # (окна помнятся между перезапусками).
        alerts.keep_in(state_dir / "alerts.json")
        self._disk_alerted = alerts.sent_recently("disk")
        self._load_failing()
        # Сколько заходов подряд начались и не кончились: служба умерла
        # посреди разбора (память — MemoryMax) и перезапустилась. Без счётчика
        # на диске перезапуск по systemd стирал бы это без следа.
        self._running_path = state_dir / "refresh.running"
        # Рычаг владельца: следующий лист принять без сверки с прежним
        # снимком (ChangedAgainstPrevious). Файл, а не настройка: разовый, его
        # стирает первый принятый лист (руководство по серверу, «Когда что-то
        # не так»). Убрать snapshot.json не поможет: load() поднимет
        # snapshot.prev.json.
        self._accept_path = state_dir / "accept-next"
        self._accepting = False
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
        # Имена листов, которые слежка уже видела в книге; None — ещё не
        # смотрела. На диске: иначе лист, заведённый, пока служба
        # перезапускалась, нашёлся бы только ночью.
        self._seen_path = state_dir / "seen_titles.json"
        self._seen_titles: set[str] | None = self._load_seen_titles()
        # Новые листы «групп», которые поиск пока не принял (пустые, одна
        # шапка): слежка перепроверяет их, а не забывает до ночи.
        self._retry_titles: dict[str, int] = {}
        # Задачи планировщика идут в разных потоках и в начале каждого часа
        # совпадают: обновление раз в 20 минут и проверка книги раз в 30.
        # Без замка они переписывают друг другу status и набор листов.
        # Замок повторный: слежка за книгой сама зовёт refresh.
        self._lock = threading.RLock()
        # Кому сказать, что снимок сменился: уведомления сайта (push.service).
        # (прежний снимок, его преподаватели, новый, его преподаватели, сегодня)
        self.on_update = None

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
                # Страховка на весь заход: неожиданное исключение, ушедшее в
                # планировщик, молча сорвало бы заход при status ok и без тревоги.
                log.exception("заход обновления упал")
                self._fail(
                    f"служба споткнулась при обновлении: {type(exc).__name__}: "
                    f"{sheet_index.hide_key(exc)}",
                    kind="error",
                    public=f"служба споткнулась при обновлении: {type(exc).__name__}",
                )
                return False
            finally:
                self._clear_running()

    def _accept_next(self) -> bool:
        """Поставлен ли рычаг accept-next. Старше ACCEPT_NEXT_TTL — стирается:
        забытый файл пропустил бы без сверки настоящий сдвиг через неделю."""
        try:
            age = dt.datetime.now().timestamp() - self._accept_path.stat().st_mtime
        except OSError:
            return False
        if age > ACCEPT_NEXT_TTL.total_seconds():
            log.warning("рычаг accept-next старше %s — не беру и стираю", ACCEPT_NEXT_TTL)
            self._accept_path.unlink(missing_ok=True)
            return False
        log.warning("рычаг accept-next: лист сверяется без прежнего снимка")
        return True

    def _accepted(self) -> None:
        """Лист принят — разовый рычаг снят."""
        if self._accepting:
            self._accepting = False
            self._lever_used = True
            try:
                self._accept_path.unlink(missing_ok=True)
            except OSError as exc:
                log.warning("рычаг accept-next не стёрся: %s", exc)
            log.warning("лист принят без сверки с прежним снимком (accept-next), рычаг снят")

    def _refresh(self, today: dt.date, force: bool, retried: bool = False) -> bool:
        self.checked_at = dt.datetime.now(dt.timezone.utc)
        texts: list[tuple[str, str | None, str, str]] = []
        try:
            if self._sheets is None or force:
                # С понедельника, как окно приложения: иначе в воскресенье на
                # стыке листов в снимке останется только будущий лист.
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
                        # Забываем его и ищем заново в том же заходе.
                        log.warning(
                            "лист %r по gid %s не отдаётся (%s) — забываю и ищу заново",
                            title, gid, exc.response.status_code,
                        )
                        SheetIndex(self.state_dir).forget(title)
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
                return False

            try:
                parsed = self._parse(texts, today)
            except NotYetPublished as exc:
                if self.store.snapshot is None:
                    self._fail(f"не нашёл лист на {today}: {exc}", kind="sheet")
                    return False
                log.info("окно — только недописанные будущие листы (%s): держу прежний снимок", exc)
                for title, gid, text, digest in texts:
                    history.archive(self.state_dir, gid, text, digest, rejected=self._dropped.get(title))
                self._source_hashes = {title: digest for title, _, _, digest in texts}
                self.status = "ok"
                self._recovered()
                return False
            if parsed is None:
                self._fail(f"не нашёл лист на {today}: набор листов пуст", kind="sheet")
                return False
            snapshot, teachers = parsed
            _check_today_kept(self.store.snapshot, snapshot, today)
        except SourceFormatChanged as exc:
            # Самый опасный случай: таблицу переделали. Держим прежнее, а
            # отвергнутый лист кладём в архив с причиной: колледж его скоро
            # переправит, а разбирать инцидент без него нечем. Исправный сосед
            # по окну ложится обычной копией, без чужой причины.
            failed = getattr(exc, "title", None)
            for title, gid, text, digest in texts:
                reason = str(exc) if failed is None or title == failed else None
                history.archive(self.state_dir, gid, text, digest, rejected=reason)
            # Отказ по сверке с прежним залипает, пока колледж не уберёт
            # правку: владельцу — чем его пропустить, если правка законная.
            lever = (
                ". Если по листу видно, что правка законная, — рычаг accept-next "
                "(руководство по серверу, «Когда что-то не так»)"
                if isinstance(exc, ChangedAgainstPrevious) or getattr(exc, "against_previous", False)
                else ""
            )
            self._fail(
                f"формат таблицы изменился: {exc}{lever}", kind="format",
                public=f"формат таблицы изменился: {exc}",
            )
            return False
        except sheet_index.SheetNotFound as exc:
            # Лист прочитан, но отвергнут воротами «пропал сегодняшний день»:
            # это такая же версия листа, как отвергнутая по формату, и
            # разбирать инцидент без неё нечем.
            for _, gid, text, digest in texts:
                history.archive(self.state_dir, gid, text, digest, rejected=f"не нашёл лист на {today}: {exc}")
            self._fail(f"не нашёл лист на {today}: {exc}", kind="sheet")
            return False
        except gsheets.SheetClosed as exc:
            # Страница входа вместо CSV: таблицу закрыли. Это не формат и не
            # сеть (само не пройдёт) — сказать сразу и прямо.
            self._fail(f"таблица колледжа закрыта: {exc}", kind="closed")
            return False
        except (httpx.HTTPError, OSError) as exc:
            # Сетью считается только сеть: прочее ловит страховка в refresh()
            # и говорит сразу, а не тревогой через полчаса.
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

        self._accepted()
        old = self.store.snapshot
        if snapshot.unread:
            snapshot.unread_since = (
                old.unread_since if old is not None and old.unread and old.unread_since
                else dt.datetime.now(dt.timezone.utc)
            )
        before = (old, self.store.teachers) if self.on_update else (None, None)
        try:
            self.store.put(snapshot, dt.datetime.now(dt.timezone.utc), teachers=teachers)
        except OSError as exc:
            # Диск: в памяти снимок уже свежий, телефоны его получат, но
            # перезапуск поднимет прежний. Хеши не запоминаем — следующий
            # заход попробует записать снова. Тревога — сразу, но один раз,
            # дальше обычное окно тишины.
            log.error("снимок не записался на диск: %s", exc)
            sent = alerts.notify(
                "disk",
                f"Снимок не записался на диск: {type(exc).__name__}. В памяти свежее "
                "расписание, после перезапуска поднимется прежнее.",
                force=not self._disk_alerted,
            )
            self._disk_alerted = self._disk_alerted or sent
            # Лист разобран и принят — прежний сбой закрыт, хоть снимок и
            # не лёг на диск: о диске своя тревога выше.
            self.status = "ok"
            self._recovered()
            self._tell_unread(old, snapshot)
            self._announce(before, snapshot, teachers, today)
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
        self._tell_unread(old, snapshot)
        self._announce(before, snapshot, teachers, today)
        if self._next_rejected:
            name, exc = self._next_rejected
            alerts.notify(
                "next-format",
                f"Следующий лист {name!r} отвергнут, окно пока без него: {exc}. Текущий "
                "принят. Когда тот станет нужен сегодня, отказ станет сбоем.",
            )
        if self._suspicions:
            # Лист принят, но похож на сдвиг, которого признак соседа не увидел.
            # Раз в шесть часов, как всё.
            alerts.notify(
                "strangers",
                "Лист принят, но: " + "; ".join(self._suspicions[:2])
                + ". Присмотреться (руководство по серверу, «Когда что-то не так»).",
            )
        log.info(
            "снимок обновлён: лист %r, %d групп, %d пар",
            snapshot.sheet_title, len(snapshot.groups), snapshot.total_lessons(),
        )
        return True

    def _tell_unread(self, old, snapshot) -> None:
        """Непрочитанные дни — владельцу сразу, как появились новые; прочитались
        все — что снова всё."""
        was = {(g, d) for g, days in (old.unread if old else {}).items() for d in days}
        now = {(g, d) for g, days in snapshot.unread.items() for d in days}
        if now:
            alerts.notify(
                "unread",
                "Лист принят, но не прочитаны дни: " + "; ".join(snapshot.unread_why[:3])
                + ". Там отдаётся прежнее, а где его нет — «ещё не опубликовано»; "
                "старые версии приложения видят сбой. Присмотреться (руководство по "
                "серверу, «Когда что-то не так»).",
                force=bool(now - was),
            )
        elif was:
            alerts.forget("unread")
            alerts.notify("unread-ok", "Все дни листа снова прочитаны.", force=True, good=True)

    def _announce(self, before, snapshot, teachers, today: dt.date) -> None:
        """Сказать подписчикам об изменениях; их поломка — не поломка обновления."""
        if self.on_update is None:
            return
        try:
            self.on_update(before[0], before[1], snapshot, teachers, today)
        except Exception:
            log.exception("уведомления об изменениях не посчитались")

    def _parse(self, texts, today: dt.date):
        """Тексты листов -> (снимок, индекс преподавателей) или None.

        Индекс строится здесь, до записи снимка: снимок, на котором индекс не
        собирается, не должен лечь на диск — его поднимал бы и перезапуск.
        ValueError разборщика — это тоже формат: лист отвергается и уходит в архив.
        """
        self._dropped = {}
        self._suspicions: list[str] = []
        # Следующий лист окна, отвергнутый по формату: (имя, отказ).
        self._next_rejected: tuple[str, SourceFormatChanged] | None = None
        self._accepting = self._accept_next()
        # С чем сверять: прежний принятый снимок, а по рычагу — ни с чем.
        # История групп для сдвига по соседям (shift_seed) берётся из него и
        # так: это не сверка, а знакомые предметы.
        previous = None if self._accepting else self.store.snapshot
        try:
            snapshot = None
            for title, gid, text, _ in texts:
                name = title or f"gid {gid}"
                try:
                    current = parse_export(text, name, gid, limits=_limits(), around=today)
                    _hold_unread(current, self.store.snapshot)
                    # Сдвиг ищется по порядку колонок, а он у каждого листа
                    # свой: проверять лист отдельно, до склейки. Историю даёт
                    # прежний снимок и — для следующего листа — только что
                    # разобранный текущий.
                    seed = shift_seed(self.store.snapshot, current)
                    if snapshot is not None:
                        for group, traces in shift_seed(snapshot, current).items():
                            seed.setdefault(group, set()).update(traces)
                    self._suspicions += check_shift(current, seed=seed, previous=previous)
                    _check_lost_names(previous, current, gid)
                except SheetTooSmall as exc:
                    future = exc.starts is not None and exc.starts > today
                    if snapshot is None and not future:
                        raise SheetRejected(title, f"лист {name!r}: {exc}") from exc
                    # Следующий лист колледж только начал: без него окно
                    # короче, и за краем — «ещё не опубликовано». Валить из-за
                    # него весь набор нельзя: правки сегодняшнего не дойдут.
                    log.warning(
                        "следующий лист %r ещё недописан (%s) — пока без него", name, exc
                    )
                    self._dropped[title] = f"следующий лист ещё недописан: {exc}"
                    continue
                except SourceFormatChanged as exc:
                    # Имя листа — в тексте: в окне их бывает два.
                    rejected = SheetRejected(
                        title, f"лист {name!r}: {exc}",
                        against_previous=isinstance(exc, ChangedAgainstPrevious),
                    )
                    if snapshot is None:
                        raise rejected from exc
                    # Черновик следующей вкладки с ошибкой формата выпадает из
                    # окна, как недописанный, а владельцу — тревога.
                    log.warning("следующий лист %r отвергнут (%s) — пока без него", name, exc)
                    self._dropped[title] = f"следующий лист отвергнут: {exc}"
                    self._next_rejected = (name, rejected)
                    continue
                snapshot = current if snapshot is None else snapshot.merged_with(current)
            if snapshot is None:
                if self._dropped and len(self._dropped) == len(texts):
                    # Все листы окна — недописанные будущие: каникулы, колледж
                    # заводит следующий лист. Это «ещё не выложено» при ok, а
                    # не сбой.
                    raise NotYetPublished(", ".join(self._dropped.values()))
                return None
            if self._next_rejected and snapshot.coverage and snapshot.coverage[1] < today:
                # Без отвергнутого листа окну нечем покрыть сегодня: он не
                # «следующий», а нужный — значит, отказ.
                raise self._next_rejected[1]
            dropped = _check_group_drop(previous, snapshot)
            if dropped:
                self._suspicions.append(dropped)
            _check_days_emptied(previous, snapshot, today, dropped=bool(self._dropped))
            teachers = build_index(snapshot)
        except ValueError as exc:
            raise SourceFormatChanged(f"разбор споткнулся о значение: {exc}") from exc
        return snapshot, teachers


class NotYetPublished(Exception):
    """В окне только листы, которые колледж ещё заводит, и все — в будущем."""


class SheetRejected(SourceFormatChanged):
    """Отказ, который знает, какой лист окна его дал."""

    def __init__(self, title: str | None, message: str, against_previous: bool = False):
        super().__init__(message)
        self.title = title
        self.against_previous = against_previous


ACCEPT_NEXT_TTL = dt.timedelta(hours=2)


def _hold_unread(snapshot, before) -> None:
    """Непрочитанные дни групп — прежними парами, если прежний снимок их знал.

    Не знал (неделя пришла впервые) — дня у группы нет, и край её расписания
    встаёт перед ним: «ещё не опубликовано», а не «пар нет».
    """
    for gid, days in snapshot.unread.items():
        for day in days:
            known = (
                before is not None and day in before.dates and gid in before.schedule
                and before.unread.get(gid, {}).get(day, True)
            )
            lessons = before.schedule.get(gid, {}).get(day) if known else None
            if lessons:
                snapshot.schedule.setdefault(gid, {})[day] = list(lessons)
            days[day] = bool(known)


def _limits() -> Limits:
    return Limits(
        min_groups=settings.min_groups,
        min_dates=settings.min_dates,
        min_lessons=settings.min_lessons,
        max_gap_days=settings.max_gap_days,
        max_days_ahead=settings.max_days_ahead,
    )


def state_dir() -> pathlib.Path:
    return pathlib.Path(settings.state_dir).resolve()
