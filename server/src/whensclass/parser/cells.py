"""Разбор одной ячейки таблицы колледжа.

Здесь собраны все грязные частности источника, чтобы при очередной переделке
таблицы править приходилось в одном файле. Наблюдённые случаи описаны в
docs/source-format.md.
"""

from __future__ import annotations

import logging
import re

from ..domain.models import Lesson

log = logging.getLogger(__name__)

# Тип занятия таблица пишет в хвосте названия: «Информатика (Лек)».
_KIND_RE = re.compile(r"\s*\(\s*([^()]{1,12}?)\s*\)\s*$")
_KIND_ANY_RE = re.compile(r"\(\s*([^()]{1,12}?)\s*\)")

# Заполняют руками, поэтому один и тот же тип встречается в разном регистре
# («Лек», «лек», «ПР»). Приводим к одному написанию, чтобы виджет не показывал
# две разные пометки для одного и того же.
_KNOWN_KINDS = {
    k.casefold(): k
    for k in ("Лек", "Пр", "Лаб", "Конс", "Экз", "Зач", "Сем", "Диф.зач", "Курс.р.")
}

# Отмену дописывают как придётся: отдельной строкой внутри ячейки,
# в конце названия предмета («Безопасность жизнедеятельности (Лек) Отмена»),
# в колонке аудитории («ОТМЕНА 279») и с причиной следом («Отмена
# преподаватель заболел»). Ищем слово где угодно, но целиком.
_CANCEL_RE = re.compile(r"\bотмена\b", re.IGNORECASE)

# Ссылка на вебинар попадается и слитно с подписью: «онлайнhttps://...».
_URL_RE = re.compile(r"https?://\S+")

# Аудитория — это номер, иногда с буквой корпуса. Всё остальное после слова
# «отмена» в той же колонке — причина, а не место.
_ROOM_RE = re.compile(r"^\d{1,4}[а-яА-Я]?$")

# Служебная заглушка колледжа вместо имени: не человек, в списке ей не место.
_VACANCY_RE = re.compile(r"^вакансия\b", re.IGNORECASE)

# Онлайн-пару чаще всего помечают словом в колонке аудитории, а ссылку
# дают позже или вовсе в чате группы: на 8 сентября таких пар в листе
# 266, а со ссылкой — единицы. Значит «онлайн» это не название
# аудитории, а состояние пары. Сверяем ячейку целиком: «онлайн-центр»
# был бы зданием, а не вебинаром.
_ONLINE_WORDS = frozenset(
    ("онлайн", "он-лайн", "online", "on-line", "дистант",
     "дистанционно", "удаленно", "удалённо")
)


def normalize(raw: str | None) -> str:
    """Чистит пробелы, но сохраняет переводы строк: в них смысл.

    Внутри ячейки вторая строка несёт отдельное сообщение — чаще всего отмену.
    """
    if not raw:
        return ""
    text = raw.replace("\xa0", " ").replace("\r\n", "\n").replace("\r", "\n")
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in text.split("\n")]
    return "\n".join(line for line in lines if line)


def _extract_cancellation(text: str, tail: str) -> tuple[str, bool, str | None]:
    """Убирает пометку отмены, возвращает остаток, признак и приписку.

    Что делать с текстом после слова «отмена», зависит от колонки: в колонке
    аудитории это сама аудитория, в колонке предмета — причина.

        _extract_cancellation("ОТМЕНА 279", tail="keep")
            -> ("279", True, None)
        _extract_cancellation("БЖД (Лек) Отмена преподаватель заболел", "note")
            -> ("БЖД (Лек)", True, "преподаватель заболел")
    """
    kept: list[str] = []
    note_parts: list[str] = []
    cancelled = False

    for line in text.split("\n"):
        m = _CANCEL_RE.search(line)
        if not m:
            kept.append(line)
            continue
        cancelled = True
        head = line[: m.start()].strip()
        rest = line[m.end():].strip(" .,;:-")
        if head:
            kept.append(head)
        if rest:
            # В колонке аудитории хвост это обычно сам номер, но пишут туда и
            # причину: «отмена, преподаватель заболел». Номер оставляем местом,
            # остальное уводим в примечание — иначе приложение показывало
            # «Где: преподаватель заболел».
            # Хвост после слова «отмена» это причина — но только если перед
            # словом что-то было. «Отмена крепостного права» иначе оставляла
            # пару вовсе без названия: голова пустая, всё остальное в причине.
            room_like = tail == "keep" and bool(_ROOM_RE.match(rest))
            subject_like = tail == "note" and not head
            (kept if room_like or subject_like else note_parts).append(rest)

    note = " ".join(note_parts).strip() or None
    return "\n".join(kept).strip(), cancelled, note


