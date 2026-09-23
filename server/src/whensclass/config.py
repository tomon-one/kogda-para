"""Настройки сервиса. Всё, что может понадобиться поправить на боевой машине
без пересборки, живёт здесь и читается из окружения (/etc/whensclass/env).
"""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WHENSCLASS_", env_file=".env")

    spreadsheet_id: str = "1FiMov0r4UUDKT6A56NWMImpoUakDC2YDevgaOpJQ7Qc"

    # Аварийный путь: если поиск листа сломался, а расписание нужно сегодня —
    # прописать сюда лист руками и перезапустить сервис.
    sheet_title: str | None = None
    sheet_gid: str | None = None

    # Ключ Sheets API необязателен. С ним список листов приходит с настоящими
    # gid и неурезанными именами; без него читаем тот же список из xlsx.
    sheets_api_key: str | None = None

    # Оповещения владельцу о поломках через ntfy.sh. Тема — длинная случайная
    # строка, она же пароль. Без темы служба работает как прежде, просто молча.
    ntfy_topic: str | None = None
    ntfy_url: str = "https://ntfy.sh/"

    state_dir: str = "var"
    timezone: str = "Asia/Novosibirsk"

    # Границы правдоподобия листа для обновления. Единственный случай, где
    # порог — про объём, а не про структуру: лист, который заполняют
    # постепенно (в понедельник в нём четыре даты). Поиск листа среди
    # кандидатов на эти пороги не смотрит — там parse_csv отличает наш лист
    # от графиков, и с низким порогом чужой лист стал бы снимком для всех.
    min_groups: int = 100
    min_dates: int = 5
    min_lessons: int = 500
    max_gap_days: int = 25

    refresh_minutes: int = 20
    # За сколько минут до звонка сходить за расписанием ещё раз: пары меняют
    # и перед самым началом.
    refresh_before_lesson_minutes: int = 8
    active_hours: tuple[int, int] = (6, 22)
    http_timeout: float = 60.0
    # Только ASCII: HTTP-заголовки кириллицу не переносят.
    user_agent: str = "WhensClass/0.1 (NGOK schedule widget; student project)"

    # Сколько дней отдавать виджету по умолчанию.
    default_days: int = 3
    # На сколько дней вперёд держим расписание. Неделя часто перешагивает
    # границу листа, поэтому при необходимости склеиваем два.
    window_days: int = 8


settings = Settings()
