"""Переименования: когда идентификатор меняется вместе с именем.

Идентификатор группы и преподавателя — это их имя, переписанное латиницей,
и он лежит в настройках на телефонах. А имена в таблице колледж правит:
11 сентября 2026 «01.26.Р.ИИ.ГД.ОФ.9-НСК» стала «01.26.Р.ИИ.ГД.Д.ОФ.9-НСК»,
и старый id перестал существовать. У кого он в настройках — расписания нет,
пока не выберет группу заново, а приложение об этом даже не предупредит.
Для группы из двадцати человек это «все без пар» в одно утро.

Поэтому служба помнит переименования и по старому id отвечает расписанием
нового. Признак переименования — не похожесть имён, а совпадение пар: старый
id исчез, новый появился, и пары нового за общие даты — те же, что были у
старого. У переименованной группы колонка та же, так что совпадение полное;
у нового курса, занявшего колонку, — никакого. Два равных кандидата — не
переименование: не гадаем, по старому id — 404.

Книга лежит в `renames.json` рядом со снимком и переживает перезапуск.
"""

from __future__ import annotations

import collections
import datetime as dt
import json
import logging
import pathlib
import threading

from ..domain.models import Snapshot
from ..domain.teachers import TeacherIndex

log = logging.getLogger(__name__)

# След сущности — пары, по которым её узнают под новым именем. Два ключа:
# устойчивый (день, номер, предмет, преподаватели) — по нему порог, и полный
# (плюс аудитория, ссылка, онлайн, отмена) — по нему лидерство. Порог по
# полному ключу подводил: между снимками 11 и 13 сентября 2026 у 31 группы
# из 184 совпало меньше 4/5 пар только потому, что к парам дописали ссылки,
# а мы в тот же день переделали «онлайн 12». По устойчивому — 181 на 1.0.
# Но один устойчивый ключ стирает разницу между подгруппами (у ПХД-923/1 и /2
# совпадает 0.92 по слабому и 0.46 по полному) — поэтому победитель обязан
# быть лидером и по полному ключу.
Trace = tuple[frozenset, frozenset]

# Меньше трёх общих пар — совпадение случайное. И меньше четырёх пятых
# прежних пар — уже не «та же группа», даже если часть пар сошлась: пока
# группу переименовывали, ей могли переписать и расписание, но не целиком.
MIN_SHARED = 3
MIN_SHARE = 0.8
# Столько обновлений подряд состав id должен простоять, прежде чем книга
# начнёт отвечать по новой записи: телефон, получив новый id, перепишет
# выбор навсегда, а опечатку в заголовке колледж чинит через двадцать минут.
CONFIRMATIONS = 3
# Или столько времени, пока лист не меняется. Неизменившийся лист не
# разбирается, и «три обновления подряд» на тихом листе ждали до двух ночных
# переиндексаций — а приложение через час 404 говорило «группы больше нет»
# (второй аудит, В7). Состав id на неизменном листе не меняется по
# определению, так что время здесь — такое же подтверждение.
CONFIRM_AFTER = dt.timedelta(minutes=40)


def group_traces(snapshot: Snapshot) -> dict[str, Trace]:
    """Следы групп: у переименованной колонки они совпадут один в один."""
    out: dict[str, Trace] = {}
    for gid, by_date in snapshot.schedule.items():
        stable, full = set(), set()
        for day, lessons in by_date.items():
            for x in lessons:
                stable.add((day, x.number, x.subject, x.teachers))
                full.add((day, x.number, x.subject, x.teachers, x.room, x.url, x.online, x.cancelled))
        out[gid] = (frozenset(stable), frozenset(full))
    return out


def weekly_traces(snapshot: Snapshot, ids: set[str]) -> dict[str, Trace]:
    """Следы по дню недели, а не по дате — чтобы сравнить группу со стыка листов.

    Старый и новый лист общих дат не имеют: по датам переименование на стыке
    не узнать никогда (второй аудит, В6). А недельный узор пар у одной и той же
    группы в соседние недели почти тот же.
    """
    out: dict[str, Trace] = {}
    for gid in ids:
        stable, full = set(), set()
        for day, lessons in snapshot.schedule.get(gid, {}).items():
            for x in lessons:
                stable.add((day.weekday(), x.number, x.subject, x.teachers))
                full.add((day.weekday(), x.number, x.subject, x.teachers, x.room, x.online))
        out[gid] = (frozenset(stable), frozenset(full))
    return out


