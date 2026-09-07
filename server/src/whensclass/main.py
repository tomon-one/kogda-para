"""Точка входа сервиса.

    uvicorn whensclass.main:app --host 127.0.0.1 --port 8081

Ровно один рабочий процесс: планировщик и разобранный снимок живут в памяти,
делить их между процессами незачем.
"""

from __future__ import annotations

import datetime as dt
import logging
import zoneinfo
from contextlib import asynccontextmanager

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from fastapi import FastAPI
from fastapi.middleware.gzip import GZipMiddleware

from .api.routes import router
from .config import settings
from .service.refresher import Refresher, state_dir
from .storage.snapshot_store import SnapshotStore

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    directory = state_dir()
    directory.mkdir(parents=True, exist_ok=True)

    store = SnapshotStore(directory)
    refresher = Refresher(store, directory)
    app.state.store = store
    app.state.refresher = refresher

    if store.load():
        log.info("поднял снимок с диска: лист %r", store.snapshot.sheet_title)
        refresher.status = "ok"

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
)
# Ответы небольшие, но телефон часто сидит на мобильном интернете.
app.add_middleware(GZipMiddleware, minimum_size=500)
app.include_router(router)