def _split_kind(subject: str) -> tuple[str, str | None]:
    # Сначала ищем знакомый тип где угодно: в ячейках с припиской «Замена ...»
    # он оказывается в середине, и хвостовой поиск его не находил — тип
    # оставался сырым текстом внутри названия.
    for m in _KIND_ANY_RE.finditer(subject):
        kind = _KNOWN_KINDS.get(m.group(1).strip().casefold())
        if kind:
            rest = (subject[: m.start()] + " " + subject[m.end():]).strip()
            return " ".join(rest.split()), kind

    m = _KIND_RE.search(subject)
    if not m:
        return subject.strip(), None
    raw = m.group(1).strip()
    kind = _KNOWN_KINDS.get(raw.casefold())
    if kind is None:
        # Новый вид занятия не должен ронять весь день — запоминаем как есть.
        log.warning("незнакомый тип занятия %r в %r", raw, subject)
        kind = raw
    return subject[: m.start()].strip(), kind


def split_teachers(text: str) -> tuple[str, ...]:
    """Разбирает ячейку с преподавателями.

    Их бывает несколько: обычно каждый со своей строки, но встречается и запись
    через косую черту — «Старостина Екатерина Александровна/ Пикулина Лидия
    Егоровна». Одним именем это выглядит как человек с двойной фамилией.
    """
    names: list[str] = []
    for line in text.splitlines():
        # Запятая наравне с косой чертой: «Быкова А. С., Фролова Д. А.» это
        # два человека. Склеенные, они превращались в несуществующего
        # преподавателя, а у настоящих пара пропадала из их расписания.
        for name in re.split(r"[/,]", line):
            # Точку не трогаем: она часть инициалов — «Иванов И. И.».
            cleaned = " ".join(name.split()).strip(" ,;")
            if cleaned and not _VACANCY_RE.match(cleaned):
                names.append(cleaned)
    return tuple(names)


def parse_lesson(
    number: int,
    subject_raw: str | None,
    room_raw: str | None,
    teacher_raw: str | None,
) -> Lesson | None:
    """Собирает пару из трёх ячеек. None, если пары нет."""
    subject, cancel_a, note = _extract_cancellation(normalize(subject_raw), tail="note")
    room_text, cancel_b, room_note = _extract_cancellation(normalize(room_raw), tail="keep")
    note = note or room_note
    teacher_text = normalize(teacher_raw)

    if not subject and not room_text and not teacher_text:
        return None

    subject, kind = _split_kind(subject.replace("\n", " ").strip())

    url = None
    room: str | None = None
    online = False
    if room_text:
        # Ссылку ищем где угодно в ячейке, а не только в начале: пишут и
        # «онлайнhttps://...» слитно, и тогда весь адрес уезжал в аудиторию.
        found = _URL_RE.search(room_text)
        if found:
            url = found.group(0)
        else:
            room = room_text.replace("\n", " ").strip()
            if room.casefold() in _ONLINE_WORDS:
                # Слово «онлайн» — не место. Оставляя его аудиторией,
                # мы считали такую пару очной, и приложение объявляло
                # «пара стала онлайн» ровно тогда, когда к давно
                # онлайновой паре наконец дописывали ссылку.
                online, room = True, None

    if url is None:
        # Иногда ссылку кладут в колонку предмета, и пара называлась адресом.
        found = _URL_RE.search(subject)
        if found:
            url = found.group(0)
            subject = " ".join(subject.replace(found.group(0), " ").split())

    if not subject and url:
        # В ячейке не было ничего, кроме ссылки. Пустое название выглядит
        # поломкой, а выдумывать предмет нельзя — говорим то, что знаем точно.
        subject = "Занятие онлайн"

    teachers = split_teachers(teacher_text)

    return Lesson(
        number=number,
        subject=subject,
        kind=kind,
        teachers=teachers,
        room=room,
        url=url,
        online=online or url is not None,
        cancelled=cancel_a or cancel_b,
        note=note,
    )
