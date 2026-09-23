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


# Дальше года от сегодняшнего дня спрашивать незачем: приложение просит неделю
# от понедельника. А без границы ?from=9999-12-31 падал на переполнении даты с
# ответом 500 и трассировкой на 88 строк в общий журнал машины — любой
# прохожий мог забить journald соседям (второй аудит, В27).
MAX_FROM_DAYS = 366


def _bad_from(start: dt.date | None) -> Response | None:
    if start is None or abs((start - _today()).days) <= MAX_FROM_DAYS:
        return None
    return Response(
        status_code=422,
        content=json.dumps({"error": f"from дальше {MAX_FROM_DAYS} дней от сегодня"},
                           ensure_ascii=False),
        media_type=JSON,
    )


@router.api_route("/healthz", methods=["GET", "HEAD"])
def healthz(request: Request) -> Response:
    """200 — расписание есть и оно про сегодня; иначе 503 с причиной.

    Раньше 200 отвечался при любом снимке, и stale двое суток снаружи
    выглядел здоровьем. Теперь по коду ответа можно поставить любой
    сторожок — ему не надо разбирать /v1/meta.
    """
    store, refresher = _state(request)
    snapshot = store.snapshot
    today = _today()
    reason = None
    if snapshot is None:
        reason = "расписание ещё не загружено"
    elif refresher.status != "ok":
        reason = f"не обновляется: {refresher.last_error or refresher.status}"
    else:
        coverage = snapshot.coverage
        # Воскресений в листах нет — это не повод для тревоги.
        if today.weekday() != 6 and coverage and not (coverage[0] <= today <= coverage[1]):
            reason = f"лист покрывает {coverage[0]}—{coverage[1]}, а сегодня {today}"
    body = {"ok": reason is None, "status": refresher.status}
    if reason:
        body["reason"] = reason
    return Response(
        content=json.dumps(body, ensure_ascii=False),
        status_code=200 if reason is None else 503,
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
                        refresher.checked_at, today=_today(),
                        failing_since=refresher.failing_since, error=refresher.last_error)
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
    if (bad := _bad_from(start)) is not None:
        return bad
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
    if (bad := _bad_from(start)) is not None:
        return bad
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
