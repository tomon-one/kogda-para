"""Разбор ячейки: каждый случай взят из живой таблицы, не выдуман."""

import pytest

from whensclass.parser.cells import parse_lesson

CASES = [
    # (предмет, аудитория, преподаватель) -> ожидаемые поля
    (
        ("Информатика (Лек)", "369", "Литвиненко Наталья Николаевна"),
        dict(subject="Информатика", kind="Лек", room="369", cancelled=False),
    ),
    (
        ("Информатика (лек)", "369", ""),
        dict(kind="Лек"),  # регистр в таблице гуляет
    ),
    (
        ("Иностранный язык (ПР)", "", ""),
        dict(kind="Пр"),
    ),
    (
        ("Безопасность жизнедеятельности (Лек) Отмена", "369", ""),
        dict(subject="Безопасность жизнедеятельности", kind="Лек", cancelled=True,
             note=None),
    ),
    (
        ("Иностранный язык (Пр) Отмена Преподаватель заболел", "253", ""),
        dict(cancelled=True, note="Преподаватель заболел", room="253"),
    ),
    (
        ("Введение в проектную деятельность\nОТМЕНА", "454\nОТМЕНА", ""),
        dict(subject="Введение в проектную деятельность", room="454", cancelled=True),
    ),
    (
        ("Физическая культура (Пр)", "ОТМЕНА 279", "Кузнецов Андрей Игоревич"),
        # хвост в колонке аудитории — это аудитория, а не причина
        dict(room="279", cancelled=True, note=None),
    ),
    (
        ("Иностранный язык (Пр)", "https://my.mts-link.ru/j/100000004/20000000627", ""),
        dict(url="https://my.mts-link.ru/j/100000004/20000000627", room=None),
    ),
    (
        ("Физкультура (Пр)", "Спортзал Б.Хмельницкого 2 (Б.Хмельницкого 2)", ""),
        # скобка в конце аудитории — это адрес, а не тип занятия
        dict(room="Спортзал Б.Хмельницкого 2 (Б.Хмельницкого 2)", kind="Пр"),
    ),
    (
        ('Технология выполнения работ по профессии "Графический дизайнер" (Пр)',
         "273", "Бартенева Диана Унановна"),
        dict(subject='Технология выполнения работ по профессии "Графический дизайнер"'),
    ),
    (
        ("Проектирование ИС (Курс.р.)", "", ""),
        dict(kind="Курс.р."),
    ),
]


@pytest.mark.parametrize("cell,expected", CASES)
def test_parse_lesson(cell, expected):
    lesson = parse_lesson(1, *cell)
    assert lesson is not None
    for field, value in expected.items():
        assert getattr(lesson, field) == value, field


def test_empty_cell_is_not_a_lesson():
    assert parse_lesson(1, "", "", "") is None
    assert parse_lesson(1, None, None, None) is None


def test_two_teachers_in_one_cell():
    lesson = parse_lesson(1, "Физкультура (Пр)", "", "Иванов И. И.\nПетров П. П.")
    assert lesson.teachers == ("Иванов И. И.", "Петров П. П.")


def test_unknown_kind_survives():
    """Незнакомый тип не должен ронять разбор всего дня."""
    lesson = parse_lesson(1, "Практика (Вебинар)", "", "")
    assert lesson.kind == "Вебинар"


def test_online_word_is_a_state_not_a_room():
    """«онлайн» в колонке аудитории — это не аудитория.

    Так помечено большинство онлайн-пар: ссылку дают позже или в чате. Считая
    слово названием аудитории, мы держали такую пару очной — и приложение
    объявляло «пара стала онлайн» в тот день, когда к ней дописывали ссылку.
    """
    lesson = parse_lesson(1, "Информатика (Лек)", "онлайн", "")
    assert lesson.online is True
    assert lesson.room is None


def test_online_word_survives_upper_case_and_synonyms():
    for word in ("ОНЛАЙН", "Дистанционно", "удалённо"):
        assert parse_lesson(1, "Информатика", word, "").online is True, word


