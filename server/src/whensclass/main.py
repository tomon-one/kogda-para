"""Точка входа сервиса.

    uvicorn whensclass.main:app --host 127.0.0.1 --port 8081

Ровно один рабочий процесс: планировщик и разобранный снимок живут в памяти,
делить их между процессами незачем.
"""

from __future__ import annotations

import datetime as dt
import logging
import re
import zoneinfo
from contextlib import asynccontextmanager

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from fastapi import FastAPI
from fastapi.middleware.gzip import GZipMiddleware

from .api.routes import router
from .config import settings
from .service.bells import load_bells
from .service.refresher import Refresher, state_dir
from .storage.snapshot_store import SnapshotStore

log = logging.getLogger(__name__)


def before_each_lesson(minutes: int) -> list[tuple[str, tuple[int, int]]]:
    """Во сколько обновляться перед каждой парой: за `minutes` до звонка."""
    out: list[tuple[str, tuple[int, int]]] = []
    for number, times in sorted(load_bells().items()):
        start = times[0] if times else None
        if not start:
            continue
        try:
            hour, minute = (int(x) for x in start.split(":", 1))
        except ValueError:
            log.warning("не понял время начала %r у пары %s", start, number)
            continue
        total = hour * 60 + minute - minutes
        if total < 0:
            continue
        out.append((number, (total // 60, total % 60)))
    return out


@asynccontextmanager
async def lifespan(app: FastAPI):
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    # httpx пишет каждый запрос целиком, вместе со строкой параметров, а в
    # обращении к Sheets API туда входит ключ. Журнал службы лежит на общей
    # машине и уезжает в journald надолго. Оставляем от httpx предупреждения.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    directory = state_dir()
    directory.mkdir(parents=True, exist_ok=True)

    if settings.sheet_title and not settings.sheet_gid:
        log.error(
            "WHENSCLASS_SHEET_TITLE задан без WHENSCLASS_SHEET_GID: с 14 сентября 2026 "
            "лист читается только по gid, по имени служба его не найдёт"
        )

    if settings.exit_ssh and not re.fullmatch(r"[^@\s]+@[^:\s]+:\d+", settings.exit_ssh):
        log.error(
            "WHENSCLASS_EXIT_SSH=%r — не вида пользователь@хост:порт: запасной путь к Google "
            "через exit работать не будет", settings.exit_ssh,
        )

    store = SnapshotStore(directory)
    refresher = Refresher(store, directory)
    app.state.store = store
    app.state.refresher = refresher

    if store.load():
        log.info("поднял снимок с диска: лист %r", store.snapshot.sheet_title)
        # Лежали до перезапуска — лежим и после: снимок тот же прежний.
        refresher.restore_status()

    zone = zoneinfo.ZoneInfo(settings.timezone)
    scheduler = BackgroundScheduler(timezone=zone)
    first, last = settings.active_hours
    scheduler.add_job(
        refresher.refresh,
        CronTrigger(hour=f"{first}-{last}", minute=f"*/{settings.refresh_minutes}"),
        id="refresh",
        max_instances=1,
        coalesce=True,
    )
    # Ночью ищем лист заново: как раз тогда появляется следующая половина месяца.
    scheduler.add_job(
        lambda: refresher.refresh(force=True),
        CronTrigger(hour=3, minute=30),
        id="reindex",
    )

    # И между делом поглядываем, не появился ли лист новее. Ночного поиска
    # мало: неделю выкладывают среди дня, а узнать об этом лучше в тот же
    # час — переход между листами это самое опасное место в службе.
    # С ключом такая проверка стоит одного маленького запроса; без ключа
    # списка листов нет вовсе, поэтому и заводится только с ключом.
    if settings.sheets_api_key:
        scheduler.add_job(
            refresher.look_for_new_sheet,
            CronTrigger(hour=f"{first}-{last}", minute="*/30"),
            id="watch-sheets",
            max_instances=1,
            coalesce=True,
        )

    # Отдельный заход перед каждой парой: расписание правят и за десять минут
    # до звонка, а как раз в этот момент в него и смотрят.
    for number, moment in before_each_lesson(settings.refresh_before_lesson_minutes):
        scheduler.add_job(
            refresher.refresh,
            CronTrigger(hour=moment[0], minute=moment[1]),
            id=f"before-lesson-{number}",
            max_instances=1,
            coalesce=True,
        )
    scheduler.start()

    # Первый заход сразу, чтобы сервис не стоял пустым до ближайшего часа.
    scheduler.add_job(refresher.refresh, "date",
                      run_date=dt.datetime.now(zone) + dt.timedelta(seconds=1))

    try:
        yield
    finally:
        scheduler.shutdown(wait=False)


app = FastAPI(
    title="WhensClass",
    description="Расписание НГОК компактным JSON для виджета на телефоне",
    version="0.1.0",
    lifespan=lifespan,
    # Автодокументация наружу не нужна: контракт — docs/api.md, а /docs и
    # /openapi.json отвечали всем и расписывали поверхность службы.
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)
# Ответы небольшие, но телефон часто сидит на мобильном интернете.
app.add_middleware(GZipMiddleware, minimum_size=500)
app.include_router(router)
