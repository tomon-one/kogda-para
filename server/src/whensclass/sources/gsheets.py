"""Доступ к Google-таблице.

Всё, что знает про URL-ы Google, собрано здесь: если однажды таблицу закроют и
придётся идти через сервисный аккаунт, парсер трогать не придётся.
"""

from __future__ import annotations

import io
import logging
import re
import zipfile
from dataclasses import dataclass

import httpx

from ..config import settings

log = logging.getLogger(__name__)

_SHEET_TAG_RE = re.compile(
    r'<sheet\b[^>]*?name="([^"]+)"[^>]*?sheetId="(\d+)"[^>]*?>|'
    r'<sheet\b[^>]*?state="(\w+)"[^>]*?name="([^"]+)"[^>]*?sheetId="(\d+)"',
)
# Excel обрезает имена листов до 31 символа — по этой длине узнаём,
# что обращаться к листу по имени нельзя.
XLSX_TITLE_LIMIT = 31


class SheetClosed(Exception):
    """Вместо CSV пришла веб-страница: таблицу закрыли или она просит входа.

    Раньше такая страница шла в разборщик, тот не находил заголовка, и
    владельца отправляли смотреть «формат таблицы», а HTML ложился в архив
    как отвергнутый лист (второй аудит, М7).
    """


@dataclass(frozen=True)
class SheetInfo:
    title: str
    sheet_id: str | None = None   # порядковый номер из xlsx, не gid
    gid: str | None = None
    hidden: bool = False

    @property
    def title_may_be_truncated(self) -> bool:
        return len(self.title) >= XLSX_TITLE_LIMIT


def _base() -> str:
    return f"https://docs.google.com/spreadsheets/d/{settings.spreadsheet_id}"


def _client() -> httpx.Client:
    return httpx.Client(
        timeout=settings.http_timeout,
        headers={"User-Agent": settings.user_agent},
        follow_redirects=True,
    )


def fetch_sheet_csv(gid: str | None = None, title: str | None = None) -> str:
    """Забирает лист сырым CSV — `export?format=csv&gid=`.

    Только по gid. До 14 сентября 2026 лист брался через gviz (`/gviz/tq`),
    и тот умел по имени — но gviz это не выгрузка, а «визуализация»: он
    типизирует колонку по большинству значений и выбрасывает текст из
    «числовых» — из колонок аудиторий пропадали ссылки на вебинар, «онлайн»,
    «55/1», спортзалы: 945 ячеек в 62 колонках из 171 при status ok. Ещё он
    схлопывает шапку и выбрасывает пустые строки, так что номера строк врут.
    Сырой экспорт отдаёт ячейки как есть; шапку «столбиком» собирает
    `csv_schedule.collapse_export`. По несуществующему gid — 400, не первая
    вкладка кодом 200, как было у gviz по неизвестному имени.

    ETag экспорт не отдаёт: узнавать «не изменилось» приходится по хешу.
    """
    if not gid:
        raise ValueError(
            f"лист {title!r} без gid не прочитать: сырой экспорт знает только gid "
            "(нужен ключ Sheets API или WHENSCLASS_SHEET_GID)"
        )
    with _client() as client:
        response = client.get(f"{_base()}/export", params={"format": "csv", "gid": gid})
    response.raise_for_status()
    kind = response.headers.get("content-type", "")
    # Экспорт приходит с BOM.
    text = response.text.lstrip("\ufeff")
    if "html" in kind.lower() or text.lstrip()[:15].lower().startswith(("<!doctype", "<html")):
        raise SheetClosed(f"по gid {gid} пришла страница {kind or 'без типа'}, а не CSV")
    return text


def list_sheets_via_xlsx() -> list[SheetInfo]:
    """Список листов из книги, выгруженной в xlsx.

    Единственный способ увидеть все листы без ключа API. Дорогой: выгрузка
    весит около двадцати мегабайт, поэтому дёргается раз в сутки.
    """
    with _client() as client:
        response = client.get(f"{_base()}/export", params={"format": "xlsx"})
    response.raise_for_status()
    return parse_workbook(response.content)


def parse_workbook(blob: bytes) -> list[SheetInfo]:
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        xml = archive.read("xl/workbook.xml").decode("utf-8")

    sheets: list[SheetInfo] = []
    for match in re.finditer(r"<sheet\b[^>]*/>", xml):
        tag = match.group(0)
        name = re.search(r'name="([^"]*)"', tag)
        sheet_id = re.search(r'sheetId="(\d+)"', tag)
        state = re.search(r'state="(\w+)"', tag)
        if not name:
            continue
        sheets.append(
            SheetInfo(
                title=_unescape(name.group(1)),
                sheet_id=sheet_id.group(1) if sheet_id else None,
                hidden=bool(state and state.group(1) != "visible"),
            )
        )
    return sheets


def list_sheets_via_api(key: str) -> list[SheetInfo]:
    """Список листов через Sheets API. Даёт настоящие gid и полные имена."""
    url = f"https://sheets.googleapis.com/v4/spreadsheets/{settings.spreadsheet_id}"
    params = {"key": key, "fields": "sheets.properties(sheetId,title,hidden)"}
    with _client() as client:
        response = client.get(url, params=params)
    response.raise_for_status()
    out = []
    for item in response.json().get("sheets", []):
        props = item.get("properties", {})
        out.append(
            SheetInfo(
                title=props.get("title", ""),
                gid=str(props["sheetId"]) if "sheetId" in props else None,
                hidden=bool(props.get("hidden")),
            )
        )
    return out


def _unescape(value: str) -> str:
    return (
        value.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", '"')
        .replace("&apos;", "'")
    )
