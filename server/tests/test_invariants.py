"""Построчные инварианты: что колледж может набрать иначе, а мы примем молча.

Каждая мутация здесь до 14 сентября 2026 разбиралась без единой жалобы — и
давала день под чужой датой, пару у всех групп не на месте или «отмену»
в названии аудитории. Теперь — отказ с точной ячейкой в тексте, а канал
тревоги (service/alerts.py) доносит его до человека.
"""

import datetime as dt

import pytest

from whensclass.domain.models import SourceFormatChanged
from whensclass.parser.cells import parse_lesson
from whensclass.parser.csv_schedule import FIXTURE, collapse_export, parse_sheet, read_csv


def rows_of(fixture_csv):
    return collapse_export(read_csv(fixture_csv), FIXTURE.min_groups)


def date_rows(rows):
    return [i for i, r in enumerate(rows) if r and r[0].strip()[:2].isdigit()]


def test_fixture_itself_passes(fixture_csv):
    parse_sheet(rows_of(fixture_csv), "фикстура", FIXTURE, around=dt.date(2026, 9, 8))


def test_duplicated_date_block_is_rejected(fixture_csv):
    """Скопировали блок дня: та же дата второй раз — номера начинаются заново."""
    rows = rows_of(fixture_csv)
    first, second = date_rows(rows)[:2]
    rows[second][0] = rows[first][0]
    with pytest.raises(SourceFormatChanged, match="повторена: этот день уже начат в строке"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_date_copied_further_down_names_both_rows(fixture_csv):
    """Блок скопирован ниже вместе с датой и
    номерами — отказ называл номера пар настоящего дня и без строки."""
    rows = rows_of(fixture_csv)
    starts = date_rows(rows)
    rows[starts[3]][0] = rows[starts[1]][0]
    with pytest.raises(SourceFormatChanged, match=r"уже была в строке \d+"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_date_in_another_format_is_still_a_date(fixture_csv):
    """«07/09/2026» и «понедельник 07.09.2026» — даты, а не текст рядом с датой."""
    rows = rows_of(fixture_csv)
    first, second = date_rows(rows)[:2]
    rows[first][0] = "среда 02.09.2026"
    rows[second][0] = "03/09/2026"
    snapshot = parse_sheet(rows, "фикстура", FIXTURE)
    assert dt.date(2026, 9, 3) in snapshot.dates


def test_date_that_cannot_be_read_stops_the_parse(fixture_csv):
    rows = rows_of(fixture_csv)
    rows[date_rows(rows)[1]][0] = "03.13.2026 четверг"
    with pytest.raises(SourceFormatChanged, match="не разобралась"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_word_without_digits_in_date_column_is_ignored(fixture_csv):
    rows = rows_of(fixture_csv)
    rows[date_rows(rows)[1] - 1][0] = "четверг"
    parse_sheet(rows, "фикстура", FIXTURE)


def test_lesson_number_written_differently_is_rejected(fixture_csv):
    """«3 пара», «1-2», «кл. час» в колонке номеров — решает человек, не пропуск."""
    rows = rows_of(fixture_csv)
    row = next(i for i, r in enumerate(rows) if len(r) > 1 and r[1].strip() == "3")
    rows[row][1] = "3 пара"
    with pytest.raises(SourceFormatChanged, match="колонке номеров"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_missing_lesson_number_is_rejected(fixture_csv):
    """Пропущенная строка номера — пара исчезала у всех групп без жалоб."""
    rows = rows_of(fixture_csv)
    row = next(i for i, r in enumerate(rows) if len(r) > 1 and r[1].strip() == "3")
    rows[row][1] = ""
    with pytest.raises(SourceFormatChanged, match="ждал 3"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_bell_times_in_number_column_are_fine(fixture_csv):
    """Под парой в колонке номера стоит «9-00-10.30» — это не номер и не беда."""
    rows = rows_of(fixture_csv)
    assert any(len(r) > 1 and r[1].strip() == "9-00-10.30" for r in rows)
    parse_sheet(rows, "фикстура", FIXTURE)


def test_date_far_ahead_is_rejected_only_when_today_is_known(fixture_csv):
    """Опечатка «2027» растягивала бы лист на год; без «сегодня» проверки нет."""
    rows = rows_of(fixture_csv)
    last = date_rows(rows)[-1]
    rows[last][0] = "11.09.2027 суббота"
    with pytest.raises(SourceFormatChanged, match="дальше 120 дней .* а в ней уже пары"):
        parse_sheet(rows, "фикстура", FIXTURE, around=dt.date(2026, 9, 8))
    with pytest.raises(SourceFormatChanged, match="больше 25 дней"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_gap_of_a_month_between_days_is_rejected(fixture_csv):
    rows = rows_of(fixture_csv)
    last = date_rows(rows)[-1]
    rows[last][0] = "10.10.2026 суббота"
    with pytest.raises(SourceFormatChanged, match="больше 25 дней"):
        parse_sheet(rows, "фикстура", FIXTURE)


@pytest.mark.parametrize("cell", ["Иностранный язык (Пр) Отменена", "Отменён Иностранный язык",
                                  "Иностранный язык отм.", "Иностранный язык ОТМ"])
def test_cancellation_spelled_differently(cell):
    assert parse_lesson(1, cell, "272", "").cancelled is True


def test_otmetka_is_not_a_cancellation():
    lesson = parse_lesson(1, "Отметка о практике", "272", "")
    assert lesson.cancelled is False and lesson.subject == "Отметка о практике"


def test_losing_a_third_of_groups_at_once_is_rejected(fixture_csv):
    """Заголовок сломан наполовину: сто групп проходят порог, восемьдесят — в 404."""
    from whensclass.domain.models import Snapshot
    from whensclass.service.refresher import _check_group_drop

    before = parse_sheet(rows_of(fixture_csv), "фикстура", FIXTURE)
    after = Snapshot(sheet_title=before.sheet_title, groups=before.groups[:2],
                     dates=list(before.dates), schedule=dict(before.schedule))
    with pytest.raises(SourceFormatChanged, match="пропало"):
        _check_group_drop(before, after)
    # Новый лист с другими датами — новый состав, сравнивать не с чем.
    after.dates = [d + dt.timedelta(days=30) for d in before.dates]
    _check_group_drop(before, after)
    # И одна пропавшая группа — не толпа.
    one_less = Snapshot(sheet_title=before.sheet_title, groups=before.groups[1:],
                        dates=list(before.dates), schedule=dict(before.schedule))
    _check_group_drop(before, one_less)


def synthetic_sheet(groups=25, days=10, per_day=4):
    """Лист из воздуха: у каждой группы свой набор предметов, повторяющийся по дням.

    Фикстура для этого мала: на шести группах и двух неделях честная суббота
    выглядит как сдвиг. Порог считался по живому листу в 189 групп.
    """
    from whensclass.domain.models import GroupRef, Lesson, Snapshot

    snapshot = Snapshot(sheet_title="синтетика")
    snapshot.dates = [dt.date(2026, 9, 1) + dt.timedelta(days=i) for i in range(days)]
    for g in range(groups):
        ref = GroupRef(name=f"Г-{g}", id=f"g-{g}", column=2 + 4 * g)
        snapshot.groups.append(ref)
        snapshot.schedule[ref.id] = {
            day: [
                Lesson(number=n + 1, subject=f"Предмет {g}-{(4 * i + n) % 12}",
                       teachers=(f"Преподаватель {g}",))
                for n in range(per_day)
            ]
            for i, day in enumerate(snapshot.dates)
        }
    return snapshot


def test_shift_below_last_repeated_header_is_caught_by_content():
    """Блок строк без повторного заголовка сдвинут на группу — по содержимому видно."""
    import copy

    from whensclass.parser.csv_schedule import check_shift

    honest = synthetic_sheet()
    check_shift(honest)

    shifted = copy.deepcopy(honest)
    ids = [g.id for g in honest.groups]
    cutoff = honest.dates[7]
    for i, gid in enumerate(ids):
        neighbour = ids[(i + 1) % len(ids)]
        for day in shifted.schedule[gid]:
            if day >= cutoff:
                shifted.schedule[gid][day] = list(honest.schedule[neighbour][day])
    with pytest.raises(SourceFormatChanged, match="сдвиг колонок"):
        check_shift(shifted)

    # Один курс ушёл на практику — не сдвиг: чужих меньше четверти.
    practice = copy.deepcopy(honest)
    for gid in ids[:5]:
        for day in practice.schedule[gid]:
            if day >= cutoff:
                practice.schedule[gid][day] = [
                    dataclasses_replace(x, subject="Практика", teachers=())
                    for x in practice.schedule[gid][day]
                ]
    check_shift(practice)


def dataclasses_replace(lesson, **changes):
    import dataclasses

    return dataclasses.replace(lesson, **changes)


def test_history_keeps_every_version_once_and_marks_rejected(tmp_path):
    from whensclass.storage.history import archive

    now = dt.datetime(2026, 9, 14, 1, 30)
    first = archive(tmp_path, "656498718", "a,b\n", "abcdef0123", now=now)
    assert first is not None and first.name == "2026-09-14-0130-abcdef01.csv.gz"
    assert archive(tmp_path, "656498718", "a,b\n", "abcdef0123", now=now) is None, "та же версия"
    bad = archive(tmp_path, "656498718", "a,b\n", "abcdef0123", rejected="сдвиг", now=now)
    assert bad is not None and bad.name.endswith("-rejected.csv.gz")
    assert (tmp_path / "history" / "656498718" / "2026-09-14-0130-abcdef01-rejected.txt").read_text("utf-8") == "сдвиг\n"


def test_skipped_block_is_a_gap_not_a_shift(fixture_csv):
    """Блок без имени группы — шаг 8. Пропуск, не сдвиг: соседи на месте."""
    from whensclass.parser.groups import build_column_map

    rows = rows_of(fixture_csv)
    header = rows[0]
    second = [c for c, v in enumerate(header) if v.startswith("Дисциплина Преподаватель")][1]
    header[second] = ""
    groups = build_column_map(rows, FIXTURE.min_groups)
    assert second not in {g.column for g in groups}
    assert len(groups) >= FIXTURE.min_groups


def test_inserted_column_is_still_a_shift(fixture_csv):
    """А шаг 5 — вставленная колонка: карта поехала, отказ."""
    from whensclass.parser.groups import build_column_map

    rows = rows_of(fixture_csv)
    for r in rows:
        if len(r) > 6:
            r.insert(6, "")
    with pytest.raises(SourceFormatChanged, match="шагом"):
        build_column_map(rows, FIXTURE.min_groups)


def test_room_mark_without_dot_is_tolerated(fixture_csv):
    from whensclass.parser.groups import build_column_map

    rows = rows_of(fixture_csv)
    rows[0][5] = "ауд"
    build_column_map(rows, FIXTURE.min_groups)
    rows[0][5] = "Каб."
    with pytest.raises(SourceFormatChanged, match="ожидалась"):
        build_column_map(rows, FIXTURE.min_groups)


def test_shift_from_the_second_lesson_is_caught():
    """Выделили диапазон не с начала дня: первая пара своя, остальные — соседа.
    Голос у группы за день: чужих три, своя одна — за сдвиг."""
    import copy

    from whensclass.parser.csv_schedule import check_shift

    honest = synthetic_sheet()
    ids = [g.id for g in honest.groups]
    shifted = copy.deepcopy(honest)
    day = honest.dates[8]
    for i, gid in enumerate(ids):
        neighbour = ids[(i + 1) % len(ids)]
        mine, theirs = honest.schedule[gid][day], honest.schedule[neighbour][day]
        shifted.schedule[gid][day] = mine[:1] + theirs[1:]
    with pytest.raises(SourceFormatChanged, match="сдвиг колонок"):
        check_shift(shifted)


def test_first_week_of_a_sheet_is_checked_against_the_previous_snapshot():
    """У нового листа своей истории нет — берём прошлый снимок, если это тот же лист."""
    import copy

    from whensclass.domain.models import Snapshot
    from whensclass.parser.csv_schedule import check_shift, shift_seed

    previous = synthetic_sheet(days=10)
    ids = [g.id for g in previous.groups]
    # Тот же лист, перечитанный: первые три дня, сдвинутые на группу.
    fresh = Snapshot(sheet_title=previous.sheet_title, groups=list(previous.groups),
                     dates=previous.dates[:3])
    for i, gid in enumerate(ids):
        neighbour = ids[(i + 1) % len(ids)]
        fresh.schedule[gid] = {d: list(previous.schedule[neighbour][d]) for d in fresh.dates}
    check_shift(fresh), "без истории сдвиг невидим — это и есть дыра"
    with pytest.raises(SourceFormatChanged, match="сдвиг"):
        check_shift(fresh, seed=shift_seed(previous, fresh))

    # Следующий лист через выходные — тоже история; после каникул — нет.
    after_weekend = copy.deepcopy(fresh)
    after_weekend.dates = [previous.dates[-1] + dt.timedelta(days=2 + k) for k in range(3)]
    after_weekend.schedule = {gid: dict(zip(after_weekend.dates, bd.values()))
                              for gid, bd in fresh.schedule.items()}
    assert shift_seed(previous, after_weekend)
    after_holidays = copy.deepcopy(after_weekend)
    after_holidays.dates = [d + dt.timedelta(days=20) for d in after_weekend.dates]
    after_holidays.schedule = {gid: dict(zip(after_holidays.dates, bd.values()))
                               for gid, bd in after_weekend.schedule.items()}
    assert shift_seed(previous, after_holidays) == {}


def test_today_dropping_out_of_the_snapshot_is_not_an_update():
    """gid умер, поиск взял соседний лист: сегодня в нём нет — это подмена, не обновление."""
    from whensclass.domain.models import Snapshot
    from whensclass.service.refresher import _check_today_kept

    previous = synthetic_sheet(days=5)
    today = previous.dates[2]
    other = Snapshot(sheet_title="соседний", groups=list(previous.groups),
                     dates=[d + dt.timedelta(days=30) for d in previous.dates])
    with pytest.raises(LookupError, match="не покрывает"):
        _check_today_kept(previous, other, today)
    _check_today_kept(previous, previous, today)
    # Воскресенья нет ни там, ни там — не повод.
    _check_today_kept(previous, other, previous.dates[-1] + dt.timedelta(days=1))



def test_shift_of_a_window_of_groups_is_caught_by_neighbours():
    """Сдвиг хвоста листа на часть групп — не на всю ширину.

    Прежняя доля «незнакомых» его не видела: соседи — часто подгруппы с общими
    лекциями, и 40–65 сдвинутых групп из 189 проходили.
    """
    import copy

    from whensclass.parser.csv_schedule import SHIFT_RUN_REJECT, check_shift

    honest = synthetic_sheet()
    ids = [g.id for g in honest.groups]
    window = ids[8:8 + SHIFT_RUN_REJECT + 2]
    shifted = copy.deepcopy(honest)
    for day in honest.dates[7:]:
        for gid in window:
            neighbour = ids[ids.index(gid) + 1]
            shifted.schedule[gid][day] = list(honest.schedule[neighbour][day])
    with pytest.raises(SourceFormatChanged, match="групп подряд"):
        check_shift(shifted)


def test_honest_edits_are_not_a_shift():
    """Законные правки колледжа — не сдвиг: прежний детектор
    отвергал их как «сдвиг колонок», и весь лист уходил в stale."""
    import copy

    from whensclass.parser.csv_schedule import check_shift

    honest = synthetic_sheet()
    ids = [g.id for g in honest.groups]
    day = honest.dates[8]

    no_teachers = copy.deepcopy(honest)
    for gid in ids:
        no_teachers.schedule[gid][day] = [
            dataclasses_replace(x, teachers=()) for x in no_teachers.schedule[gid][day]
        ]
    check_shift(no_teachers)

    health_day = copy.deepcopy(honest)
    for gid in ids:
        health_day.schedule[gid][day] = [
            dataclasses_replace(x, subject="День здоровья", teachers=())
            for x in health_day.schedule[gid][day]
        ]
    check_shift(health_day)

    class_hour = copy.deepcopy(honest)
    for gid in ids[:18]:
        first, *rest = class_hour.schedule[gid][day]
        class_hour.schedule[gid][day] = [
            dataclasses_replace(first, subject="Кураторский час", teachers=("Куратор",)), *rest
        ]
    check_shift(class_hour)


def test_subgroups_sharing_lessons_are_not_a_shift():
    """Подгруппы с общими парами: пара соседа, которую знает и своя история, —
    не голос за сдвиг. На живом листе это давало честные серии до 4."""
    from whensclass.parser.csv_schedule import check_shift

    honest = synthetic_sheet()
    for i in range(0, len(honest.groups) - 1, 2):
        a, b = honest.groups[i].id, honest.groups[i + 1].id
        for day in honest.dates:
            honest.schedule[b][day] = list(honest.schedule[a][day])
    check_shift(honest)


def test_new_day_without_a_date_is_named_as_such(fixture_csv):
    """Запятая вместо даты: день молча прилипал к предыдущему, а отказ говорил
    про номера пар соседнего дня (20 сентября 2026)."""
    rows = rows_of(fixture_csv)
    rows[date_rows(rows)[1]][0] = ","
    with pytest.raises(SourceFormatChanged, match="начинается новый день"):
        parse_sheet(rows, "фикстура", FIXTURE)


def test_messages_point_to_the_sheet_row(fixture_csv):
    """Номер строки в тексте отказа — как в Sheets, а не после схлопывания шапки."""
    from whensclass.parser.csv_schedule import collapse_with_rows

    raw = read_csv(fixture_csv)
    rows, numbers = collapse_with_rows(raw, FIXTURE.min_groups)
    target = date_rows(rows)[1]
    assert raw[numbers[target] - 1] is rows[target], "номер ведёт в ту же строку сырого листа"
    rows[target][0] = "03.13.2026 четверг"
    with pytest.raises(SourceFormatChanged, match=f"строке {numbers[target]} листа"):
        parse_sheet(rows, "фикстура", FIXTURE, sheet_rows=numbers)


def test_winter_holidays_inside_a_sheet_are_not_a_typo(fixture_csv):
    """26.12 → 11.01 — шестнадцать дней: прежний порог в две недели отвергал
    такой лист целиком. Месяц — по-прежнему опечатка.
    Здесь — сдвиг на две недели: разрыв 15–16 дней, дни недели те же."""
    rows = rows_of(fixture_csv)
    later = date_rows(rows)[5:]
    for i in later:
        day = _parse_cell_date(rows[i][0])
        rows[i][0] = (day + dt.timedelta(days=14)).strftime("%d.%m.%Y")
    parse_sheet(rows, "фикстура", FIXTURE)


def _parse_cell_date(cell):
    from whensclass.parser.csv_schedule import _parse_date

    return _parse_date(cell)


def test_dates_out_of_order_name_the_row(fixture_csv):
    """Отказ по порядку дат называет дату и строку листа, а не весь столбец
    дат списком datetime.date(…) — так он выглядел 24 сентября 2026."""
    import csv
    import io

    from whensclass.parser.csv_schedule import FIXTURE, parse_export

    rows = list(csv.reader(io.StringIO(fixture_csv)))
    dated = [i for i, r in enumerate(rows) if r and r[0].strip()[:2].isdigit()]
    # Дата раньше предыдущей, но новая: повтор уже встреченной ловит
    # проверка номеров пар, а здесь нужен именно порядок.
    last = dated[-1]
    rows[last][0] = "01.09.2026 вторник"
    out = io.StringIO()
    csv.writer(out).writerows(rows)
    with pytest.raises(SourceFormatChanged) as err:
        parse_export(out.getvalue(), "лист", "1", limits=FIXTURE)
    message = str(err.value)
    assert f"в строке {last + 1} листа" in message
    assert "datetime" not in message



@pytest.mark.parametrize(
    "cell, match",
    [
        ("20.09.2026 понедельник", "воскресенье"),
        ("20.09.2026", "воскресенье"),
        ("22.09.2026 понедельник", "помечена как понедельник, а это вторник"),
    ],
)
def test_date_that_contradicts_its_weekday_is_rejected(fixture_csv, cell, match):
    """Одна цифра в дате — и понедельник уезжал в
    воскресенье («Выходной» у всех), а неделя без пропуска воскресенья
    раздавала пары следующего дня. Раньше — «верю числу» в журнал."""
    rows = rows_of(fixture_csv)
    rows[date_rows(rows)[-1]][0] = cell
    with pytest.raises(SourceFormatChanged, match=match):
        parse_sheet(rows, "фикстура", FIXTURE)



def test_empty_date_skeleton_far_ahead_is_cut_not_rejected(fixture_csv):
    """Колледж вписывает каркас дат на недели
    вперёд. Пустые даты за горизонтом — не опечатка, а будущее: их отрезаем,
    а не роняем лист у всех."""
    import csv
    import dataclasses
    import io

    from whensclass.parser.csv_schedule import parse_csv

    rows = read_csv(fixture_csv)
    width = max(len(r) for r in rows)
    extra = []
    for day in (dt.date(2026, 9, 14), dt.date(2026, 9, 15)):
        for number in range(1, 7):
            first = f"{day:%d.%m.%Y}" if number == 1 else ""
            extra.append([first, str(number)] + [""] * (width - 2))
            extra.append(["", "8.30-10.00"] + [""] * (width - 2))
    buf = io.StringIO()
    csv.writer(buf, lineterminator="\n").writerows(rows + extra)
    near = dataclasses.replace(FIXTURE, max_days_ahead=5)
    snapshot = parse_csv(buf.getvalue(), "фикстура", near, around=dt.date(2026, 9, 8))
    assert snapshot.dates[-1] == dt.date(2026, 9, 12), "14 и 15.09 — за горизонтом и пусты"
