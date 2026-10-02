"""Эндпоинты API.

Тела ответов собираются на каждый запрос из снимка в памяти — это десятки
миллисекунд. ETag считается по байтам тела, поэтому повторный разбор
неизменившейся таблицы не заставляет телефон качать то же самое.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import urllib.parse
import zoneinfo

from fastapi import APIRouter, Query, Request, Response

from ..config import settings
from ..push.subscription import BadSubscription, parse_keys, parse_subscription
from ..service.bells import BELLS
from ..service.refresher import state_dir
from .etag import etag_for, matches
from .payloads import (
    FILLED_SHARE,
    filling,
    groups_payload,
    meta_payload,
    schedule_payload,
    teacher_answer,
    teachers_payload,
)
from .releases import CHANNELS, latest_release

# Маршруты — на GET и HEAD: nginx пропускает оба, а сторожа по коду ответа
# ходят HEAD-ом и на голый @router.get получили бы 405.
router = APIRouter()
log = logging.getLogger(__name__)


# Лист кончился больше этого назад — каникулы (канарейка — так же).
HOLIDAY = dt.timedelta(days=7)


def _today() -> dt.date:
    """Сегодня по часовому поясу колледжа: сервер может жить в UTC."""
    return dt.datetime.now(zoneinfo.ZoneInfo(settings.timezone)).date()

JSON = "application/json; charset=utf-8"
# Хранить можно, отдавать без спроса — нет: каждый раз сверять ETag (304 и
# ноль байт, если ничего не изменилось). С max-age OkHttp в телефоне отдаёт
# расписание из кэша как свежее, и ручное обновление приносит старое. Решается
# здесь, а не в приложении: так и у уже установленных сборок.
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


def _too_old(request: Request) -> Response | None:
    """426, если сборка приложения ниже `min_build`: её форматы служба уже не
    держит. /v1/app и /download/ этим не закрываются — по ним и обновляются."""
    raw = request.headers.get("X-App-Build", "")
    if settings.min_build <= 0 or not raw.isdigit() or int(raw) >= settings.min_build:
        return None
    return Response(
        status_code=426,
        content=json.dumps({"error": "версия приложения больше не поддерживается",
                            "min": settings.min_build}, ensure_ascii=False),
        media_type=JSON,
    )


def _marks(request: Request) -> bool:
    """Клиент знает пометку непрочитанного дня (`?marks=1`): прежним версиям
    пустой непрочитанный день читался бы как «пар нет» и «убрали»."""
    return request.query_params.get("marks") == "1"


# Дальше года от сегодняшнего дня спрашивать незачем: приложение просит неделю
# от понедельника. Без границы ?from=9999-12-31 падает на переполнении даты с
# 500 и длинной трассировкой — любой прохожий забил бы journald соседям.
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

    По коду ответа можно поставить любой сторожок — ему не надо разбирать
    /v1/meta.
    """
    store, refresher = _state(request)
    snapshot = store.snapshot
    today = _today()
    reason = note = None
    if snapshot is None:
        reason = "расписание ещё не загружено"
    elif refresher.status != "ok":
        reason = f"не обновляется: {refresher.last_error or refresher.status}"
    elif snapshot.unread:
        reason = _outward(store, refresher)[2]
    else:
        coverage = snapshot.coverage
        busy, fullest = filling(snapshot)
        if coverage and today > coverage[1] + HOLIDAY:
            # Лист кончился больше недели назад, а нового нет — каникулы, а не
            # авария: 200, но с пометкой.
            note = f"лист кончился {coverage[1]}, нового нет — похоже, каникулы"
        # Воскресений в листах нет — это не повод для тревоги.
        elif today.weekday() != 6 and coverage and not (coverage[0] <= today <= coverage[1]):
            reason = f"лист покрывает {coverage[0]}—{coverage[1]}, а сегодня {today}"
        elif today in snapshot.dates and busy[today] < FILLED_SHARE * fullest:
            # Дата в листе есть, а пар почти ни у кого: каркас дат вписан
            # заранее, а неделю колледж не дописал. Так же выглядит и праздник
            # — пусть человек глянет.
            reason = (
                f"сегодня пары вписаны у {busy[today]} групп из {len(snapshot.groups)} — "
                "лист на сегодня не дописан или праздник"
            )
    body = {"ok": reason is None, "status": _outward(store, refresher)[0]}
    if reason:
        body["reason"] = reason
    elif note:
        body["note"] = note
    return Response(
        content=json.dumps(body, ensure_ascii=False),
        status_code=200 if reason is None else 503,
        media_type=JSON,
    )


