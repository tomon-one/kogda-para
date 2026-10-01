"""Доставка уведомлений сайта: своя очередь у каждой службы рассылки, один
повтор, снятие подписок, которые не доставляются.

`Delivery` — часть `Push`: подписки, замок и счётчики заводит Push.__init__.
"""

from __future__ import annotations

import concurrent.futures
import datetime as dt
import heapq
import logging
import threading
import time
from typing import NamedTuple

import httpx

from . import webpush
from .subscription import _host

log = logging.getLogger(__name__)

# Одна повторная попытка на 429 и 5xx: через Retry-After, но не дольше двух минут.
RETRY_AFTER = 30
RETRY_MAX = 120
# Повторы ждут в одной очереди с одним потоком (`_Delayed`); больше стольких —
# не встают.
MAX_PENDING_RETRIES = 2000
# Соединение со службой рассылки — не дольше трёх секунд: пока Google не
# отвечает, его подписки держат очередь.
TIMEOUT = httpx.Timeout(10.0, connect=3.0)
WORKERS_PER_SERVICE = 4
# Подписку, которая не доставляется столько раз подряд, служба снимает — если
# та же служба рассылки за это время доставляла другим: сбой всей службы
# или нашего ключа подписки не стирает.
DROP_AFTER_FAILS = 5


def family(endpoint: str) -> str:
    """Служба рассылки: у каждой своя очередь."""
    host = _host(endpoint)
    for name, suffix in (("google", "googleapis.com"), ("mozilla", "mozilla.com"),
                         ("apple", "apple.com"), ("microsoft", "windows.com")):
        if host == suffix or host.endswith("." + suffix):
            return name
    return host


class Job(NamedTuple):
    sub: dict
    message: dict
    ttl: int
    # Позже этого (секунды эпохи) повторять незачем: пара уже началась.
    deadline: float | None = None


