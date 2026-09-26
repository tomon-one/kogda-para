"""Настройки сервиса. Всё, что может понадобиться поправить на боевой машине
без пересборки, живёт здесь и читается из окружения (/etc/whensclass/env).
"""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WHENSCLASS_", env_file=".env")

    spreadsheet_id: str = "1FiMov0r4UUDKT6A56NWMImpoUakDC2YDevgaOpJQ7Qc"

    # Аварийный путь: если поиск листа сломался, а расписание нужно сегодня —
    # прописать сюда gid листа руками и перезапустить сервис. Имя — только
    # подпись: с 14 сентября 2026 лист читается только по gid.
    sheet_title: str | None = None
    sheet_gid: str | None = None

    # Ключ Sheets API. Без него gid листов взять неоткуда — лист не прочитать,
    # служба уходит в stale с тревогой (docs/deploy.md, «Ключ Sheets API»).
    sheets_api_key: str | None = None

    # Оповещения владельцу о поломках через ntfy.sh. Тема — длинная случайная
    # строка, она же пароль. Без темы тревоги не уходят никуда: служба
    # работает, но молча.
    ntfy_topic: str | None = None
    ntfy_url: str = "https://ntfy.sh/"

    state_dir: str = "var"
    # Свой домен — канарейке, чтобы сверять срок отдаваемого сертификата.
    domain: str = "schedule.edelweiss-alpine-confederation.ru"
    timezone: str = "Asia/Novosibirsk"

    # Границы правдоподобия листа для обновления. Единственный случай, где
    # порог — про объём, а не про структуру: лист, который заполняют
    # постепенно (в понедельник в нём четыре даты). Поиск листа среди
    # кандидатов объём не спрашивает: наш лист от
    # графиков он отличает по заголовку групп, min_groups. Объём судит
    # обновление: сегодняшний лист меньше порога — отказ, следующий —
    # пропускается до поры.
    min_groups: int = 100
    min_dates: int = 5
    min_lessons: int = 500
    max_gap_days: int = 25
    max_days_ahead: int = 120
    # Заморозка: служба не ходит в таблицу и сразу stale на прежнем снимке —
    # когда на экранах чужие пары при ok (docs/deploy.md, «Когда что-то не
    # так»). Прежний рычаг SPREADSHEET_ID=stop полчаса держал ok, стирал
    # память о листе и вёл ссылку «открыть таблицу» в несуществующую книгу.
    freeze: bool = False

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
