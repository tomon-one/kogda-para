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
        ("Иностранный язык (Пр)", "https://my.mts-link.ru/j/100000001/20000000065", ""),
        dict(url="https://my.mts-link.ru/j/100000001/20000000065", room=None),
    ),
    (
        ("Физкультура (Пр)", "Спортзал Б.Хмельницкого 8 (Б.Хмельницкого 8)", ""),
        # скобка в конце аудитории — это адрес, а не тип занятия
        dict(room="Спортзал Б.Хмельницкого 8 (Б.Хмельницкого 8)", kind="Пр"),
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


def test_room_that_only_starts_with_online_stays_a_room():
    """Сверяем ячейку целиком: «онлайн-центр» был бы зданием, а не вебинаром."""
    lesson = parse_lesson(1, "Информатика", "онлайн-центр", "")
    assert lesson.online is False
    assert lesson.room == "онлайн-центр"


def test_link_means_online_too():
    lesson = parse_lesson(1, "Информатика", "https://my.mts-link.ru/j/5/8", "")
    assert lesson.online is True
    assert lesson.url == "https://my.mts-link.ru/j/5/8"


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


# --- Находки аудита 8 сентября. Каждый случай взят из живой таблицы. --------

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
        ("Иностранный язык", "онлайнhttps://my.mts-link.ru/j/100000001/20000000112", ""),
        dict(url="https://my.mts-link.ru/j/100000001/20000000112", room=None),
    ),
    (
        # Голая ссылка разбиралась и раньше.
        ("Информатика", "https://my.mts-link.ru/j/5/8", ""),
        dict(url="https://my.mts-link.ru/j/5/8", room=None),
    ),
    (
        # Ссылка в колонке предмета: УП-926/1, 07.09, пара 4.
        ("https://my.mts-link.ru/j/5/8", "", "Новиков Вячеслав Сергеевич"),
        dict(url="https://my.mts-link.ru/j/5/8", subject="Занятие онлайн"),
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
        # заменённой, у новой он не написан (ГД-1126, 03.09, пара 3; третий
        # аудит, В8 прогона 1).
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
    assert lesson.subject == "крепостного права"
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
    названия (второй аудит, М8)."""
    lesson = parse_lesson(1, "Иностранный язык (Пр)\nОтмена Преподаватель заболел", "", "")
    assert lesson.subject == "Иностранный язык" and lesson.cancelled
    assert lesson.note == "Преподаватель заболел"


@pytest.mark.parametrize("room", ["55/1", "171/3", "Восход 222", "Спортзал 8", "279а"])
def test_room_after_cancellation_stays_a_room(room):
    """«ОТМЕНА 55/1» — отменена пара в 55/1, а не причина «55/1» (второй аудит, М3)."""
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
    """Второй аудит, М4: раньше всё это делало пару очной с аудиторией-словом."""
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
    /v1/teachers, а у настоящих пары пропадали (второй аудит, В8). Пример —
    живой лист 23.09.2026."""
    assert parse_lesson(1, "Информатика", "", cell).teachers == people


def test_same_sheet_trouble_is_logged_once(caplog):
    """Неувязка листа — состояние, а не событие: раньше она писалась в журнал
    на каждом разборе, каждые двадцать минут."""
    with caplog.at_level("WARNING"):
        for _ in range(3):
            parse_lesson(1, "Информатика", "", "замена")
    assert caplog.text.count("нет имени") == 1


# --- Третий аудит, В1 и В8 прогона 1 ---------------------------------------

@pytest.mark.parametrize(
    "subject, room, teachers, expected",
    [
        # МФ-926/8, 25.09, пара 4: тип и преподаватель — новой пары.
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
        ("Русский язык (Пр) преподаватель на онлайн, студенты в кабинете 171/8", "171/8"),
        ("Экономика организации (Лек) студенты в кабинете 351", "351"),
    ],
)
def test_students_in_a_room_is_an_offline_lesson_with_a_link(subject, room):
    """Л-926/3, 23.09, пара 1: студенты в кабинете, преподаватель по ссылке.
    Пара очная: кабинет — место, ссылка остаётся, «онлайн» нет."""
    lesson = parse_lesson(1, subject, "https://my.mts-link.ru/j/5/8", "Московец К. Р.")
    assert lesson.room == room
    assert lesson.url == "https://my.mts-link.ru/j/5/8"
    assert lesson.online is False
    assert "студент" not in lesson.subject and "онлайн" not in lesson.subject
    assert lesson.kind in ("Лек", "Пр")
