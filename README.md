<img src="android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="" width="96">

# Когда пара?

**Расписание НГОК на домашнем экране Android.** Виджеты и приложение для
студентов и преподавателей Новосибирского городского открытого колледжа.

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84)
![версия](https://img.shields.io/github/v/release/tomon-one/kogda-para?include_prereleases&label=%D0%B2%D0%B5%D1%80%D1%81%D0%B8%D1%8F)
![лицензия AGPL-3.0](https://img.shields.io/badge/%D0%BB%D0%B8%D1%86%D0%B5%D0%BD%D0%B7%D0%B8%D1%8F-AGPL--3.0-blue)
[![тесты](https://github.com/tomon-one/kogda-para/actions/workflows/tests.yml/badge.svg)](https://github.com/tomon-one/kogda-para/actions/workflows/tests.yml)

**[Скачать](https://kogda-para-nsk.ru/download/latest.apk)**
· [все версии](https://github.com/tomon-one/kogda-para/releases)
· [как поставить](docs/install.md) · вопросы — [@toomonn](https://t.me/toomonn)

> **Пре-релиз.** Новых функций автор не планирует, дальше только исправления.
> Проверено на телефоне автора с Android 12, на других пока нет.
>
> **Код писал ИИ** — Claude, по задачам автора и с его правками.
>
> **Не заработало или показывает не то — [напишите](https://t.me/toomonn)** или
> заведите [issue](https://github.com/tomon-one/kogda-para/issues).

## Как выглядит

<p>
  <img src="docs/screenshots/widget-week.png" alt="Виджет «Неделя»" width="300" align="top">
  <img src="docs/screenshots/widget-day.png" alt="Виджет «День»" width="300" align="top">
  <img src="docs/screenshots/widget-next.png" alt="Виджет «Ближайшая пара»" width="150" align="top">
</p>

<p>
  <img src="docs/screenshots/today-dark.png" alt="Расписание, тёмная тема" width="250">
  <img src="docs/screenshots/today-light.png" alt="Расписание, светлая тема" width="250">
  <img src="docs/screenshots/settings.png" alt="Настройки" width="250">
</p>

## Что умеет

- Три виджета: пары на день, неделя вперёд и ближайшая пара.
- Расписание обновляется само, без интернета показывается последнее скачанное.
- Уведомления об отменах и заменах на сегодня и завтра.
- Напоминание перед парой — включается в настройках.
- Соседняя подгруппа: её пары показываются вместе с вашими.
- Расписание преподавателя — по его имени.
- Блокировка Google в России не помешает: телефон ходит только на сервер
  приложения, а у сервера есть запасной путь к таблице.
- Сообщает о новой версии, ставится она кнопкой в настройках.

## Установка

1. Скачайте файл и откройте его.
2. Разрешите установку тому, чем открыли файл: браузеру, Telegram или «Файлам».
3. Play Защита покажет одно из двух окон, они ниже.
4. Выберите группу или «Я преподаватель» и добавьте виджет на домашний экран —
   из списка виджетов или кнопкой в «Настройки» → «Виджеты».
5. На Xiaomi, Huawei, Honor, Samsung, Tecno и Infinix откройте «Настройки» →
   «Работа в фоне» и пройдите пункты по очереди: без этого виджет и
   напоминания ждут, пока приложение откроют.

**Первое окно:** Google проверил файл и ничего опасного не нашёл. Нажмите
«Install» («Установить»).

<img src="docs/screenshots/play-protect-safe.png" alt="Play Защита: приложение выглядит безопасным" width="240">

**Второе окно:** Google не знает разработчика. Нажмите мелкую надпись
«Install anyway» («Всё равно установить»), большая кнопка «Got it» отменяет
установку.

<img src="docs/screenshots/play-protect-blocked.png" alt="Play Защита: приложение заблокировано" width="240">

Предупреждает она потому, что приложения нет в Google Play. Все версии
подписаны одним ключом, и поддельное обновление поверх не встанет; отпечаток
ключа — в [SECURITY.md](SECURITY.md#подпись). Что делать, если не
получилось, — в [docs/install.md](docs/install.md).

## Чего не умеет

- Работает только с расписанием НГОК.
- Версии для iPhone нет: Apple не даёт ставить приложения без App Store.
- Знает ровно то, что опубликовано в таблице колледжа. Днём её правка доходит
  до телефона за двадцать минут, если открыть приложение, и до полутора часов,
  если нет.

## Что уходит на сервер

Только то, чьё расписание открыто, и за какие дни. Учётной записи, аналитики и
рекламы нет, остальное остаётся на телефоне. Если сервер не отвечает или не
отдаёт файл, за новой версией приложение идёт на GitHub — группа и настройки
туда не уходят.

## Как устроено

Служба на сервере сама читает таблицу колледжа и отдаёт телефону только нужное,
приложение это показывает.

- `server/` — служба на Python (FastAPI).
- `android/` — приложение и виджеты на Kotlin.
- `docs/` — [формат таблицы](docs/source-format.md),
  [ответы сервера](docs/api.md), [версии](docs/versions.md),
  [установка](docs/install.md).
- `tools/` — вспомогательные скрипты.

Сервер — Python 3.12 или новее; для чтения таблицы нужен ключ Google Sheets API
(`WHENSCLASS_SHEETS_API_KEY` в `server/.env`):

```bash
cd server
python3 -m venv .venv
.venv/bin/python -m pip install -c constraints.txt -e ".[dev]"
.venv/bin/python -m uvicorn whensclass.main:app --port 8081 --app-dir src
```

Приложение — JDK 21 и Android SDK с platform 37, из корня репозитория:

```bash
cd android
./gradlew assembleDebug
```

## Лицензия

Код — под [AGPL-3.0](LICENSE). Логотип колледжа — `assets/logo-ngok.svg` и
сделанные из него иконки и значки приложения в `android/app/src/main/res` —
принадлежит колледжу и под лицензию не входит, как и данные таблицы колледжа в
тестовых фикстурах.
