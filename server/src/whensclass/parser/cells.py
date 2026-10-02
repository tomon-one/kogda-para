"""Разбор одной ячейки таблицы колледжа.

Здесь собраны все грязные частности источника, чтобы при очередной переделке
таблицы править приходилось в одном файле. Наблюдённые случаи описаны в
docs/source-format.md.
"""

from __future__ import annotations

import logging
import re

from ..domain.models import Lesson
from ..domain.teachers import PATRONYMIC_RE
from .groups import _distance, _warn_once  # неувязка листа — состояние, а не событие: в журнал раз

log = logging.getLogger(__name__)

# Тип занятия таблица пишет в хвосте названия: «Информатика (Лек)».
_KIND_RE = re.compile(r"\s*\(\s*([^()]{1,12}?)\s*\)\s*$")
_KIND_ANY_RE = re.compile(r"\(\s*([^()]{1,12}?)\s*\)")

# Таблицу заполняют руками, и тип встречается в разном регистре («Лек», «лек»,
# «ПР»). Одно написание — чтобы виджет не показывал две пометки одного типа.
_KNOWN_KINDS = {
    k.casefold(): k
    for k in ("Лек", "Пр", "Лаб", "Конс", "Экз", "Зач", "Сем", "Диф.зач", "Курс.р.")
}

# Отмену дописывают как придётся: отдельной строкой внутри ячейки,
# в конце названия предмета («Безопасность жизнедеятельности (Лек) Отмена»),
# в колонке аудитории («ОТМЕНА 279») и с причиной следом («Отмена
# преподаватель заболел»). Ищем слово где угодно, но целиком.
# «Отменена», «отменён», «отм.» — тоже отмена. Но не «отметка».
_CANCEL_RE = re.compile(r"\bотмен\w*|\bотм\b\.?", re.IGNORECASE)

# Ссылка на вебинар попадается и слитно с подписью: «онлайнhttps://...».
_URL_RE = re.compile(r"https?://\S+")

# Аудитория — это номер, иногда с буквой корпуса или через дробь («55/1»),
# иногда со словом-зданием впереди («Восход 222», «Спортзал 2»). Всё
# остальное после слова «отмена» в той же колонке — причина, а не место.
_ROOM_RE = re.compile(r"^(?:[А-ЯЁA-Z][\w.]*\s+)?\d{1,4}(?:[а-яА-Я]|/\d{1,3})?$")

# «X (Пр) ЗАМЕНА Y (Лек)»: пару X заменили парой Y. Название, тип и
# преподаватель — у Y, иначе студентов позовут к заменённому преподавателю.
# Слово — целиком: «заменитель» не замена.
_REPLACED_RE = re.compile(r"\bзамена\b[\s\-–—:,.]*", re.IGNORECASE)

# «преподаватель на онлайн, студенты в кабинете 269»: пара очная — студенты
# в кабинете, преподаватель на связи по ссылке. Номер нужен как аудитория, а не
# в хвосте названия, который виджеты обрезают.
_STUDENTS_IN_ROOM_RE = re.compile(
    r"(?:преподавател\w*\s+(?:на\s+)?(?:онлайн\w*|дистанц\w*)\s*[,;.]?\s*)?"
    r"студент\w*\s+в\s+(?:кабинет\w*|аудитори\w*|ауд\.?)\s*№?\s*"
    r"([\wА-Яа-яЁё/.\-]+?)[\s.,;]*$",
    re.IGNORECASE,
)

