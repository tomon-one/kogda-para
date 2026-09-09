"""Разбор книги, выгруженной в xlsx.

Единственный способ увидеть список листов без ключа Sheets API — то есть ровно
тот путь, который включится, когда ключ протухнет или Google начнёт отвечать
отказом. Обычно он не работает вовсе, а значит и не проверяется ничем: до
9 сентября у него не было ни одного теста.

Настоящая книга колледжа весит двадцать мегабайт, поэтому здесь собирается
своя, из одного файла: разбор всё равно смотрит только в `xl/workbook.xml`.
"""

import io
import zipfile

import pytest

from whensclass.sources.gsheets import XLSX_TITLE_LIMIT, parse_workbook


def workbook(*sheets: str) -> bytes:
    """Мини-книга: только тот файл, в который смотрит разбор."""
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        "<sheets>" + "".join(sheets) + "</sheets></workbook>"
    )
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("xl/workbook.xml", xml)
    return buffer.getvalue()


def sheet(name: str, number: int = 1, state: str | None = None) -> str:
    extra = f' state="{state}"' if state else ""
    return f'<sheet name="{name}" sheetId="{number}"{extra} r:id="rId{number}"/>'


def test_visible_and_hidden_are_told_apart():
    """Скрытые листы колледж прячет сам, и кандидатами они быть не должны."""
    book = parse_workbook(
        workbook(
            sheet("расписание групп 01.-05.09", 1),
            sheet("расписание групп 22-27.09", 2, state="hidden"),
            sheet("совсем спрятанный", 3, state="veryHidden"),
        )
    )

    assert [(s.title, s.hidden) for s in book] == [
        ("расписание групп 01.-05.09", False),
        ("расписание групп 22-27.09", True),
        ("совсем спрятанный", True),
    ]


def test_gid_does_not_come_from_the_workbook():
    """sheetId из книги — не gid, и путать их нельзя.

    По sheetId gviz отдаст не тот лист. Именно поэтому без ключа Sheets API
    лист берётся по имени, а обрезанные имена приходится пропускать.
    """
    book = parse_workbook(workbook(sheet("расписание групп 01.-05.09", 7)))

    assert book[0].sheet_id == "7"
    assert book[0].gid is None


def test_escaped_characters_come_back():
    """В именах листов колледжа встречаются кавычки и амперсанды."""
    book = parse_workbook(workbook(sheet("&quot;Атлас&quot; &amp; графика", 1)))

    assert book[0].title == '"Атлас" & графика'


def test_truncated_title_is_recognised():
    """Ровно 31 символ — признак того, что xlsx имя обрезал.

    Отличить обрезанное имя от честного одинаковой длины нельзя, поэтому
    подозрительными считаются все, и по такому имени в gviz мы не ходим.
    """
    long_name = "расписание для 2-4 курса на 1-6"
    assert len(long_name) == XLSX_TITLE_LIMIT

    book = parse_workbook(workbook(sheet(long_name, 1)))

    assert book[0].title_may_be_truncated is True


def test_short_title_is_not_suspicious():
    book = parse_workbook(workbook(sheet("расписание групп 01.-05.09", 1)))

    assert book[0].title_may_be_truncated is False


def test_sheet_without_a_name_is_skipped():
    book = parse_workbook(workbook('<sheet sheetId="1" r:id="rId1"/>', sheet("живой", 2)))

    assert [s.title for s in book] == ["живой"]


def test_book_without_the_expected_file_is_an_error():
    """Не наша книга — лучше упасть, чем отдать пустой список листов.

    Пустой список наверху выглядит как «в книге ничего нет», и поиск ушёл бы
    в отказ молча, вместо того чтобы сказать, что не понял ответ Google.
    """
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("что-то.txt", "не книга")

    with pytest.raises(KeyError):
        parse_workbook(buffer.getvalue())
