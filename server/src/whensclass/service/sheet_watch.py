"""Слежка за книгой: не появился ли в ней лист, которого служба ещё не видела.

Часть `Refresher`: поля заводит его __init__. Имена виденных листов — на диске
(seen_titles.json), чтобы перезапуск не терял базовую линию.
"""

from __future__ import annotations

import json
import logging

from ..config import settings
from ..sources import sheet_index
from ..sources.sheet_memory import SheetIndex
from ..storage.atomic import write_json
from .clock import _today

log = logging.getLogger(__name__)


class SheetWatch:
    """Слежка за книгой; методы `Refresher`."""

    def _load_seen_titles(self) -> set[str] | None:
        try:
            data = json.loads(self._seen_path.read_text("utf-8"))
        except (OSError, ValueError):
            return None
        return set(data) if isinstance(data, list) else None

    def _save_seen_titles(self) -> None:
        try:
            write_json(self._seen_path, sorted(self._seen_titles or ()))
        except OSError as exc:
            log.warning("имена листов для слежки не записались: %s", exc)

    def look_for_new_sheet(self) -> bool:
        """Не появился ли в книге лист, которого мы ещё не видели.

        Проверка дешёвая: один маленький запрос к Sheets API. Нужна ради
        перехода между листами — самого опасного места в службе. Раньше новый
        лист искался только ночью, и о неделе, выложенной в пятницу днём, мы
        узнавали в субботу. Три дня форы на починку стоят одного запроса
        в полчаса. Без ключа Sheets API список листов не получить — тогда
        просто False.
        """
        if settings.freeze:
            return False
        with self._lock:
            return self._look()

    def _look(self) -> bool:
        try:
            titles = {
                sheet.title
                for sheet in sheet_index.candidates(sheet_index.list_sheets(), _today())
            }
        except Exception as exc:
            # Не дозвонились — не беда: ночной поиск никуда не делся.
            log.warning("список листов не посмотрелся: %s", exc)
            return False

        if self._seen_titles is None:
            # Самый первый взгляд — файла ещё нет: запоминаем, с чем сравнивать.
            # Гнать поиск прямо сейчас незачем — служба и так обновилась на старте.
            self._seen_titles = titles
            self._save_seen_titles()
            return False

        fresh = (titles - self._seen_titles) | (set(self._retry_titles) & titles)
        self._seen_titles = titles
        self._save_seen_titles()
        if not fresh:
            return False

        log.info("в книге появился лист: %s — ищу заново", ", ".join(sorted(fresh)))
        changed = self.refresh(force=True)
        # Лист «групп», заведённый пустым, поиск отвергает, а заполненный
        # позже служба узнавала только ночью:
        # такие имена перепроверяются на следующих слежках, до RETRY_LOOKS раз.
        known = set(SheetIndex(self.state_dir).known)
        for title in fresh:
            if title in known or not sheet_index.looks_like_groups(title):
                self._retry_titles.pop(title, None)
                continue
            tries = self._retry_titles.get(title, 0) + 1
            if tries > RETRY_LOOKS:
                self._retry_titles.pop(title, None)
            else:
                self._retry_titles[title] = tries
        return changed


# Сколько раз слежка (раз в полчаса) перепроверяет новый лист «групп», который
# поиск ещё не принял: шесть часов.
RETRY_LOOKS = 12
