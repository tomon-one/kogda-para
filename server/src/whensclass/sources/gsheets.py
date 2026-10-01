"""Доступ к Google-таблице.

Всё, что знает про URL-ы Google, собрано здесь: если однажды таблицу закроют и
придётся идти через сервисный аккаунт, парсер трогать не придётся.
"""

from __future__ import annotations

import json
import logging
import re
import subprocess
from dataclasses import dataclass

import httpx

from ..config import settings

log = logging.getLogger(__name__)

class SheetClosed(Exception):
    """Вместо CSV пришла веб-страница: таблицу закрыли или она просит входа.

    Раньше такая страница шла в разборщик, тот не находил заголовка, и
    владельца отправляли смотреть «формат таблицы», а HTML ложился в архив
    как отвергнутый лист.
    """


@dataclass(frozen=True)
class SheetInfo:
    """Лист книги по Sheets API. Выгрузка книги в xlsx, которой список брался
    без ключа, gid не давала вовсе, а лист с 14.09.2026 читается только по gid:
    она качала 22 МБ впустую и убрана."""

    title: str
    gid: str | None = None
    hidden: bool = False


def _base() -> str:
    return f"https://docs.google.com/spreadsheets/d/{settings.spreadsheet_id}"


def _client() -> httpx.Client:
    return httpx.Client(
        timeout=settings.http_timeout,
        headers={"User-Agent": settings.user_agent},
        follow_redirects=True,
    )


def _via_exit(*args: str, stdin: str | None = None) -> str:
    """Тот же запрос к Google — с машины exit, по ssh.

    Запасной путь на случай, когда с сервера Google не отвечает. На exit ключ
    службы вызывает только wc-fetch (server/deploy/exit/wc-fetch): тот знает
    одну эту таблицу, берёт у службы лишь gid или ключ API и отдаёт тело
    ответа. Ни проброса портов, ни оболочки у ключа нет. Ключ API идёт на
    stdin, а не в аргументах: так его не видно в списке процессов ни здесь,
    ни там.
    """
    target, _, port = (settings.exit_ssh or "").rpartition(":")
    if not target or not port.isdigit():
        raise OSError(f"exit: WHENSCLASS_EXIT_SSH не вида пользователь@хост:порт — {settings.exit_ssh!r}")
    command = [
        "ssh", "-F", "/dev/null", "-p", port, "-i", settings.exit_key,
        "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
        "-o", "StrictHostKeyChecking=yes", "-o", f"UserKnownHostsFile={settings.exit_known_hosts}",
        "-o", "GlobalKnownHostsFile=/dev/null", "-o", "ConnectTimeout=15",
        "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=2", "-o", "LogLevel=ERROR",
        target, *args,
    ]
    result = subprocess.run(
        command, input=(stdin or "").encode(), capture_output=True, timeout=settings.http_timeout + 60,
    )
    why = result.stderr.decode("utf-8", "replace").strip()[:300]
    # curl -f на exit: Google ответил кодом ошибки. Это ответ Google, а не
    # обрыв сети, — отдаём его наверх как HTTPStatusError, как при прямом
    # запросе: мёртвый gid (400) забывается и ищется заново, отказ ключа
    # (403) считается тревогой ключа. Иначе через exit любой отказ Google
    # выглядел сетевым сбоем.
    status = re.search(r"returned error: (\d{3})", why) if result.returncode == 22 else None
    if status:
        request = httpx.Request("GET", f"https://exit/{args[0]}")
        response = httpx.Response(int(status.group(1)), request=request)
        raise httpx.HTTPStatusError(f"exit: Google ответил {status.group(1)}", request=request, response=response)
    if result.returncode != 0:
        raise OSError(f"exit: ssh вернул {result.returncode}: {why}")
    return result.stdout.decode("utf-8")


def _direct_or_exit(direct, *args: str, stdin: str | None = None) -> tuple[str | None, httpx.Response | None]:
    """Запрос напрямую; не ответил Google на уровне сети — через exit.

    Возвращает (текст через exit, None) или (None, ответ напрямую). Ответ
    Google с кодом ошибки — не повод идти в обход: закрытую таблицу или
    мёртвый ключ exit не починит. Не вышло и через exit — наверх уходит
    исходная ошибка, чтобы причина сбоя осталась прежней.
    """
    try:
        return None, direct()
    except httpx.TransportError as error:
        if not settings.exit_ssh:
            raise
        try:
            text = _via_exit(*args, stdin=stdin)
        except (OSError, subprocess.TimeoutExpired, UnicodeDecodeError) as fallback:
            log.warning("Google не ответил (%s), и через exit тоже: %s", error, fallback)
            raise error from None
        log.warning("Google не ответил напрямую (%s) — взято через exit", error)
        return text, None


def fetch_sheet_csv(gid: str | None = None, title: str | None = None) -> str:
    """Забирает лист сырым CSV — `export?format=csv&gid=`.

    Только по gid. До 14 сентября 2026 лист брался через gviz (`/gviz/tq`),
    и тот умел по имени — но gviz это не выгрузка, а «визуализация»: он
    типизирует колонку по большинству значений и выбрасывает текст из
    «числовых» — из колонок аудиторий пропадали ссылки на вебинар, «онлайн»,
    «55/1», спортзалы: 945 ячеек в 62 колонках из 171 при status ok. Ещё он
    схлопывает шапку и выбрасывает пустые строки, так что номера строк врут.
    Сырой экспорт отдаёт ячейки как есть; шапку «столбиком» собирает
    `export.collapse_export`. По несуществующему gid — 400, не первая
    вкладка кодом 200, как было у gviz по неизвестному имени.

    ETag экспорт не отдаёт: узнавать «не изменилось» приходится по хешу.
    """
    if not gid:
        raise ValueError(
            f"лист {title!r} без gid не прочитать: сырой экспорт знает только gid "
            "(нужен ключ Sheets API или WHENSCLASS_SHEET_GID)"
        )
    def direct() -> httpx.Response:
        with _client() as client:
            return client.get(f"{_base()}/export", params={"format": "csv", "gid": gid})

    relayed, response = _direct_or_exit(direct, "csv", gid)
    if response is not None:
        response.raise_for_status()
        kind = response.headers.get("content-type", "")
        text = response.text
    else:
        kind, text = "", relayed or ""
    # Экспорт приходит с BOM.
    text = text.lstrip("\ufeff")
    if "html" in kind.lower() or text.lstrip()[:15].lower().startswith(("<!doctype", "<html")):
        raise SheetClosed(f"по gid {gid} пришла страница {kind or 'без типа'}, а не CSV")
    return text


def list_sheets_via_api(key: str) -> list[SheetInfo]:
    """Список листов через Sheets API. Даёт настоящие gid и полные имена."""
    url = f"https://sheets.googleapis.com/v4/spreadsheets/{settings.spreadsheet_id}"
    params = {"key": key, "fields": "sheets.properties(sheetId,title,hidden)"}
    def direct() -> httpx.Response:
        with _client() as client:
            return client.get(url, params=params)

    relayed, response = _direct_or_exit(direct, "sheets", stdin=key + "\n")
    if response is not None:
        response.raise_for_status()
        data = response.json()
    else:
        data = json.loads(relayed or "{}")
    out = []
    for item in data.get("sheets", []):
        props = item.get("properties", {})
        out.append(
            SheetInfo(
                title=props.get("title", ""),
                # gid — только число: он становится именем каталога архива,
                # а ответ мог прийти и через exit.
                gid=str(props["sheetId"]) if isinstance(props.get("sheetId"), int) else None,
                hidden=bool(props.get("hidden")),
            )
        )
    return out
