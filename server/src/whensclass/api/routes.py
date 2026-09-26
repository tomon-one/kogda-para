"""Эндпоинты API.

Тела ответов собираются на каждый запрос из снимка в памяти — это десятки
миллисекунд. ETag считается по байтам тела, поэтому повторный разбор
неизменившейся таблицы не заставляет телефон качать то же самое.
"""

from __future__ import annotations

import datetime as dt
import zoneinfo
import json

from fastapi import APIRouter, Query, Request, Response

from ..config import settings
from ..domain.teachers import spelling_twin
from ..service.bells import load_bells
from ..service.refresher import state_dir
from .releases import latest_release
from .etag import etag_for, matches
from .payloads import (
    FILLED_SHARE,
    filling,
    groups_payload,
    meta_payload,
    schedule_payload,
    teacher_payload,
    teachers_payload,
)

# GET и HEAD: nginx пропускает оба, а сторожа по коду ответа ходят HEAD-ом —
# на @router.get служба отвечала им 405.
router = APIRouter()


# Лист кончился больше этого назад — каникулы (канарейка — так же).
HOLIDAY = dt.timedelta(days=7)


def _today() -> dt.date:
    """Сегодня по часовому поясу колледжа: сервер может жить в UTC."""
    return dt.datetime.now(zoneinfo.ZoneInfo(settings.timezone)).date()

JSON = "application/json; charset=utf-8"
# Хранить можно, отдавать без спроса — нет: каждый раз сверять ETag (304 и
# ноль байт, если ничего не изменилось). С max-age=300 OkHttp в телефоне пять
# минут отдавал расписание из кэша как свежее, и ручное обновление сразу после
# нового gen в /v1/meta приносило старое с галочкой «обновлено» (второй аудит,
# В14). Чинится здесь, а не в приложении: так и у уже установленных сборок.
CACHE = "public, no-cache"


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
    reason = note = None
    if snapshot is None:
        reason = "расписание ещё не загружено"
    elif refresher.status != "ok":
        reason = f"не обновляется: {refresher.last_error or refresher.status}"
    else:
        coverage = snapshot.coverage
        busy, fullest = filling(snapshot)
        if coverage and today > coverage[1] + HOLIDAY:
            # Лист кончился больше недели назад, а нового нет — каникулы, а не
            # авария: раньше 503 держался все учебные дни лета (третий аудит,
            # М88 прогона 2). 200, но с пометкой.
            note = f"лист кончился {coverage[1]}, нового нет — похоже, каникулы"
        # Воскресений в листах нет — это не повод для тревоги.
        elif today.weekday() != 6 and coverage and not (coverage[0] <= today <= coverage[1]):
            reason = f"лист покрывает {coverage[0]}—{coverage[1]}, а сегодня {today}"
        elif today in snapshot.dates and busy[today] < FILLED_SHARE * fullest:
            # Дата в листе есть, а пар почти ни у кого: каркас дат вписан
            # заранее, а неделю колледж не дописал. Раньше это был 200 при
            # «пар нет» у всех (третий аудит, В8 прогона 1). Так же выглядит
            # и праздник — пусть человек глянет.
            reason = (
                f"сегодня пары вписаны у {busy[today]} групп из {len(snapshot.groups)} — "
                "лист на сегодня не дописан или праздник"
            )
    body = {"ok": reason is None, "status": refresher.status}
    if reason:
        body["reason"] = reason
    elif note:
        body["note"] = note
    return Response(
        content=json.dumps(body, ensure_ascii=False),
        status_code=200 if reason is None else 503,
        media_type=JSON,
    )


@router.api_route("/v1/app", methods=["GET", "HEAD"])
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
    for key in ("size", "notes"):
        if release.get(key):
            body[key] = release[key]
    return _json_response(request, body, cache=False)


@router.api_route("/v1/meta", methods=["GET", "HEAD"])
def meta(request: Request) -> Response:
    store, refresher = _state(request)
    if store.snapshot is None:
        return Response(status_code=503, content='{"error":"расписание ещё не загружено"}',
                        media_type=JSON)
    body = meta_payload(store.snapshot, store.generated, refresher.status,
                        refresher.checked_at, today=_today(),
                        failing_since=refresher.failing_since, error=refresher.last_error)
    return _json_response(request, body, cache=False)


