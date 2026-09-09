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


def fetch_sheet_csv(
    gid: str | None = None, title: str | None = None, etag: str | None = None
) -> tuple[str | None, str | None]:
    """Забирает лист в виде CSV.

    Возвращает (текст, etag). Текст None, если Google ответил 304 — значит
    таблица не менялась и перечитывать её незачем.
    """
    params = {"tqx": "out:csv"}
    if title:
        params["sheet"] = title
    else:
        params["gid"] = gid or ""

    headers = {"If-None-Match": etag} if etag else {}
    with _client() as client:
        response = client.get(f"{_base()}/gviz/tq", params=params, headers=headers)
    if response.status_code == 304:
        return None, etag
    response.raise_for_status()
    return response.text, response.headers.get("ETag")


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