def _outward(store, refresher) -> tuple[str, dt.datetime | None, str | None]:
    """Что видно наружу: (status, since, err).

    Непрочитанные дни при здоровом обновлении — тоже stale: старые версии
    приложения других пометок не знают, и сбой у них лучше, чем прежние пары
    без знака. Новые читают `refresh` и `unread` в /v1/meta.
    """
    snapshot = store.snapshot
    if refresher.status != "ok" or snapshot is None or not snapshot.unread:
        return refresher.status, refresher.failing_since, refresher.last_error
    return "stale", snapshot.unread_since, "не прочитаны дни: " + "; ".join(snapshot.unread_why)


def _not_loaded() -> Response:
    """Служба ещё ничего не разобрала: 503 — «временно», телефон держит своё."""
    return Response(status_code=503, content='{"error":"расписание ещё не загружено"}',
                    media_type=JSON)


@router.api_route("/v1/app", methods=["GET", "HEAD"])
def app_release(request: Request) -> Response:
    """Последняя выложенная сборка приложения — чтобы оно знало об обновлении."""
    channel = request.query_params.get("channel", "main")
    if channel not in CHANNELS:
        return Response(status_code=404, content='{"error":"нет такого канала"}',
                        media_type=JSON)
    release = latest_release(state_dir(), channel)
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
    if refused := _too_old(request):
        return refused
    store, refresher = _state(request)
    if store.snapshot is None:
        return _not_loaded()
    status, since, error = _outward(store, refresher)
    body = meta_payload(store.snapshot, store.generated, status,
                        refresher.checked_at, today=_today(),
                        failing_since=since, error=error, refresh=refresher.status)
    return _json_response(request, body, cache=False)


@router.api_route("/v1/groups", methods=["GET", "HEAD"])
def groups(request: Request) -> Response:
    if refused := _too_old(request):
        return refused
    store, _ = _state(request)
    if store.snapshot is None:
        return _not_loaded()
    return _json_response(request, groups_payload(store.snapshot, store.generated))


@router.api_route("/v1/teachers", methods=["GET", "HEAD"])
def teachers(request: Request) -> Response:
    """Список преподавателей — собирается из расписания групп."""
    if refused := _too_old(request):
        return refused
    store, _ = _state(request)
    if store.snapshot is None or store.teachers is None:
        return _not_loaded()
    return _json_response(request, teachers_payload(store.teachers, store.generated))


@router.api_route("/v1/teacher/{teacher_id}", methods=["GET", "HEAD"])
def teacher(
    request: Request,
    teacher_id: str,
    start: dt.date | None = Query(None, alias="from"),
    days: int = Query(settings.default_days, ge=1, le=14),
) -> Response:
    if refused := _too_old(request):
        return refused
    store, refresher = _state(request)
    if (bad := _bad_from(start)) is not None:
        return bad
    if store.snapshot is None or store.teachers is None:
        return _not_loaded()

    body = teacher_answer(
        store.snapshot, store.teachers, store.known_teacher, teacher_id, start or _today(),
        days, store.generated, bells=BELLS, today=_today(), marks=_marks(request),
    )
    if body is None:
        return Response(status_code=404, content='{"error":"преподаватель не найден"}',
                        media_type=JSON)
    return _json_response(request, body)


@router.api_route("/v1/schedule/{group_id}", methods=["GET", "HEAD"])
def schedule(
    request: Request,
    group_id: str,
    start: dt.date | None = Query(None, alias="from"),
    days: int = Query(settings.default_days, ge=1, le=14),
) -> Response:
    if refused := _too_old(request):
        return refused
    store, refresher = _state(request)
    if (bad := _bad_from(start)) is not None:
        return bad
    if store.snapshot is None:
        return _not_loaded()

    def build(gid: str) -> dict | None:
        return schedule_payload(
            store.snapshot,
            gid,
            start or _today(),
            days,
            store.generated,
            bells=BELLS,
            today=_today(),
            marks=_marks(request),
        )

    body = build(group_id)
    if body is None:
        # Группы с таким id нет — в том числе переименованной: приложение на
        # 404 предлагает выбрать группу заново.
        return Response(status_code=404, content='{"error":"группа не найдена"}',
                        media_type=JSON)
    return _json_response(request, body)