def teacher_traces(index: TeacherIndex) -> dict[str, Trace]:
    """Пары преподавателя без группы: группу могли переименовать в тот же час."""
    out: dict[str, Trace] = {}
    for tid, by_date in index.schedule.items():
        stable = frozenset(
            (day, entry.lesson.number, entry.lesson.subject)
            for day, entries in by_date.items()
            for entry in entries
        )
        out[tid] = (stable, stable)
    return out


def _is_offspring(old_id: str, new_id: str) -> bool:
    """Подгруппа старого имени: хвост «/1» или пометка «(1)» в середине.

    «КВД-926» -> «КВД-926/1» и «КС-926» -> «КС(1)-926» — разделение. А лишняя
    буква («…ГД.Д.ОФ…») или буква в хвосте («ИСП-924/2а») — переименование.
    """
    if new_id.startswith(old_id + "-"):
        return True
    old_tokens = collections.Counter(old_id.split("-"))
    new_tokens = collections.Counter(new_id.split("-"))
    # Считаем с кратностью: у «КС(2)-926/2» две двойки, у «КС-926/2» — одна.
    extra = new_tokens - old_tokens
    missing = old_tokens - new_tokens
    return bool(extra) and not missing and all(t.isdigit() for t in extra)


def _same_surname(old_name: str, new_name: str) -> bool:
    """Переименование преподавателя — опечатка или инициалы; фамилия остаётся.

    Другая фамилия — другой человек, которого вписали в те же ячейки на время
    больничного. Отвечать по старому id его расписанием — чужое при ok.
    """
    def surname(name: str) -> str:
        return (name.split() or [""])[0].casefold().replace("ё", "е")

    return surname(old_name) == surname(new_name)


def detect(old: dict[str, Trace], new: dict[str, Trace]) -> dict[str, str]:
    """Кто в кого переименован: старый id -> новый."""
    vanished = sorted(set(old) - set(new))
    appeared = sorted(set(new) - set(old))
    if not vanished or not appeared:
        return {}

    renames: dict[str, str] = {}
    for old_id in vanished:
        offspring = [new_id for new_id in appeared if _is_offspring(old_id, new_id)]
        if offspring:
            # Имя с хвостом — подгруппа: 13 сентября 2026 КВД-926 стала
            # КВД-926/1 и КВД-926/2. Колонка /1 совпадает с прежней целиком,
            # но половина людей теперь в /2, и ответить им расписанием /1 при
            # «ok» — то самое чужое расписание. Одного хвоста достаточно:
            # второй колледж заведёт завтра. Пусть выберут себя заново.
            log.info("%r исчез, а появились %s — это разделение, не гадаю", old_id, offspring)
            continue
        stable_old, full_old = old[old_id]
        if len(stable_old) < MIN_SHARED:
            continue
        scored: list[tuple[tuple[int, int], str]] = []
        for new_id in appeared:
            stable_new, full_new = new[new_id]
            shared = len(stable_old & stable_new)
            if shared < MIN_SHARED or shared < MIN_SHARE * len(stable_old):
                continue
            scored.append(((shared, len(full_old & full_new)), new_id))
        if not scored:
            continue
        scored.sort(reverse=True)
        (best, winner), rest = scored[0], scored[1:]
        # Похожесть имён здесь не судья: колонка на две группы («ДП-923 и
        # ДП-1124») даёт двух кандидатов с одинаковыми парами, и угадывать,
        # кто из них «прежний», — это раздать чужое расписание (второй
        # аудит, М8: комментарий обещал выбор по имени, код его не делал).
        if rest and rest[0][0] == best:
            # Два кандидата неотличимы и по устойчивому, и по полному ключу.
            log.info(
                "%r исчез, а на его место претендуют %s поровну — не гадаю",
                old_id, [winner, rest[0][1]],
            )
            continue
        by_full = max(scored, key=lambda item: item[0][1])[1]
        if by_full != winner:
            # По предметам ближе один, по аудиториям и ссылкам — другой:
            # так выглядят подгруппы с общими лекциями. Не гадаем.
            log.info("%r исчез: по предметам ближе %r, по полному следу %r — не гадаю",
                     old_id, winner, by_full)
            continue
        renames[old_id] = winner
    return renames