def test_numbered_online_room_is_online_with_a_number():
    """«онлайн 12» — онлайн-комната с номером, с недели 14.09.2026 их четыре сотни.

    Состояние — онлайн, номер — место внутри него: в JSON `o: 1` и `r: "12"`.
    """
    lesson = parse_lesson(1, "Информатика (Лек)", "онлайн 12", "")
    assert lesson.online is True
    assert lesson.room == "12"
    for cell in ("Онлайн 3", "онлайн12", "online 15"):
        assert parse_lesson(1, "Информатика", cell, "").online is True, cell
    assert parse_lesson(1, "Информатика", "онлайн12", "").room == "12"


def test_room_zero_or_one_means_not_assigned_yet():
    """«0» и «1» у очной пары — кабинет ещё не назначен: не «каб. 0», а место
    не указано. Онлайн-комната с таким номером — другое, она остаётся."""
    for cell in ("0", "1", " 0 "):
        lesson = parse_lesson(1, "Дизайн-проектирование (Лек)", cell, "Иванова А. С.")
        assert lesson.room is None and lesson.online is False, cell
    assert parse_lesson(1, "Информатика", "онлайн 0", "").room == "0"
    assert parse_lesson(1, "Информатика", "10", "").room == "10"


@pytest.mark.parametrize("room", ["Элжур", "элжур", "Эл. журнал", "Электронный журнал"])
def test_journal_in_room_column_is_a_cancellation(room):
    """«Элжур» — задание в электронном журнале вместо пары, «грубо говоря
    отмена» (Tomon 29.09), а не кабинет: иначе переход с онлайна объявлялся
    «снова очная» (четвёртый аудит, В7 прогона 1)."""
    lesson = parse_lesson(2, "Физика (Лек)", room, "Иванов Иван Иванович")
    assert lesson.cancelled and lesson.room is None and not lesson.online
    assert lesson.note == "задание в электронном журнале"
    assert lesson.subject == "Физика" and lesson.teachers == ("Иванов Иван Иванович",)
    reason = parse_lesson(2, "Физика (Пр) Отмена Преподаватель заболел", room, "Иванов И. И.")
    assert reason.cancelled and reason.subject == "Физика"
    assert reason.note == "Преподаватель заболел; задание в электронном журнале"


def test_room_that_only_starts_with_online_stays_a_room():
    """Сверяем ячейку целиком: «онлайн-центр» был бы зданием, а не вебинаром."""
    lesson = parse_lesson(1, "Информатика", "онлайн-центр", "")
    assert lesson.online is False
    assert lesson.room == "онлайн-центр"


def test_link_means_online_too():
    lesson = parse_lesson(1, "Информатика", "https://my.mts-link.ru/j/6/0", "")
    assert lesson.online is True
    assert lesson.url == "https://my.mts-link.ru/j/6/0"


def test_ordinary_room_is_not_online():
    assert parse_lesson(1, "Информатика", "272", "").online is False


def test_two_teachers_through_slash():
    """В таблице встречается запись двух преподавателей через косую черту."""
    lesson = parse_lesson(
        1,
        "Иностранный язык (Пр)",
        "253",
        "Старостина Екатерина Александровна/ Пикулина Лидия Егоровна",
    )
    assert lesson.teachers == (
        "Старостина Екатерина Александровна",
        "Пикулина Лидия Егоровна",
    )


# --- Случаи из живой таблицы ----------------------------------------------

