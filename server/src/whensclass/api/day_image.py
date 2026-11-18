"""PNG «картинка дня» для отправки в чат.

Рендер — по готовому телу /v1/schedule на один день: те же пары, звонки и
состояние дня, что видит приложение. Пиксели собираются Pillow, шрифт —
системный, с кириллицей и полужирным начертанием; список путей можно задать
переменной WHENSCLASS_IMAGE_FONT.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import io
import os
import pathlib
import tempfile
from functools import cache

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 1080, 1920
MARGIN = 64
RADIUS = 32

BG = (0x00, 0x00, 0x00)
CARD = (0x14, 0x14, 0x14)
MAIN = (0xF5, 0xF5, 0xF5)
QUIET = (0x9A, 0xA0, 0xA6)
ACCENT = (0xFF, 0x6B, 0x70)

WEEKDAYS = ("Понедельник", "Вторник", "Среда", "Четверг",
            "Пятница", "Суббота", "Воскресенье")
MONTHS = ("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
          "августа", "сентября", "октября", "ноября", "декабря")

# Слова календарного графика — те же, что в дне приложения и на сайте.
STATES = {
    "session": "по графику сессия",
    "holidays": "каникулы",
    "practice": "практика",
}

# Версия вида картинки: меняется раскладка — подними, иначе на диске останутся
# старые файлы с тем же ключом (группа, день, поколение снимка).
RENDER_VERSION = 4

MAX_CARDS = 6
TIME_LINE = 44
SUBJECT_LINE = 54
BOTTOM_LINE = 42
FOOTER = "kogda-para-nsk.ru"

# Свой шрифт — в api/fonts: на сервере шрифтов может не быть вовсе, а без
# кириллицы картинка не собирается. DejaVu Sans, урезанный до нужных знаков
# (латиница, кириллица, цифры и пунктуация), лицензия рядом, LICENSE.
BUNDLED = (
    (str(pathlib.Path(__file__).parent / "fonts" / "DejaVuSans-Bold.ttf"),
     str(pathlib.Path(__file__).parent / "fonts" / "DejaVuSans-Regular.ttf")),
)

# Пары «полужирный, обычный» по порядку. env задаёт один файл на оба
# начертания: своей копии шрифта на сервере может не быть.
FONT_PAIRS = BUNDLED + (
    ("/usr/share/fonts/google-noto/NotoSans-Bold.ttf",
     "/usr/share/fonts/google-noto/NotoSans-Regular.ttf"),
    ("/usr/share/fonts/dejavu-sans-fonts/DejaVuSans-Bold.ttf",
     "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf"),
    ("/usr/share/fonts/adwaita-sans-fonts/AdwaitaSans-Bold.ttf",
     "/usr/share/fonts/adwaita-sans-fonts/AdwaitaSans-Regular.ttf"),
)

PROFILES = ((24, 16), (18, 10))   # (отступ в карточке, зазор между строками)


class NoFont(Exception):
    """Ни одного файла шрифта с кириллицей не нашлось."""


@cache
def _font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    path = os.environ.get("WHENSCLASS_IMAGE_FONT")
    if path and os.path.isfile(path):
        return ImageFont.truetype(path, size)
    for heavy, regular in FONT_PAIRS:
        for candidate in ((heavy, regular) if bold else (regular,)):
            if os.path.isfile(candidate):
                return ImageFont.truetype(candidate, size)
    raise NoFont("нет файла шрифта с кириллицей: задайте WHENSCLASS_IMAGE_FONT")


def _ellipsis(draw: ImageDraw.ImageDraw, text: str, font, width: float) -> str:
    """Обрезает строку по ширине, ставя многоточие."""
    if draw.textlength(text, font=font) <= width:
        return text
    return _cut(draw, text, font, width)


def _cut(draw: ImageDraw.ImageDraw, text: str, font, width: float) -> str:
    """Обрезает строку и ставит многоточие, даже если строка влезала."""
    while text and draw.textlength(text + "…", font=font) > width:
        text = text[:-1].rstrip()
    return text + "…"


def _fit(draw: ImageDraw.ImageDraw, text: str, font, width: float,
         max_lines: int = 2) -> list[str]:
    """Строки по словам, не шире `width`; хвост — с многоточием."""
    words = text.split()
    lines: list[str] = []
    index = 0
    while index < len(words) and len(lines) < max_lines:
        line = words[index]
        index += 1
        while index < len(words):
            trial = f"{line} {words[index]}"
            if draw.textlength(trial, font=font) <= width:
                line = trial
                index += 1
            else:
                break
        lines.append(line)
    if index < len(words):
        lines[-1] = _cut(draw, lines[-1], font, width)
    elif lines and draw.textlength(lines[-1], font=font) > width:
        # Одно слово длиннее строки: режем по буквам.
        lines[-1] = _cut(draw, lines[-1], font, width)
    return lines


def _pairs(number: int) -> str:
    if number % 10 == 1 and number % 100 != 11:
        return "пара"
    if number % 10 in (2, 3, 4) and number % 100 not in (12, 13, 14):
        return "пары"
    return "пар"


def _day_span(lessons: list[dict], bells: dict) -> tuple[str, str] | None:
    """Начало первой и конец последней пары дня по звонкам; None — не по чему считать."""
    spans = [bells[str(l.get("n"))] for l in lessons
             if isinstance(l.get("n"), int) and isinstance(bells.get(str(l.get("n"))), list)
             and len(bells[str(l.get("n"))]) == 2]
    if not spans:
        return None
    return min(span[0] for span in spans), max(span[1] for span in spans)


def _date_text(day: dt.date) -> str:
    return f"{WEEKDAYS[day.weekday()]}, {day.day} {MONTHS[day.month - 1]}"


def _room_text(lesson: dict) -> str | None:
    room = lesson.get("r")
    online = lesson.get("o")
    if room:
        # При онлайн-паре в `r` номер комнаты, а не кабинет.
        return str(room) if online else f"каб. {room}"
    return "онлайн" if online else None


def _time_text(lesson: dict, bells: dict) -> str:
    number = lesson.get("n")
    word = f"{number} {_pairs(number)}" if isinstance(number, int) else "Пара"
    span = bells.get(str(number))
    if isinstance(span, list) and len(span) == 2:
        return f"{word} · {span[0]}–{span[1]}"
    return word


def _person(name: str) -> str:
    """«Трухачев Даниил Дмитриевич» → «Трухачев Д. Д.»: на картинке место тесно, а
    фамилии с инициалами хватает, чтобы узнать человека (как в клиентах)."""
    parts = name.split()
    if len(parts) < 2:
        return name
    return parts[0] + " " + " ".join(part[0] + "." for part in parts[1:])


def _capitalize(text: str) -> str:
    """Короткое название приходит строчным («алгоритмы», «физра») — заголовок карточки
    с маленькой буквы читался бы опечаткой."""
    return text[:1].upper() + text[1:] if text else text


def _parts(draw: ImageDraw.ImageDraw, lesson: dict, bells: dict,
           inner: int) -> dict:
    subject = _capitalize(lesson.get("sn") or lesson.get("s") or "")
    teacher = ", ".join(_person(name) for name in (lesson.get("t") or []))
    room = _room_text(lesson)
    # Длинный кабинет и длинное ФИО в одну строку не встают: тогда
    # преподаватель уходит под кабинет.
    stacked = bool(room and teacher) and (
        draw.textlength(room, font=_font(36))
        + draw.textlength(teacher, font=_font(34))
        + 24 > inner
    )
    return {
        "time": _time_text(lesson, bells),
        "lines": _fit(draw, subject, _font(44, bold=True), inner, 2),
        "room": room,
        "teacher": teacher,
        "stacked": stacked,
        "cancelled": bool(lesson.get("x")),
    }


def _height(parts: dict, pad: int, gap: int) -> int:
    height = 2 * pad + TIME_LINE + gap + len(parts["lines"]) * SUBJECT_LINE
    if parts["room"] or parts["teacher"]:
        height += gap + BOTTOM_LINE * (2 if parts["stacked"] else 1)
    return height


def _draw_card(draw: ImageDraw.ImageDraw, parts: dict, top: int, pad: int,
               gap: int) -> None:
    left = MARGIN + pad
    inner = WIDTH - 2 * MARGIN - 2 * pad
    height = _height(parts, pad, gap)
    draw.rounded_rectangle((MARGIN, top, WIDTH - MARGIN, top + height),
                           radius=RADIUS, fill=CARD)

    time_font = _font(36)
    draw.text((left, top + pad), parts["time"], font=time_font, fill=QUIET)
    if parts["cancelled"]:
        word = "отменена"
        right = WIDTH - MARGIN - pad
        draw.text((right - draw.textlength(word, font=time_font), top + pad),
                  word, font=time_font, fill=ACCENT)

    y = top + pad + TIME_LINE + gap
    subject_font = _font(44, bold=True)
    for line in parts["lines"]:
        draw.text((left, y), line, font=subject_font, fill=MAIN)
        if parts["cancelled"]:
            width = draw.textlength(line, font=subject_font)
            middle = y + SUBJECT_LINE // 2 + 4
            draw.line((left, middle, left + width, middle), fill=MAIN, width=3)
        y += SUBJECT_LINE

    if not (parts["room"] or parts["teacher"]):
        return
    y += gap
    used = 0
    if parts["room"]:
        room_font = _font(36)
        room = _ellipsis(draw, parts["room"], room_font, inner)
        draw.text((left, y), room, font=room_font, fill=ACCENT)
        used = int(draw.textlength(room, font=room_font)) + 24
    if parts["teacher"]:
        teacher_font = _font(34)
        if parts["stacked"]:
            name = _ellipsis(draw, parts["teacher"], teacher_font, inner)
            draw.text((left, y + BOTTOM_LINE), name,
                      font=teacher_font, fill=QUIET)
        else:
            name = _ellipsis(draw, parts["teacher"], teacher_font, inner - used)
            width = draw.textlength(name, font=teacher_font)
            draw.text((WIDTH - MARGIN - pad - width, y + 2), name,
                      font=teacher_font, fill=QUIET)


def _card_plan(draw: ImageDraw.ImageDraw, lessons: list[dict], bells: dict,
               inner: int, available: int) -> tuple[list[dict], int, int]:
    """Размеры карточек и укладка, в которую они влезают по высоте.

    Обычно хватает широких отступов; шесть пар с двумя строками предмета —
    единственный случай, где поля сжимаются.
    """
    parts = [_parts(draw, lesson, bells, inner) for lesson in lessons]
    for pad, gap in PROFILES:
        total = sum(_height(item, pad, gap) for item in parts) + gap * (len(parts) - 1)
        if total <= available:
            return parts, pad, gap
    # Не влезло и в тесной укладке: у предметов оставляем по строке.
    for item in parts:
        item["lines"] = item["lines"][:1]
    return parts, *PROFILES[-1]


def render(payload: dict, day: dt.date) -> bytes:
    """PNG дня из тела /v1/schedule с `days=1`."""
    body = payload["days"][0]
    image = Image.new("RGB", (WIDTH, HEIGHT), BG)
    draw = ImageDraw.Draw(image)
    name_font = _font(56, bold=True)
    left = MARGIN
    inner = WIDTH - 2 * MARGIN

    draw.text((left, 64), _ellipsis(draw, payload["gn"], name_font, inner),
              font=name_font, fill=MAIN)
    draw.text((left, 138), _date_text(day), font=_font(36), fill=QUIET)
    lessons = body.get("l") or []
    bells = body.get("bl") or payload.get("bells") or {}
    top = 190
    # Сразу видно, во сколько начинается и кончается день: по этой строке решают,
    # ехать ли к первой паре.
    span = _day_span(lessons, bells)
    if span is not None:
        draw.text((left, top), f"Пары с {span[0]} до {span[1]}", font=_font(32), fill=QUIET)
        top += 46
    state = body.get("st")
    if state in STATES:
        draw.text((left, top), STATES[state], font=_font(32), fill=QUIET)
        top += 46
    top += 30
    bottom = HEIGHT - 120

    if not lessons:
        draw.text((left, top + 20), "Пар нет", font=_font(56, bold=True), fill=MAIN)
    else:
        parts, pad, gap = _card_plan(draw, lessons[:MAX_CARDS], bells, inner,
                                     bottom - top)
        # Карточки идут сразу под шапкой с ровным небольшим зазором: пустое место
        # остаётся внизу, а не раздувает промежутки между парами.
        y = top
        for item in parts:
            _draw_card(draw, item, y, pad, gap)
            y += _height(item, pad, gap) + gap
        extra = len(lessons) - MAX_CARDS
        if extra > 0:
            draw.text((left, y + 4), f"и ещё {extra}", font=_font(36), fill=QUIET)

    footer_font = _font(28)
    width = draw.textlength(FOOTER, font=footer_font)
    draw.text(((WIDTH - width) / 2, HEIGHT - 64), FOOTER,
              font=footer_font, fill=QUIET)

    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    return buffer.getvalue()


def day_png(payload: dict, day: dt.date, directory: pathlib.Path) -> bytes:
    """PNG дня из кэша на диске или после рендера.

    Ключ — группа, день и поколение снимка: после обновления листа картинка
    перерисуется, а тот же день, спрошенный снова, отдаётся файлом.
    """
    key = hashlib.blake2b(
        f"{payload['g']}|{day.isoformat()}|{payload.get('gen', '')}|{RENDER_VERSION}".encode(),
        digest_size=16,
    ).hexdigest()
    path = directory / f"{key}.png"
    try:
        return path.read_bytes()
    except FileNotFoundError:
        pass

    png = render(payload, day)
    _store(path, png)
    return png


def _store(path: pathlib.Path, data: bytes) -> None:
    """Файл целиком или никак: читатель не увидит половину PNG."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        os.replace(name, path)
    except BaseException:
        pathlib.Path(name).unlink(missing_ok=True)
        raise