def _across_sheets(previous: Snapshot, current: Snapshot, vanished: set[str]) -> dict[str, str]:
    """Переименование на стыке листов: старый id жил только в уходящем листе.

    Пока оба листа в окне, старый и новый id живут в снимке вместе; когда
    уходит старый лист, старый id исчезает, а новый «не появился» — он уже был
    (второй аудит, В6). Кандидаты — группы, которых не было в листе старого
    id, сравнение — по недельному узору пар.
    """
    if not vanished or len(previous.sheet_columns) < 2:
        return {}
    homes = [set(ids) for ids in previous.sheet_columns.values() if vanished & set(ids)]
    if not homes:
        return {}
    home_ids = set().union(*homes)
    candidates = {g.id for g in current.groups} - home_ids
    if not candidates:
        return {}
    found = detect(weekly_traces(previous, vanished), weekly_traces(current, candidates))
    for old_id, new_id in found.items():
        log.info("на стыке листов %r, похоже, стала %r (по недельному узору пар)", old_id, new_id)
    return found


def _teachers_across_sheets(
    previous: Snapshot,
    previous_teachers: TeacherIndex,
    current_teachers: TeacherIndex,
    vanished: set[str],
) -> dict[str, str]:
    """То же, что _across_sheets, для преподавателей (второй аудит, В6).

    Индекс преподавателей листов не знает, но снимок знает, с какого листа
    какой день (`places`). «Родные» дни исчезнувшего — дни его листа;
    кандидаты — те, у кого в эти дни пар не было: новое написание имени живёт
    только в новом листе. Сравнение — по недельному узору, фамилия — та же
    (проверяется там же, где у обычного переименования).
    """
    if not vanished or len(previous.sheet_columns) < 2 or not previous.places:
        return {}
    sheet_of = {day: place.gid for day, place in previous.places.items()}
    homes = {sheet_of.get(day) for tid in vanished for day in previous_teachers.days(tid)} - {None}
    if not homes:
        return {}
    home_days = {day for day, gid in sheet_of.items() if gid in homes}
    candidates = {
        tid for tid in current_teachers.names
        if not set(previous_teachers.days(tid)) & home_days
    }
    if not candidates:
        return {}

    def weekly(index: TeacherIndex, ids: set[str]) -> dict[str, Trace]:
        out: dict[str, Trace] = {}
        for tid in ids:
            stable = frozenset(
                (day.weekday(), entry.lesson.number, entry.lesson.subject)
                for day, entries in index.days(tid).items()
                for entry in entries
            )
            out[tid] = (stable, stable)
        return out

    found = detect(weekly(previous_teachers, vanished), weekly(current_teachers, candidates))
    for old_id, new_id in found.items():
        log.info("на стыке листов преподаватель %r, похоже, стал %r (по недельному узору)", old_id, new_id)
    return found