AUDIT_CASES = [
    (
        # Двое в одной ячейке через запятую: ГД-1126, 03.09, пара 3.
        ("Информатика (Лек)", "453", "Быкова Анна Станиславовна, Фролова Данна Алексеевна"),
        dict(teachers=("Быкова Анна Станиславовна", "Фролова Данна Алексеевна")),
    ),
    (
        # Косая черта работала и раньше — проверяем, что не сломали.
        ("Информатика", "", "Старостина Екатерина Александровна/ Пикулина Лидия Егоровна"),
        dict(teachers=("Старостина Екатерина Александровна", "Пикулина Лидия Егоровна")),
    ),
    (
        # Заглушка колледжа вместо имени: ГД-926/1, 10.09.
        ("Кураторский час", "", "Вакансия 10"),
        dict(teachers=()),
    ),
    (
        # Ссылка слитно с подписью: ГД-925/3, 04.09, пара 1.
        ("Иностранный язык", "онлайнhttps://my.mts-link.ru/j/100000004/20000000629", ""),
        dict(url="https://my.mts-link.ru/j/100000004/20000000629", room=None),
    ),
    (
        # Голая ссылка разбиралась и раньше.
        ("Информатика", "https://my.mts-link.ru/j/6/0", ""),
        dict(url="https://my.mts-link.ru/j/6/0", room=None),
    ),
    (
        # Ссылка в колонке предмета: УП-926/1, 07.09, пара 4.
        ("https://my.mts-link.ru/j/6/0", "", "Новиков Вячеслав Сергеевич"),
        dict(url="https://my.mts-link.ru/j/6/0", subject="Занятие онлайн"),
    ),
    (
        # Причина отмены в колонке аудитории: Т-1125, 07.09, пара 4.
        ("Основы бережливого производства", "отмена, преподаватель заболел", ""),
        dict(cancelled=True, note="преподаватель заболел", room=None),
    ),
    (
        # А номер после «ОТМЕНА» остаётся аудиторией, как и был.
        ("Информатика", "ОТМЕНА 279", ""),
        dict(cancelled=True, room="279", note=None),
    ),
    (
        # Замена: пара — новая, прежняя — в примечании. Тип «(Лек)» был у
        # заменённой, у новой он не написан.
        ("Безопасность жизнедеятельности (Лек) Замена Дизайн-проектирование", "453", ""),
        dict(kind=None, subject="Дизайн-проектирование",
             note="вместо: Безопасность жизнедеятельности"),
    ),
    (
        # Скобки, которые не тип занятия, названием и остаются.
        ("Иностранный язык (второй), Английский", "", ""),
        dict(kind=None, subject="Иностранный язык (второй), Английский"),
    ),
]


@pytest.mark.parametrize("cells, expected", AUDIT_CASES)
def test_audit_cases(cells, expected):
    lesson = parse_lesson(1, *cells)
    assert lesson is not None
    for field, value in expected.items():
        assert getattr(lesson, field) == value, field


def test_cancellation_at_the_start_keeps_the_subject():
    """«Отмена» в начале ячейки не должна съедать название пары.

    Хвост после слова считается причиной только если перед словом что-то было.
    Иначе «Отмена крепостного права» оставляла пару вовсе без названия: голова
    пустая, всё остальное уходило в причину.

    Пометка при этом остаётся: слово вырезается, пара считается отменённой, и
    название выходит покалеченным. Это осознанный выбор в пользу безопасной
    ошибки. Принять настоящую отмену за обычную пару — значит отправить
    человека на занятие, которого нет; обратная ошибка стоит непонятной
    строки в расписании. В нынешнем листе таких ячеек нет ни одной.
    """
    lesson = parse_lesson(1, "Отмена крепостного права (Лек)", "", "")
    assert lesson is not None
    assert lesson.subject == "Крепостного права"
    assert lesson.kind == "Лек"
    assert lesson.cancelled is True
    assert lesson.note is None


def test_reason_after_the_subject_is_still_a_reason():
    """А привычный порядок «предмет, отмена, причина» не тронут."""
    lesson = parse_lesson(1, "Иностранный язык (Пр) Отмена Преподаватель заболел", "", "")
    assert lesson is not None
    assert lesson.subject == "Иностранный язык"
    assert lesson.note == "Преподаватель заболел"


def test_two_line_cancellation_keeps_the_reason_as_a_reason():
    """Вторая строка «Отмена Преподаватель заболел» — причина, а не хвост
    названия."""
    lesson = parse_lesson(1, "Иностранный язык (Пр)\nОтмена Преподаватель заболел", "", "")
    assert lesson.subject == "Иностранный язык" and lesson.cancelled
    assert lesson.note == "Преподаватель заболел"