# --- уведомления сайта --------------------------------------------------------
# Только для сайта: приложение считает уведомления само. POST — единственное,
# что служба принимает; nginx пускает его только в /v1/push/ и с телом до 2 КБ.

MAX_PUSH_BODY = 4096


def _error(status: int, text: str) -> Response:
    return Response(status_code=status, media_type=JSON,
                    content=json.dumps({"error": text}, ensure_ascii=False))


async def _json_body(request: Request) -> object:
    raw = await request.body()
    if len(raw) > MAX_PUSH_BODY:
        raise BadSubscription("тело больше 4 КБ")
    try:
        return json.loads(raw)
    except ValueError as exc:
        raise BadSubscription("тело — не JSON") from exc


@router.api_route("/v1/push/key", methods=["GET", "HEAD"])
def push_key(request: Request) -> Response:
    """Открытый ключ VAPID: браузер подписывается с ним."""
    push = request.app.state.push
    if not push.enabled:
        return _error(404, "уведомления сайта не настроены")
    return _json_response(request, {"v": 1, "key": push.vapid.public}, cache=False)


@router.post("/v1/push/subscribe")
async def push_subscribe(request: Request) -> Response:
    """Подписка браузера: записать или заменить (тот же адрес — та же запись)."""
    push = request.app.state.push
    if not push.enabled:
        return _error(404, "уведомления сайта не настроены")
    body = None
    try:
        body = await _json_body(request)
        sub = parse_subscription(body)
    except BadSubscription as exc:
        _rejected(body, exc)
        return _error(422, str(exc))
    store = request.app.state.store
    if store.snapshot is None:
        return _not_loaded()
    if sub["kind"] == "group":
        known = any(g.id == sub["id"] for g in store.snapshot.groups)
    else:
        index = store.teachers
        known = (
            bool(index and (sub["id"] in index.names or sub["id"] in index.aliases))
            or store.known_teacher(sub["id"]) is not None
        )
    if not known:
        return _error(404, "группа не найдена" if sub["kind"] == "group" else "преподаватель не найден")
    if not push.subscribe(sub, _today()):
        return _error(503, "подписок слишком много")
    return Response(status_code=204)


def _rejected(body: object, exc: Exception) -> None:
    """Отвергнутая подписка — в журнал хост её службы рассылки (не адрес): вдруг у
    какого-то браузера служба своя и её нет в списке."""
    endpoint = body.get("endpoint") if isinstance(body, dict) else None
    host = None
    if isinstance(endpoint, str):
        try:
            host = urllib.parse.urlsplit(endpoint).hostname
        except ValueError:
            host = None
    log.info("подписка на уведомления отвергнута (%s): %s", host or "без адреса", exc)


@router.post("/v1/push/move")
async def push_move(request: Request) -> Response:
    """Браузер сменил подписку сам: сервис-воркер переносит прежний выбор на новую."""
    push = request.app.state.push
    if not push.enabled:
        return _error(404, "уведомления сайта не настроены")
    body = None
    try:
        body = await _json_body(request)
        fresh = parse_keys(body)
    except BadSubscription as exc:
        _rejected(body, exc)
        return _error(422, str(exc))
    if not push.move(body["old"], fresh):
        # Прежней записи нет (стёрта по 410): сервис-воркер сбросит отметку, и
        # страница перешлёт подписку с выбором при первом открытии.
        return _error(404, "прежней подписки нет")
    return Response(status_code=204)


@router.post("/v1/push/remove")
async def push_remove(request: Request) -> Response:
    """Отписка: запись стирается. Неизвестный адрес — тоже 204."""
    push = request.app.state.push
    try:
        body = await _json_body(request)
    except BadSubscription as exc:
        return _error(422, str(exc))
    endpoint = body.get("endpoint") if isinstance(body, dict) else None
    if not isinstance(endpoint, str):
        return _error(422, "нужен endpoint")
    push.unsubscribe(endpoint)
    return Response(status_code=204)