class RenameBook:
    """Что во что переименовано — для групп и преподавателей отдельно."""

    def __init__(self, state_dir: pathlib.Path):
        self.path = state_dir / "renames.json"
        self._lock = threading.Lock()
        self.groups: dict[str, str] = {}
        self.teachers: dict[str, str] = {}
        # Кандидаты в книгу: старый id -> {"to": новый, "seen": сколько
        # обновлений подряд состав id это подтверждает}. Отдаются наружу
        # только после CONFIRMATIONS.
        self.pending_groups: dict[str, dict] = {}
        self.pending_teachers: dict[str, dict] = {}
        self._load()

    def _load(self) -> None:
        try:
            data = json.loads(self.path.read_text("utf-8"))
            self.groups = dict(data.get("groups", {}))
            self.teachers = dict(data.get("teachers", {}))
            self.pending_groups = dict(data.get("pending_groups", {}))
            self.pending_teachers = dict(data.get("pending_teachers", {}))
        except (OSError, ValueError, AttributeError):
            self.groups, self.teachers = {}, {}
            self.pending_groups, self.pending_teachers = {}, {}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(
            json.dumps(
                {
                    "groups": self.groups,
                    "teachers": self.teachers,
                    "pending_groups": self.pending_groups,
                    "pending_teachers": self.pending_teachers,
                    "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                },
                ensure_ascii=False,
                indent=1,
            ),
            "utf-8",
        )
        tmp.replace(self.path)

    def tick(self) -> None:
        """Лист не менялся — время идёт: подтвердить то, что ждёт дольше CONFIRM_AFTER."""
        with self._lock:
            changed = False
            for book, pending, what in (
                (self.groups, self.pending_groups, "группа"),
                (self.teachers, self.pending_teachers, "преподаватель"),
            ):
                for old_id, entry in list(pending.items()):
                    if _confirmed(entry):
                        _promote(book, pending, old_id, what)
                        changed = True
            if changed:
                self._save()

    def group(self, gid: str) -> str | None:
        """Новый id группы, если старый переименован."""
        return self.groups.get(gid)

    def teacher(self, tid: str) -> str | None:
        return self.teachers.get(tid)

    def record(
        self,
        previous: Snapshot,
        current: Snapshot,
        previous_teachers: TeacherIndex,
        current_teachers: TeacherIndex,
    ) -> None:
        """Сверяет два снимка подряд и дописывает книгу."""
        old_groups = {g.id: g.name for g in previous.groups}
        new_groups = {g.id: g.name for g in current.groups}
        # Следы строятся по всем парам снимка — незачем, пока состав id тот же,
        # а это почти каждое обновление.
        found_groups = (
            detect(group_traces(previous), group_traces(current))
            if old_groups.keys() != new_groups.keys()
            else {}
        )
        leftover = set(old_groups) - set(new_groups) - set(found_groups)
        found_groups.update(_across_sheets(previous, current, leftover))
        found_teachers = (
            detect(teacher_traces(previous_teachers), teacher_traces(current_teachers))
            if previous_teachers.names.keys() != current_teachers.names.keys()
            else {}
        )
        leftover_teachers = (
            set(previous_teachers.names) - set(current_teachers.names) - set(found_teachers)
        )
        found_teachers.update(
            _teachers_across_sheets(previous, previous_teachers, current_teachers, leftover_teachers)
        )
        for old_id, new_id in list(found_teachers.items()):
            old_name = previous_teachers.names.get(old_id, old_id)
            new_name = current_teachers.names.get(new_id, new_id)
            if not _same_surname(old_name, new_name):
                log.info(
                    "преподаватель %r исчез, его пары у %r — фамилия другая, это замена",
                    old_name, new_name,
                )
                del found_teachers[old_id]
        with self._lock:
            changed = self._update(
                self.groups, self.pending_groups, found_groups, alive=set(new_groups),
                names=(old_groups, new_groups), what="группа",
            )
            changed |= self._update(
                self.teachers, self.pending_teachers, found_teachers,
                alive=set(current_teachers.names),
                names=(previous_teachers.names, current_teachers.names), what="преподаватель",
            )
            if changed:
                self._save()

    @staticmethod
    def _update(
        book: dict[str, str],
        pending: dict[str, dict],
        found: dict[str, str],
        alive: set[str],
        names: tuple[dict[str, str], dict[str, str]],
        what: str,
    ) -> bool:
        changed = False
        for old_id, new_id in found.items():
            log.warning(
                "%s %r (%s), похоже, теперь %r (%s): подожду %d обновлений или %d минут, "
                "потом по старому id буду отвечать расписанием нового",
                what, names[0].get(old_id, old_id), old_id,
                names[1].get(new_id, new_id), new_id, CONFIRMATIONS,
                CONFIRM_AFTER.total_seconds() // 60,
            )
            pending[old_id] = {"to": new_id, "seen": 1, "since": _now().isoformat()}
            changed = True

        # Кандидат держится, пока старого id нет, а новый есть. Вернулся
        # старый или пропал новый — не переименование, забываем.
        for old_id, entry in list(pending.items()):
            if old_id in found:
                continue
            if old_id in alive or entry["to"] not in alive:
                del pending[old_id]
                changed = True
                continue
            entry["seen"] += 1
            changed = True
            if _confirmed(entry):
                _promote(book, pending, old_id, what)

        for old_id, target in list(book.items()):
            # Старое имя вернулось в таблицу — значит, это снова настоящая
            # сущность, и подменять её нельзя.
            if old_id in alive:
                del book[old_id]
                changed = True
                continue
            # Появилась подгруппа старого имени (кроме той, на которую
            # отвечаем) — разделение прошло в два захода, и запись стала
            # чужим расписанием для половины людей. Убираем.
            if any(_is_offspring(old_id, a) and a != target for a in alive):
                log.warning("%s %s -> %s: появились подгруппы, запись снята", what, old_id, target)
                del book[old_id]
                changed = True
        return changed


def _now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def _confirmed(entry: dict) -> bool:
    if entry.get("seen", 0) >= CONFIRMATIONS:
        return True
    try:
        since = dt.datetime.fromisoformat(entry["since"])
    except (KeyError, TypeError, ValueError):
        # Записи до 23 сентября 2026 без времени — только по счёту.
        return False
    return _now() - since >= CONFIRM_AFTER


def _promote(book: dict[str, str], pending: dict[str, dict], old_id: str, what: str) -> None:
    entry = pending.pop(old_id)
    log.warning("%s %s -> %s: подтверждено, отвечаю", what, old_id, entry["to"])
    book[old_id] = entry["to"]
    # Цепочку не храним: A -> B -> C сворачивается в A -> C, иначе ответ по A
    # придётся искать в два шага, а по три — никогда.
    for key, value in list(book.items()):
        if value == old_id:
            book[key] = entry["to"]