# «X (Лек) Отмена Кураторский час»: пару X не отменили, а отдали кураторскому
# часу — та же замена. Отменой её делать нельзя: час идёт, а напоминания не будет.
_CANCEL_FOR_RE = re.compile(
    r"\bотмен\w*[\s\-–—:,.]*(?:замена[\s\-–—:,.]*)?(?=(?:кураторск|классн)\w*\s+час)",
    re.IGNORECASE,
)
# То же с причиной между «отмена» и часом, и с опечаткой в слове: «Иностранный
# язык (Пр) Отмена Преподаватель заболел. Куратрский час». Причина — в приписку.
_CANCEL_REASON_FOR_RE = re.compile(
    r"\bотмен\w*[\s\-–—:,.]*(?P<why>\S.{0,80}?)[\s.,;:]+"
    r"(?P<hour>(?:кура?т\w*|классн\w*)\s+час)[\s.]*$",
    re.IGNORECASE,
)
# «замена, кураторский час» в строке преподавателя, перед именем: пару отдали
# часу. Имя, приклеенное без пробела, — отдельно.
_TEACHER_ROW_FOR_RE = re.compile(
    r"^\s*замена[\s,.:;-]*(?P<hour>(?:кура?т\w*|классн\w*)\s+час)[\s,.;:-]*",
    re.IGNORECASE,
)

# Опечатка в слове «онлайн» — «онлай», «ондлайн», «онлдайн»: без неё пара
# стала бы очной с аудиторией-словом.
_ONLINE_WORD = "онлайн"
_ROOM_NUMBER_TAIL = re.compile(
    r"^([а-яё]+)[\s.,:;()№#-]*(?:(\d{1,3})[\s.,:;()]*)?$", re.IGNORECASE
)

# Прочерк или «нет» вместо предмета или аудитории — пусто, а не пара «—» в
# 9:00 и не «каб. -».
_NOTHING_RE = re.compile(r"^(?:[\W_]*|нет)$", re.IGNORECASE)

# «Элжур» в колонке аудитории (с 25.09.2026; 02.10 — 486 пар у 159 групп) —
# задание в электронном журнале вместо пары, по сути отмена, а не кабинет.
_JOURNAL_RE = re.compile(
    r"^(?:эл\.?\s*-?\s*жур(?:нал)?|электронный\s+журнал)[\s.]*$", re.IGNORECASE
)
JOURNAL_NOTE = "задание в электронном журнале"

# Служебная заглушка колледжа вместо имени: не человек, в списке ей не место.
# «Куратор» — должность, а не фамилия; «Кураторова» — фамилия, поэтому после
# слова нужна граница. Заглушка — только служебное слово целиком: «Куратор
# Фальконер Квеллкрист Харлановна» и «Преподаватель Петров В. П.» — люди, их
# имена оставляет `_people`.
_VACANCY_RE = re.compile(r"^(?:вакансия\b.*|куратор|преподаватель)\W*$", re.IGNORECASE)
# Должность перед именем — не часть имени: «Куратор: Фальконер К. Х.».
_ROLE_PREFIX_RE = re.compile(r"^(?:куратор|преподаватель)\W+(?=\w)", re.IGNORECASE)

# Имя без единой буквы — тоже не человек: прочерк, вопрос, точка как заглушка
# («-», «?», «Миллер Д. Х., .»). Такое имя дало бы пустой идентификатор, и
# индекс преподавателей падал бы на каждом обращении.
_HAS_LETTER = re.compile(r"[^\W\d_]")

# ФИО как их пишет колледж: «Фамилия Имя Отчество» или «Фамилия И. О.».
# По нему делят двоих без разделителя («Ковач Такеси Лев Ортега Кристин
# Франсисковна») и отрезают приписку («замена, кураторский часМатвеев А. А.»):
# иначе в /v1/teachers появится несуществующий человек.
_NAME = r"[А-ЯЁ][а-яё]+"
FIO_RE = re.compile(
    rf"{_NAME}(?:-{_NAME})?\s+(?:{_NAME}\s+{_NAME}|[А-ЯЁ]\.\s*[А-ЯЁ]\.?)"
)