class Delivery:
    """Рассылка подписчикам; методы `Push`."""

    def _send(self, job: Job, attempt: int = 0) -> None:
        """Одна доставка. Любое исключение — в журнал, а не в Future, которую
        никто не читает."""
        try:
            self._deliver(job, attempt)
        except Exception as exc:  # noqa: BLE001 — страховка потока пула
            log.warning("уведомление не ушло (%s): %s", _host(job.sub["endpoint"]),
                        type(exc).__name__)
            self._failed(job.sub, "error", type(exc).__name__)

    def _deliver(self, job: Job, attempt: int) -> None:
        if self.vapid is None:
            return
        sub = job.sub
        kind = job.message["notification"]["data"]["t"]
        if job.deadline is not None and time.time() >= job.deadline:
            # Очередь продержала напоминание до начала пары — оно уже не нужно.
            log.info("напоминание опоздало к началу пары (%s) — не шлю", _host(sub["endpoint"]))
            return
        try:
            result = webpush.send(
                self._client, self.vapid, sub["endpoint"], sub["p256dh"], sub["auth"],
                job.message, job.ttl, urgency="high" if kind == "lesson" else "normal",
            )
        except webpush.TooLarge as exc:
            # Наша ошибка, не подписки: не снимаем и не повторяем.
            log.warning("уведомление не влезло (%s): %s", kind, exc)
            return
        except ValueError as exc:
            # Ключи подписки не расшифровываются (не точка кривой) — до сети:
            # доставить такую нельзя никогда.
            self.unsubscribe(sub["endpoint"], why=f"ключи не годятся: {exc}")
            return
        except httpx.HTTPError as exc:
            # Адрес — только хост: путь подписки и есть её секрет.
            log.warning("уведомление не ушло (%s): %s", _host(sub["endpoint"]), type(exc).__name__)
            if not self._retry(job, attempt, None):
                self._failed(sub, "dns" if _no_such_host(exc) else "net", type(exc).__name__)
            return
        if result.status < 300:
            self._succeeded(sub)
            return
        said = f"{result.status}" + (f" {result.reason}" if result.reason else "")
        if result.status in webpush.GONE or result.reason == "VapidPkHashMismatch":
            # Подписки нет или она под другим ключом — слать дальше незачем;
            # страница при открытии подпишется заново.
            self.unsubscribe(sub["endpoint"], why=f"служба рассылки ответила {said}")
            return
        log.warning("служба рассылки %s ответила %s (%s)", _host(sub["endpoint"]), said, kind)
        if result.status in webpush.RETRY and self._retry(job, attempt, result.retry_after):
            return
        self._failed(sub, "status", said)

    def _succeeded(self, sub: dict) -> None:
        name = family(sub["endpoint"])
        with self._lock:
            self._delivered[name] = time.monotonic()
            self._fails.pop(sub["endpoint"], None)
            self._stats.setdefault(name, [0, 0, None])[0] += 1
            # Доставлялось хоть раз — настоящая подписка: не снимается ни как
            # мусор под потолком, ни за несуществующий адрес.
            record = self._subs.get(sub["endpoint"])
            if record is not None and not record.get("ok"):
                record["ok"] = dt.date.today().isoformat()
                self._save()

    def _failed(self, sub: dict, how: str, said: str) -> None:
        """Доставка не удалась окончательно (повторы кончились или не нужны)."""
        endpoint, name = sub["endpoint"], family(sub["endpoint"])
        now = time.monotonic()
        with self._lock:
            stat = self._stats.setdefault(name, [0, 0, None])
            stat[1] += 1
            stat[2] = said
            count, since = self._fails.get(endpoint, (0, now))
            count += 1
            self._fails[endpoint] = (count, since)
            # Адреса нет в DNS, и ни разу не доставлялось — выдуманный поддомен
            # службы рассылки. Снимаем и без доставок другим: у Microsoft их
            # может не быть вовсе.
            never = not self._subs.get(endpoint, sub).get("ok")
            dead = count >= DROP_AFTER_FAILS and (
                self._delivered.get(name, -1.0) > since or (how == "dns" and never)
            )
        if dead:
            self.unsubscribe(endpoint, why=f"не доставляется {count} раз подряд: {said}")

    def _retry(self, job: Job, attempt: int, after: int | None) -> bool:
        """Одна повторная попытка; напоминание — только пока пара не началась.
        True — повтор встал в очередь."""
        if attempt > 0:
            return False
        delay = min(max(after or RETRY_AFTER, 1), RETRY_MAX)
        if job.deadline is not None and time.time() + delay >= job.deadline:
            return False
        again = job._replace(ttl=max(60, job.ttl - delay))
        if self._later(delay, lambda: self._submit(again, attempt + 1)) is False:
            log.warning("очередь повторов полна (%d) — повтор не встал", MAX_PENDING_RETRIES)
            return False
        return True

    def _submit(self, job: Job, attempt: int = 0) -> None:
        name = family(job.sub["endpoint"])
        with self._lock:
            pool = self._pools.get(name)
            if pool is None:
                pool = concurrent.futures.ThreadPoolExecutor(
                    max_workers=WORKERS_PER_SERVICE, thread_name_prefix=f"push-{name}"[:15]
                )
                self._pools[name] = pool
        pool.submit(self._send, job, attempt)

    def _dispatch(self, jobs: list[Job]) -> None:
        for job in jobs:
            self._submit(job)


class _Delayed:
    """Отложенные повторы — одна очередь и один поток, а не поток ОС на
    каждый повтор: тысячи неудач за минуту упрутся в MemoryMax."""

    def __init__(self, limit: int):
        self._heap: list[tuple[float, int, object]] = []
        self._cond = threading.Condition()
        self._seq = 0
        self._limit = limit
        self._thread: threading.Thread | None = None

    def add(self, delay: float, action) -> bool:
        with self._cond:
            if len(self._heap) >= self._limit:
                return False
            self._seq += 1
            heapq.heappush(self._heap, (time.monotonic() + delay, self._seq, action))
            if self._thread is None:
                self._thread = threading.Thread(target=self._run, name="push-retry", daemon=True)
                self._thread.start()
            self._cond.notify()
        return True

    def _run(self) -> None:
        while True:
            with self._cond:
                while not self._heap:
                    self._cond.wait()
                due, _, action = self._heap[0]
                wait = due - time.monotonic()
                if wait > 0:
                    self._cond.wait(wait)
                    continue
                heapq.heappop(self._heap)
            try:
                action()
            except Exception:  # noqa: BLE001
                log.exception("повтор уведомления упал")


def _no_such_host(exc: BaseException) -> bool:
    """Имени нет в DNS (EAI_NONAME), а не сбой сети или своего резолвера."""
    import socket

    seen = 0
    while exc is not None and seen < 6:
        if isinstance(exc, socket.gaierror) and exc.errno == socket.EAI_NONAME:
            return True
        exc = exc.__cause__ or exc.__context__
        seen += 1
    return False