@pytest.mark.parametrize("room", ["55/1", "171/3", "Восход 222", "Спортзал 2", "279а"])
def test_room_after_cancellation_stays_a_room(room):
    """«ОТМЕНА 55/1» — отменена пара в 55/1, а не причина «55/1»."""
    lesson = parse_lesson(1, "Информатика", f"ОТМЕНА {room}", "")
    assert lesson.cancelled and lesson.room == room and lesson.note is None


def test_reason_in_room_column_is_still_a_reason():
    lesson = parse_lesson(1, "Информатика", "отмена, перенос на 29.10", "")
    assert lesson.room is None and lesson.note == "перенос на 29.10"


@pytest.mark.parametrize("cell,room", [
    ("Онлайн.", None), ("онлайн №12", "12"), ("онлайн (12)", "12"),
    ("дистант 3", "3"), ("ONLINE-7", "7"),
])
def test_online_is_recognised_however_it_is_written(cell, room):
    """Раньше всё это делало пару очной с аудиторией-словом."""
    lesson = parse_lesson(1, "Информатика", cell, "")
    assert lesson.online and lesson.room == room


def test_online_centre_is_still_a_building():
    assert not parse_lesson(1, "Информатика", "онлайн-центр", "").online


@pytest.mark.parametrize("cell,people", [
    ("Хертек Ая Андреевна Давыдова Анна Александровна",
     ("Хертек Ая Андреевна", "Давыдова Анна Александровна")),
    ("Мисюрова Е.С. Антонов Артем Юрьевич", ("Мисюрова Е.С.", "Антонов Артем Юрьевич")),
    ("кураторский часМатвеев Александр Игоревич", ("Матвеев Александр Игоревич",)),
    ("замена", ()),
    ("Иванов Иван Иванович", ("Иванов Иван Иванович",)),
    ("Ли", ("Ли",)),
])
def test_glued_and_service_texts_in_teacher_row(cell, people):
    """Два человека без разделителя и служебная приписка давали фантомов в
    /v1/teachers, а у настоящих пары пропадали. Пример —
    живой лист 23.09.2026."""
    assert parse_lesson(1, "Информатика", "", cell).teachers == people


def test_same_sheet_trouble_is_logged_once(caplog):
    """Неувязка листа — состояние, а не событие: раньше она писалась в журнал
    на каждом разборе, каждые двадцать минут."""
    with caplog.at_level("WARNING"):
        for _ in range(3):
            parse_lesson(1, "Информатика", "", "замена")
    assert caplog.text.count("нет имени") == 1



@pytest.mark.parametrize(
    "subject, room, teachers, expected",
    [
        # МФ-926/2, 25.09, пара 4: тип и преподаватель — новой пары.
        (
            "Математика (Пр) ЗАМЕНА Коммуникативный тренинг (Лек)", "351",
            "Романова Анастасия Юрьевна Звонцов Александр Сергеевич",
            dict(subject="Коммуникативный тренинг", kind="Лек",
                 teachers=("Звонцов Александр Сергеевич",), note="вместо: Математика"),
        ),
        # Замена отдельной строкой и прописными.
        (
            "Коммуникативный тренинг (Лек) ЗАМЕНА КУРАТОРСКИЙ ЧАС", "", "",
            dict(subject="Кураторский час", kind=None, note="вместо: Коммуникативный тренинг"),
        ),
        (
            "Иностранный язык\n.Английский ячзык (Пр)\nзамена кураторский час", "", "",
            dict(subject="Кураторский час", note="вместо: Иностранный язык .Английский ячзык"),
        ),
        (
            "Иностранный язык, Английский (Пр) ЗАМЕНА - Биология (пр)", "", "",
            dict(subject="Биология", kind="Пр"),
        ),
        # Отменённая замена: причина и прежняя пара — обе в примечании.
        (
            "Информатика (Пр) замена кураторский час", "Отмена", "",
            dict(subject="Кураторский час", cancelled=True, note="вместо: Информатика"),
        ),
    ],
)
def test_replacement_takes_the_new_lesson(subject, room, teachers, expected):
    lesson = parse_lesson(4, subject, room, teachers)
    for field, value in expected.items():
        assert getattr(lesson, field) == value, field