# Онлайн-пару чаще всего помечают словом в колонке аудитории, а ссылку
# дают позже или вовсе в чате группы: на 8 сентября таких пар в листе
# 266, а со ссылкой — единицы. Значит «онлайн» это не название
# аудитории, а состояние пары. Сверяем ячейку целиком: «онлайн-центр»
# было бы зданием, а не вебинаром.
#
# «онлайн 12» — онлайн-комната с номером. Появилось с недели 14.09.2026:
# полтора десятка комнат (2–15) раздают по парам, как кабинеты, номер ни к
# преподавателю, ни к предмету не привязан. Это по-прежнему состояние пары,
# а номер — её место внутри онлайна; в JSON он уходит в `r` при `o: 1`.
# Пишут его как придётся: «Онлайн.», «онлайн №12», «онлайн (12)», «дистант 3».
_ONLINE_RE = re.compile(
    r"^(?:онлайн|он-лайн|online|on-line|дистант|дистанционно|удал[её]нно)"
    r"[\s.,:;()№#-]*(?:(\d{1,3})[\s.,:;()]*)?$",
    re.IGNORECASE,
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
            if cancelled and tail == "keep" and not _ROOM_RE.match(line):
                # «отмена» / «преподаватель заболел» строкой ниже: это
                # причина, а не аудитория.
                note_parts.append(line)
                continue
            kept.append(line)
            continue
        cancelled = True
        head = line[: m.start()].strip()
        rest = line[m.end():].strip(" .,;:-")
        if head:
            kept.append(head)
        if rest:
            # В колонке аудитории хвост обычно сам номер, но пишут туда и
            # причину: «отмена, преподаватель заболел». Номер — место, прочее —
            # в примечание, а не «Где: преподаватель заболел».
            room_like = tail == "keep" and bool(_ROOM_RE.match(rest))
            # В колонке предмета хвост — название, только если названия ещё нет
            # ни в одной строке ячейки: «Отмена крепостного права» — предмет, а
            # «Иностранный язык (Пр)» / «Отмена Преподаватель заболел» — причина.
            subject_like = tail == "note" and not head and not kept
            (kept if room_like or subject_like else note_parts).append(rest)

    note = " ".join(note_parts).strip() or None
    return "\n".join(kept).strip(), cancelled, note


def _split_kind(subject: str) -> tuple[str, str | None]:
    # Сначала ищем знакомый тип где угодно: в ячейках с припиской «Замена ...»
    # он оказывается в середине названия, а не в хвосте.
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
        _warn_once(("тип", raw, subject), "незнакомый тип занятия %r в %r", raw, subject, logger=log)
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
        # Запятая наравне с косой чертой: «Быкова А. С., Фролова Д. А.» —
        # два человека, а не один несуществующий.
        for name in re.split(r"[/,]", line):
            # Точку не трогаем: она часть инициалов — «Сеченов Д. С.».
            cleaned = " ".join(name.split()).strip(" ,;")
            if not cleaned or _VACANCY_RE.match(cleaned):
                continue
            cleaned = _ROLE_PREFIX_RE.sub("", cleaned)
            if not cleaned:
                continue
            if not _HAS_LETTER.search(cleaned):
                _warn_once(
                    ("не имя", cleaned),
                    "в строке преподавателей %r вместо имени — пропускаю", cleaned, logger=log,
                )
                continue
            names.extend(_people(cleaned))
    return tuple(names)


# Название пары, когда в ячейке предмета ничего нет, а пара есть.
PLACEHOLDER = "Занятие"


def _people(text: str) -> list[str]:
    """Кусок строки преподавателей -> люди в нём.

    Два ФИО подряд — два человека; ФИО, к которому прилипла служебная
    приписка, — один, без приписки. Кусок, где полного ФИО нет, остаётся как
    есть, если начинается с заглавной («Смит Джон», «Ли»), а строчная
    приписка без имени («замена», «кураторский час») — не человек.
    """
    found = [m.group(0) for m in FIO_RE.finditer(text)]
    if len(found) >= 2 or (found and found[0] != text):
        rest = FIO_RE.sub(" ", text).strip(" ,;.")
        if PATRONYMIC_RE.match(rest):
            # «Щетинкин Артем Сергеевич Анастасия Дмитриевна» — два куратора,
            # у второй не написана фамилия; это человек, а не приписка.
            return [" ".join(name.split()) for name in found] + [rest]
        if rest:
            _warn_once(
                ("приписка", text, rest),
                "в строке преподавателей %r кроме имён — %r: пропускаю приписку", text, rest, logger=log,
            )
        return [" ".join(name.split()) for name in found]
    if found or text[:1].isupper():
        return [text]
    _warn_once(("нет имени", text), "в строке преподавателей %r нет имени — пропускаю", text, logger=log)
    return []


def replaced_subject(subject: str) -> tuple[str, str] | None:
    """«X (Пр) ЗАМЕНА Y (Лек)» -> (X без типа, «Y (Лек)»); без замены — None."""
    m = _REPLACED_RE.search(subject)
    if not m:
        return None
    before, after = subject[: m.start()].strip(" ,;.-"), subject[m.end():].strip()
    if not before or not after:
        # «Замена» без одной из сторон — приписка, а не замена пары.
        return None
    old_name, _ = _split_kind(before)
    return " ".join(old_name.split()).strip(" ,;.") or before, after


_UNASSIGNED_ROOMS = frozenset({"0", "1"})


def _online_room(room: str) -> str | None:
    """Онлайн ли пара по колонке аудитории: None — нет; иначе номер комнаты
    («онлайн 12» → «12») или пустая строка.

    «онлай 12», «ондлайн» — «онлайн» с опечаткой: до двух правок.
    """
    marked = _ONLINE_RE.match(room)
    if marked:
        return marked.group(1) or ""
    m = _ROOM_NUMBER_TAIL.match(room)
    if not m:
        return None
    word = m.group(1).casefold()
    if word[:2] != "он" or _distance(word, _ONLINE_WORD) > 2:
        return None
    _warn_once(
        ("онлайн", room), "в колонке аудитории %r — считаю опечаткой в «онлайн»", room,
        logger=log,
    )
    return m.group(2) or ""


_INSTEAD = "вместо: "


def replaced_of(lesson: Lesson) -> str | None:
    """Название заменённой пары, если `lesson` — замена (см. `parse_lesson`)."""
    for part in (lesson.note or "").split("; "):
        if part.startswith(_INSTEAD):
            return part[len(_INSTEAD):]
    return None


def parse_lesson(
    number: int,
    subject_raw: str | None,
    room_raw: str | None,
    teacher_raw: str | None,
) -> Lesson | None:
    """Собирает пару из трёх ячеек. None, если пары нет."""
    template = normalize(teacher_raw) in ("", "Преподаватель")
    if normalize(subject_raw) == "Дисциплина" and template:
        # Незаполненная клетка по шаблону шапки — пары нет.
        _warn_once(("шаблон", number), "в клетке пары заготовка «Дисциплина» — пары нет",
                   logger=log)
        return None
    subject_text = normalize(subject_raw)
    handed = _CANCEL_FOR_RE.search(subject_text)
    handed_why = None
    if handed and subject_text[: handed.start()].strip():
        subject_text = _CANCEL_FOR_RE.sub("ЗАМЕНА ", subject_text, count=1)
    elif (late := _CANCEL_REASON_FOR_RE.search(subject_text)) \
            and subject_text[: late.start()].strip():
        handed_why = late.group("why").strip(" .,;:")
        subject_text = f"{subject_text[: late.start()].rstrip()} ЗАМЕНА Кураторский час" \
            if late.group("hour").casefold().startswith("кур") \
            else f"{subject_text[: late.start()].rstrip()} ЗАМЕНА Классный час"
    teacher_text = normalize(teacher_raw)
    row_for = _TEACHER_ROW_FOR_RE.match(teacher_text)
    if row_for and "замена" not in subject_text.casefold():
        hour = "Кураторский час" if row_for.group("hour").casefold().startswith("кур") \
            else "Классный час"
        subject_text = f"{subject_text} ЗАМЕНА {hour}"
        teacher_text = teacher_text[row_for.end():]
    subject, cancel_a, note = _extract_cancellation(subject_text, tail="note")
    room_text, cancel_b, room_note = _extract_cancellation(normalize(room_raw), tail="keep")
    if _NOTHING_RE.match(subject):
        subject = ""
    if _NOTHING_RE.match(room_text):
        room_text = ""
    note = note or room_note or handed_why
    teachers = split_teachers(teacher_text)
    cancelled = cancel_a or cancel_b
    if _JOURNAL_RE.match(room_text):
        room_text, cancelled = "", True
        note = f"{note}; {JOURNAL_NOTE}" if note else JOURNAL_NOTE

    if not subject and not room_text and not teachers:
        if not cancelled:
            return None
        # В ячейке написано только «отмена». Молча выбросить её — значит
        # показать окно там, где у группы стояла пара и её отменили: человек
        # не узнает, что пара была.
        return Lesson(number=number, subject=PLACEHOLDER, cancelled=True, note=note)

    subject = subject.replace("\n", " ").strip()
    replaced = replaced_subject(subject)
    if replaced is not None:
        old_name, subject = replaced
        # Прежний преподаватель — первым, новый — последним. Один на двоих
        # — чей, разбирает обход листа по остальным ячейкам (`parse_sheet`).
        teachers = teachers[-1:]
        instead = f"{_INSTEAD}{old_name}"
        note = f"{note}; {instead}" if note else instead

    in_room = _STUDENTS_IN_ROOM_RE.search(subject)
    if in_room:
        subject = subject[: in_room.start()].strip(" ,;.")

    subject, kind = _split_kind(subject)
    if replaced is not None and subject.isupper():
        # «ЗАМЕНА КУРАТОРСКИЙ ЧАС».
        subject = subject[:1] + subject[1:].lower()

    url = None
    room: str | None = None
    online = False
    if room_text:
        # Ссылку ищем где угодно в ячейке: пишут и «онлайнhttps://...» слитно.
        found = _URL_RE.search(room_text)
        if found:
            url = found.group(0)
        # Что рядом со ссылкой — место: «269 https://…» — кабинет, «онлайн 12
        # https://…» — комната. Но только кабинет или «онлайн N»: прочее
        # («Ссылка:», «(пароль 1234)») уходит в приписку, иначе онлайн-пара
        # станет очной с кабинетом-словом. Из нескольких ссылок берётся первая.
        rest = _URL_RE.sub(" ", room_text) if url else room_text
        room = " ".join(rest.replace("\n", " ").split()).strip(" ,;") or None
        if url is not None and room is not None and not _ROOM_RE.match(room) \
                and _online_room(room) is None and room not in _UNASSIGNED_ROOMS:
            note = f"{note}; {room}" if note else room
            room = None
        if room is not None:
            online_room = _online_room(room)
            if online_room is not None:
                # Слово «онлайн» — не место, а состояние пары: иначе пара
                # «станет онлайн», когда к ней наконец допишут ссылку.
                online, room = True, online_room or None
            elif room in _UNASSIGNED_ROOMS:
                # «0» и «1» в колонке кабинета у очной пары — кабинет ещё не
                # назначен (так колледж пишет; 27.09.2026 в листе 34 и 30
                # раз), а не «каб. 0».
                room = None

    if url is None:
        # Иногда ссылку кладут в колонку предмета, и пара называлась адресом.
        found = _URL_RE.search(subject)
        if found:
            url = found.group(0)
            subject = " ".join(subject.replace(found.group(0), " ").split())

    if in_room:
        # Очная пара со ссылкой для преподавателя: место — кабинет, а в JSON
        # у неё нет «o» (docs/api.md).
        room, online = in_room.group(1).strip(" .,;"), False

    if subject[:1].islower():
        # Первая буква — заглавная у всех пар: иначе «кураторский час» и
        # «Кураторский час» — разные предметы, и сравнение пришлёт «добавилась»
        # и «убрали» о той же паре. После ссылки: адрес не трогаем.
        subject = subject[:1].upper() + subject[1:]

    if not subject:
        # Пустое название выглядит поломкой, а выдумывать предмет нельзя —
        # говорим то, что знаем точно.
        subject = "Занятие онлайн" if url else PLACEHOLDER

    return Lesson(
        number=number,
        subject=subject,
        kind=kind,
        teachers=teachers,
        room=room,
        url=url,
        # Ссылка без места — онлайн; ссылка при кабинете — очная пара, где
        # преподаватель на связи по ссылке (docs/api.md).
        online=online or (url is not None and not in_room and room is None),
        cancelled=cancelled,
        note=note,
    )
