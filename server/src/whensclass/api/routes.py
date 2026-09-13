"""Эндпоинты API.

Тела ответов собираются заранее, в момент разбора таблицы, — запрос виджета
отдаёт готовые байты. ETag считается по этим байтам, поэтому повторный разбор
неизменившейся таблицы не заставляет телефон качать то же самое.
"""

from __future__ import annotations

import datetime as dt
import zoneinfo
import json

from fastapi import APIRouter, Query, Request, Response

from ..config import settings
from ..service.bells import load_bells
from ..service.refresher import state_dir
from .releases import latest_release
from .etag import etag_for, matches
from .payloads import (
    groups_payload,
    meta_payload,
    schedule_payload,
    teacher_payload,
    teachers_payload,
)

router = APIRouter()


def _today() -> dt.date:
    """Сегодня по часовому поясу колледжа: сервер может жить в UTC."""
    return dt.datetime.now(zoneinfo.ZoneInfo(settings.timezone)).date()

JSON = "application/json; charset=utf-8"
CACHE = "public, max-age=300, stale-while-revalidate=3600"


def _json_response(request: Request, body: dict, cache: bool = True) -> Response:
    raw = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    tag = etag_for(raw)
    if matches(request.headers.get("if-none-match"), tag):
        return Response(status_code=304, headers={"ETag": tag})
    headers = {"ETag": tag}
    if cache:
        headers["Cache-Control"] = CACHE
    return Response(content=raw, media_type=JSON, headers=headers)


def _state(request: Request):
    return request.app.state.store, request.app.state.refresher


@router.get("/healthz")
def healthz(request: Request) -> Response:
    store, refresher = _state(request)
    ok = store.snapshot is not None
    return Response(
        content=json.dumps({"ok": ok, "status": refresher.status}),
        status_code=200 if ok else 503,
        media_type=JSON,
    )


@router.get("/v1/app")
def app_release(request: Request) -> Response:
    """Последняя выложенная сборка приложения — чтобы оно знало об обновлении."""
    release = latest_release(state_dir())
    if release is None:
        return Response(status_code=404, content='{"error":"сборка не выложена"}',
                        media_type=JSON)

    # Схему берём не из запроса: служба намеренно не верит заголовкам от
    # nginx (--no-proxy-headers, иначе в журнал попадал адрес телефона), и
    # request.base_url для неё всегда http. Приложение должно качать по https:
    # ссылка уходит наружу и переживает нас в чатах.
    base = str(request.base_url).rstrip("/")
    if base.startswith("http://") and not base.startswith("http://127."):
        base = "https://" + base[len("http://"):]
    body = {
        "v": 1,
        "versionCode": release["versionCode"],
        "versionName": release["versionName"],
        "url": f"{base}/download/{release['file']}",
    }
    for key in ("size", "notes", "published"):
        if release.get(key):
            body[key] = release[key]
    return _json_response(request, body, cache=False)


@router.get("/v1/meta")
def meta(request: Request) -> Response:
    store, refresher = _state(request)
    if store.snapshot is None:
        return Response(status_code=503, content='{"error":"расписание ещё не загружено"}',
                        media_type=JSON)
    body = meta_payload(store.snapshot, store.generated, refresher.status,
                        refresher.checked_at, today=_today())
    return _json_response(request, body, cache=False)


@router.get("/v1/groups")
def groups(request: Request) -> Response:
    store, _ = _state(request)
    if store.snapshot is None:
        return Response(status_code=503, content='{"error":"расписание ещё не загружено"}',
                        media_type=JSON)
    return _json_response(request, groups_payload(store.snapshot, store.generated))


@router.get("/v1/teachers")
def teachers(request: Request) -> Response:
    """Список преподавателей — собирается из расписания групп."""
    store, _ = _state(request)
    if store.snapshot is None or store.teachers is None:
        return Response(status_code=503, content='{"error":"расписание ещё не загружено"}',
                        media_type=JSON)
    return _json_response(request, teachers_payload(store.teachers, store.generated))


@router.get("/v1/teacher/{teacher_id}")
def teacher(
    request: Request,
    teacher_id: str,
    start: dt.date | None = Query(None, alias="from"),
    days: int = Query(settings.default_days, ge=1, le=14),
) -> Response:
    store, refresher = _state(request)
    if store.snapshot is None or store.teachers is None:
        return Response(status_code=503, content='{"error":"расписание ещё не загружено"}',
                        media_type=JSON)

    def build(tid: str) -> dict | None:
        return teacher_payload(
            store.snapshot,
            store.teachers,
            tid,
            start or _today(),
            days,
            store.generated,
            bells=load_bells() or None,
        )

    body = build(teacher_id)
    if body is None:
        # Преподавателя переименовали в таблице — отвечаем за нового. В
        # ответе стоит его новый id, приложение перепишет выбор у себя.
        renamed = refresher.renames.teacher(teacher_id)
        if renamed:
            body = build(renamed)
    if body is None:
        return Response(status_code=404, content='{"error":"преподаватель не найден"}',
                        media_type=JSON)
    return _json_response(request, body)


@router.get("/v1/schedule/{group_id}")
def schedule(
    request: Request,
    group_id: str,
    start: dt.date | None = Query(None, alias="from"),
    days: int = Query(settings.default_days, ge=1, le=14),
) -> Response:
    store, refresher = _state(request)
    if store.snapshot is None:
        return Response(status_code=503, content='{"error":"расписание ещё не загружено"}',
                        media_type=JSON)

    def build(gid: str) -> dict | None:
        return schedule_payload(
            store.snapshot,
            gid,
            start or _today(),
            days,
            store.generated,
            bells=load_bells() or None,
        )

    body = build(group_id)
    if body is None:
        # Группу переименовали в таблице — отвечаем за новую. В ответе стоит
        # её новый id, приложение перепишет выбор у себя.
        renamed = refresher.renames.group(group_id)
        if renamed:
            body = build(renamed)
    if body is None:
        return Response(status_code=404, content='{"error":"группа не найдена"}',
                        media_type=JSON)
    return _json_response(request, body)