def test_word_replacement_alone_is_not_a_replacement():
    lesson = parse_lesson(1, "Замена", "", "")
    assert lesson.subject == "Замена" and lesson.note is None


@pytest.mark.parametrize(
    "subject, room",
    [
        ("Русский язык (Лек) преподаватель на онлайн, студенты в кабинете 269", "269"),
        ("Русский язык (Лек) преподаватель на онлайн, студенты в кабинет 369", "369"),
        ("Русский язык (Пр) преподаватель на онлайн, студенты в кабинете 171/2", "171/2"),
        ("Экономика организации (Лек) студенты в кабинете 351", "351"),
    ],
)
def test_students_in_a_room_is_an_offline_lesson_with_a_link(subject, room):
    """Л-926/3, 23.09, пара 1: студенты в кабинете, преподаватель по ссылке.
    Пара очная: кабинет — место, ссылка остаётся, «онлайн» нет."""
    lesson = parse_lesson(1, subject, "https://my.mts-link.ru/j/6/0", "Московец К. Р.")
    assert lesson.room == room
    assert lesson.url == "https://my.mts-link.ru/j/6/0"
    assert lesson.online is False
    assert "студент" not in lesson.subject and "онлайн" not in lesson.subject
    assert lesson.kind in ("Лек", "Пр")



def test_lesson_handed_to_curator_hour_is_a_replacement_not_a_cancellation():
    """«X (Лек) Отмена Кураторский час» — час идёт, пару отдали ему."""
    lesson = parse_lesson(
        3, "Организация социально-культурной деятельности (Лек) Отмена Кураторский час",
        "262", "Мисюрова Е.С. Антонов Артем Юрьевич",
    )
    assert not lesson.cancelled
    assert lesson.subject == "Кураторский час" and lesson.room == "262"
    assert lesson.note == "вместо: Организация социально-культурной деятельности"
    assert lesson.teachers == ("Антонов Артем Юрьевич",)
    # Настоящая отмена с причиной — по-прежнему отмена.
    real = parse_lesson(1, "Физика (Лек) Отмена преподаватель заболел", "262", "")
    assert real.cancelled and real.note == "преподаватель заболел"


@pytest.mark.parametrize("room", ["онлай", "ондлайн", "онлдайн", "онлай 12"])
def test_typo_in_online_is_still_online(room):
    """Опечатка делала пару очной с аудиторией-словом, а исправление —
    ложным «пара стала онлайн»."""
    lesson = parse_lesson(3, "Физика (Пр)", room, "")
    assert lesson.online and lesson.room in (None, "12")


@pytest.mark.parametrize("room", ["Спортзал 2", "Восход 222", "онлайн-центр"])
def test_rooms_that_only_look_like_online_stay_rooms(room):
    assert not parse_lesson(3, "Физика (Пр)", room, "").online


def test_reason_on_the_line_below_cancellation_is_a_note_not_a_room():
    """«отмена» / «преподаватель заболел» строкой ниже в колонке аудитории."""
    lesson = parse_lesson(
        5, "Коммуникативный тренинг (Пр)", "отмена\nпреподаватель заболел", ""
    )
    assert lesson.cancelled and lesson.room is None and lesson.note == "преподаватель заболел"
    with_room = parse_lesson(5, "Физика", "ОТМЕНА\nпреподаватель заболел\n279", "")
    assert with_room.room == "279" and with_room.note == "преподаватель заболел"


def test_second_curator_without_surname_is_a_person_but_not_a_teacher_id():
    """«Щетинкин Артем Сергеевич Анастасия Дмитриевна» — два куратора;
    у второй нет фамилии, в ячейке её видно, но id она не заводит — как и «СПТ»."""
    from whensclass.domain.teachers import not_a_person

    lesson = parse_lesson(
        4, "Кураторский час", "454", "Щетинкин Артем Сергеевич Анастасия Дмитриевна"
    )
    assert lesson.teachers == ("Щетинкин Артем Сергеевич", "Анастасия Дмитриевна")
    assert not_a_person("Анастасия Дмитриевна") and not_a_person("СПТ")
    assert not not_a_person("Щетинкин Артем Сергеевич") and not not_a_person("Ли")