@router.api_route("/v1/groups", methods=["GET", "HEAD"])
def groups(request: Request) -> Response:
    store, _ = _state(request)
    if store.snapshot is None:
        return Response(status_code=503, content='{"error":"расписание ещё не загружено"}',
                        media_type=JSON)
    return _json_response(request, groups_payload(store.snapshot, store.generated))


@router.api_route("/v1/teachers", methods=["GET", "HEAD"])
def teachers(request: Request) -> Response:
    """Список преподавателей — собирается из расписания групп."""
    store, _ = _state(request)
    if store.snapshot is None or store.teachers is None:
        return Response(status_code=503, content='{"error":"расписание ещё не загружено"}',
                        media_type=JSON)
    return _json_response(request, teachers_payload(store.teachers, store.generated))


@router.api_route("/v1/teacher/{teacher_id}", methods=["GET", "HEAD"])
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
            today=_today(),
        )

    body = build(teacher_id)
    if body is None:
        # Преподавателя переименовали в таблице — отвечаем за нового. В
        # ответе стоит его новый id, приложение перепишет выбор у себя.
        renamed = (
            refresher.renames.teacher(teacher_id) or store.teachers.aliases.get(teacher_id)
        )
        if renamed:
            body = build(renamed)
    if body is None and teacher_id in getattr(refresher.renames, "pending_teachers", {}):
        # Книга ждёт подтверждения переименования: пустые дни «знакомого»
        # телефон записывал поверх своих, слал «убрали пару» и снимал
        # напоминания (третий аудит, В26 прогона 1), а 404 через час давал
        # «вас больше нет» — вечером подтверждение ждёт ночи (М20). 503 —
        # «временно»: телефон держит прежнее.
        return _renaming_response()
    name = store.known_teacher(teacher_id) if body is None else None
    if name and spelling_twin(store.teachers, name):
        # Колледж исправил опечатку в имени: тот же человек теперь под другим
        # id, и пустые дни здесь 60 дней говорили бы «пар нет». 404 — и
        # приложение предложит выбрать заново, человек найдёт себя под верным
        # именем (третий аудит, В19 прогона 1).
        name = None
    if name:
        # В этом листе у преподавателя нет пар, но он был в прошлых — отпуск,
        # неделя без часов. Это «пар нет», а не «вас больше нет в таблице»
        # (второй аудит, В18).
        body = teacher_payload(
            store.snapshot, store.teachers, teacher_id, start or _today(), days,
            store.generated, bells=load_bells() or None, known_name=name,
            today=_today(),
        )
    if body is None:
        return Response(status_code=404, content='{"error":"преподаватель не найден"}',
                        media_type=JSON)
    return _json_response(request, body)


def _renaming_response() -> Response:
    """Старый id, чьё переименование книга ещё подтверждает: «временно», а
    не «нет такого» — приложение держит прежнее расписание и не объявляет
    «вас больше нет в таблице»."""
    return Response(
        status_code=503, content='{"error":"id, похоже, переименован — подтверждаю"}',
        media_type=JSON, headers={"Retry-After": "1200"},
    )


@router.api_route("/v1/schedule/{group_id}", methods=["GET", "HEAD"])
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
            today=_today(),
        )

    body = build(group_id)
    if body is None:
        # Группу переименовали в таблице — отвечаем за новую. В ответе стоит
        # её новый id, приложение перепишет выбор у себя.
        renamed = refresher.renames.group(group_id)
        if renamed:
            body = build(renamed)
    if body is None and group_id in getattr(refresher.renames, "pending_groups", {}):
        # Переименование ждёт подтверждения (М20): не 404, а «временно».
        return _renaming_response()
    if body is None:
        return Response(status_code=404, content='{"error":"группа не найдена"}',
                        media_type=JSON)
    return _json_response(request, body)