@pytest.mark.parametrize(
    "subject, room", [("—", ""), ("-", ""), ("нет", ""), ("", "-"), ("", "нет")]
)
def test_dash_or_no_is_nothing(subject, room):
    """Прочерк или «нет» — пусто, а не пара «—» в 9:00 и не «каб. -»."""
    assert parse_lesson(1, subject, room, "") is None
    lesson = parse_lesson(1, "Физика", room or "-", "")
    assert lesson.room is None


@pytest.mark.parametrize("subject, teacher, why", [
    ("Иностранный язык, Английский (Пр) Отмена Преподаватель заболел. Куратрский час",
     "Иванова И. И.", "Преподаватель заболел"),
    ("Иностранный язык (Пр) Отмена преподаватель заболел Кураторский час",
     "Иванова И. И.", "преподаватель заболел"),
    ("История экскурсионной деятельности в России (Лек)",
     "замена, кураторский часМатвеев Александр Игоревич", None),
])
def test_curator_hour_written_off_template_is_a_replacement(subject, teacher, why):
    """Кураторский час вместо пары, записанный не по шаблону, уходил отменой
    с часом в причине или показывался прежней лекцией (М29 прогона 1 аудита 4)."""
    lesson = parse_lesson(4, subject, "", teacher)
    assert lesson.subject == "Кураторский час" and not lesson.cancelled
    assert lesson.note.endswith("вместо: " + subject.split(" (")[0].split(" Отмена")[0])
    assert (why is None) or lesson.note.startswith(why)
    assert all("час" not in t for t in lesson.teachers)


def test_first_letter_is_capital_for_every_lesson():
    """Заглавную делали только у замен, и «кураторский час» с «Кураторский
    час» были разными предметами (М30). Адрес вместо названия не трогается."""
    assert parse_lesson(4, "кураторский час", "", "").subject == "Кураторский час"
    assert parse_lesson(4, "ОБЖ (Лек)", "", "").subject == "ОБЖ"


def test_curator_placeholder_is_not_a_teacher():
    """«Куратор» — должность, а не человек: в /v1/teachers ему не место (М31)."""
    assert parse_lesson(4, "Кураторский час (Пр)", "", "Куратор").teachers == ()
    assert parse_lesson(4, "Физика", "", "Кураторова А. А.").teachers == ("Кураторова А. А.",)


@pytest.mark.parametrize("room, note", [
    ("Ссылка: https://my.mts-link.ru/j/1", "Ссылка:"),
    ("https://my.mts-link.ru/j/1 (пароль 1234)", "(пароль 1234)"),
    ("https://a.ru/1\nhttps://b.ru/2", None),
])
def test_text_next_to_a_link_is_not_a_room(room, note):
    """Рядом со ссылкой место — только кабинет или «онлайн N»: «Ссылка:» и
    вторая ссылка делали онлайн-пару очной с кабинетом-словом (прогон 2 аудита 4)."""
    lesson = parse_lesson(number=1, subject_raw="Физика (Лек)", room_raw=room, teacher_raw="")
    assert (lesson.room, lesson.online, lesson.note) == (None, True, note)


def test_role_before_a_name_is_dropped_not_the_name():
    """«Куратор Иванова Анна Петровна» — человек, а «Куратор» целиком —
    заглушка (М31; прогон 2 аудита 4)."""
    from whensclass.parser.cells import split_teachers

    assert split_teachers("Куратор") == ()
    assert split_teachers("Вакансия (ждём)") == ()
    assert split_teachers("Куратор Иванова Анна Петровна") == ("Иванова Анна Петровна",)
    assert split_teachers("куратор: Иванова А. П.") == ("Иванова А. П.",)
    assert split_teachers("Кураторова Анна Петровна") == ("Кураторова Анна Петровна",)

