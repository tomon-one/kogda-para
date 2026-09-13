package ru.whensclass.data

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.WeekWidget
import ru.whensclass.work.MidnightUpdater

/**
 * Сколько дней держим на телефоне: неделя целиком и следующий понедельник.
 *
 * Восьмой день — не про запас: в воскресенье и в субботу вечером человек
 * смотрит именно на завтрашний понедельник.
 */
const val DAYS = 8

/**
 * С какого дня показывать расписание — с понедельника текущей недели.
 *
 * Прошедшие пары никуда не деваются: иногда нужно вспомнить, что было в начале
 * недели, и в приложении это обещано прямо.
 *
 * Раньше в воскресенье окно сдвигалось на следующий понедельник — и любое
 * обновление в этот день затирало прожитую неделю данными следующей. Понедельник
 * с субботой исчезали и с экрана, и из виджета, хотя приложение обещает
 * обратное. Теперь воскресенье такой же день недели, как остальные, а завтрашний
 * понедельник виден за счёт восьмого дня.
 */
fun weekStart(today: java.time.LocalDate = java.time.LocalDate.now()): java.time.LocalDate =
    today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

/**
 * Кому принадлежит расписание, за которым мы пошли.
 *
 * Сравнивается целиком: сменилось любое из трёх — ответ уже не тот, о котором
 * просили. Отдельным типом, а не тремя переменными, чтобы добавить четвёртую
 * настройку и забыть её в сравнении было негде.
 */
internal data class Subject(
    val teacher: Boolean,
    val id: String?,
    val second: String?,
)

/** Что случилось при обновлении — приложению есть что показать, виджету нет. */
sealed interface RefreshResult {
    data object Updated : RefreshResult
    data object AlreadyFresh : RefreshResult
    data object NoGroup : RefreshResult
    data class Failed(val error: Throwable) : RefreshResult
}

class ScheduleRepository(
    private val context: Context,
    private val api: ScheduleApi,
    private val store: ScheduleStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Разбор уводим с главного потока: collectAsState собирает поток там же,
    // где идёт отрисовка. Заминку на запуске это не убрало — мерили 9 сентября,
    // разница внутри разброса, — но разбирать недельный JSON в потоке отрисовки
    // неправильно и без неё: телефоны бывают медленнее нашего, а лист длиннее.
    val schedule: Flow<ScheduleDto?> = store.scheduleJson
        // Хранилище общее: любая запись в него — счётчик ответов, тема,
        // время проверки — будила этот поток, и недельный JSON разбирался
        // заново, хотя сам не менялся.
        .distinctUntilChanged()
        .map { body ->
            body?.let { runCatching { json.decodeFromString<ScheduleDto>(it) }.getOrNull() }
        }
        .flowOn(Dispatchers.Default)

    val fetchedAt: Flow<Long> = store.fetchedAt
    val groupName: Flow<String?> = store.groupName

    /**
     * Чьё расписание мы сейчас просим: роль, выбранный и соседняя подгруппа.
     *
     * Запрос идёт по сети секунды, и за это время человек успевает сменить
     * группу или роль. Пришедший ответ тогда чужой, и записывать его нельзя —
     * однажды он молча возвращал на экран прежние пары поверх только что
     * выбранных. Поэтому перед записью спрашиваем ещё раз и сверяем целиком.
     */
    private suspend fun subject(): Subject {
        val teacher = store.teacherMode()
        return Subject(
            teacher = teacher,
            id = if (teacher) store.teacherId.first() else store.currentGroupId(),
            second = store.currentSecondGroupId(),
        )
    }

    /** Есть ли сегодняшний день в том, что лежит на телефоне. */
    private fun coversToday(saved: ScheduleDto?): Boolean {
        if (saved == null) return false
        val today = java.time.LocalDate.now().toString()
        return saved.days.any { it.date == today }
    }

    /**
     * Рассказать об изменениях, если человек этого хотел.
     *
     * Молчим, когда меняется что-то далёкое: про послезавтрашнюю замену
     * сообщать посреди пары — только раздражать.
     */
    private suspend fun announceChanges(old: ScheduleDto?, fresh: ScheduleDto) {
        if (!store.notifyChangesEnabled()) return
        val today = java.time.LocalDate.now()
        val soon = setOf(today.toString(), today.plusDays(1).toString())
        val changes = ScheduleDiff.compare(old, fresh).filter { it.day in soon }
        if (changes.isEmpty()) return

        val text = changes.joinToString("\n") { change ->
            val when_ = if (change.day == today.toString()) "Сегодня" else "Завтра"
            "$when_: ${change.text}"
        }
        Notifications.changes(context, "Расписание изменилось", text)
    }

    /** Сведения для отчёта об ошибке — см. [collectDiagnostics]. */
    suspend fun diagnostics(): String =
        collectDiagnostics(context, store, schedule.first())

    /** Перерисовать виджеты по тому, что уже лежит на телефоне. */
    suspend fun redrawWidgets() = updateWidgets()

    private suspend fun updateWidgets() {
        ScheduleWidget().updateAll(context)
        WeekWidget().updateAll(context)
        NextLessonWidget().updateAll(context)
        // Считаем только тогда, когда виджет и правда стоит на экране.
        // Перерисовка пустого места ответом не была: счётчик обещает, что
        // столько раз расписание показали вместо таблицы.
        if (widgetsPlaced()) store.countWidgetDraw()
    }

    private suspend fun widgetsPlaced(): Boolean = runCatching {
        val manager = GlanceAppWidgetManager(context)
        listOf(
            ScheduleWidget::class.java,
            WeekWidget::class.java,
            NextLessonWidget::class.java,
        ).any { manager.getGlanceIds(it).isNotEmpty() }
    }.getOrDefault(false)

    /**
     * Добавить пары соседней подгруппы, если она выбрана.
     *
     * Не достучались до неё — показываем своё расписание как есть: без пары
     * соседей человек всё же обойдётся, а без своих пар — нет.
     */
    private suspend fun withSecondGroup(mine: ScheduleDto): Merged {
        val second = store.currentSecondGroupId() ?: return Merged(mine, whole = true)
        val extra = runCatching {
            api.schedule(second, from = weekStart(), days = DAYS)
        }.getOrNull() ?: return Merged(mine, whole = false)
        // Соседку переименовали: сервер ответил под новым id. Записать его
        // прямо здесь нельзя — сверка «не сменил ли человек выбор, пока шёл
        // запрос» сочла бы это чужим ответом. Отдаём наверх, запишется после.
        val renamed = extra.takeIf { it.groupId != second }?.let { it.groupId to it.groupName }
        return Merged(mergeSecondGroup(mine, extra), whole = true, secondRenamed = renamed)
    }

    /**
     * Расписание и признак того, что склейка удалась целиком.
     *
     * Когда запрос за парами соседней подгруппы не прошёл, мы показываем своё
     * расписание без них — так лучше, чем ничего. Но сравнивать такой обрубок с
     * прежним полным снимком нельзя: разница выглядит как отмена, и человеку
     * уходило уведомление «убрали 3 пару» на пару, которая никуда не делась.
     */
    private data class Merged(
        val schedule: ScheduleDto,
        val whole: Boolean,
        /** Новые id и имя соседней подгруппы, если её переименовали. */
        val secondRenamed: Pair<String, String>? = null,
    )

    suspend fun groups(): List<GroupDto> = withContext(Dispatchers.IO) {
        val cached = store.groupsJson.first()
        val fromNetwork = runCatching { api.groups() }.getOrNull()
        if (fromNetwork != null) {
            store.putGroups(json.encodeToString(fromNetwork))
            return@withContext fromNetwork.groups
        }
        // Список групп открывается и без сети: выбрать группу в метро тоже надо.
        cached?.let { runCatching { json.decodeFromString<GroupsDto>(it).groups }.getOrNull() }
            .orEmpty()
    }

    /**
     * Список преподавателей.
     *
     * Сначала отдаём сохранённый — экран открывается сразу и без сети, а
     * свежий подтягиваем следом. Раньше он ехал по сети при каждом заходе на
     * вкладку, и та заметно подтормаживала.
     */
    suspend fun teachers(): List<GroupDto> = withContext(Dispatchers.IO) {
        val cached = store.teachersJson.first()
            ?.let { runCatching { json.decodeFromString<TeachersDto>(it).teachers }.getOrNull() }
        val fresh = runCatching { api.teachers() }.getOrNull()
        if (fresh != null) {
            store.putTeachers(json.encodeToString(fresh))
            return@withContext fresh.teachers
        }
        cached.orEmpty()
    }

    /** Расписание преподавателя — берём по запросу, на телефоне не храним. */
    suspend fun teacherSchedule(teacherId: String): ScheduleDto? = withContext(Dispatchers.IO) {
        runCatching { api.teacher(teacherId, from = weekStart(), days = DAYS) }.getOrNull()
    }

    /** Студент выбрал группу — заодно это и означает, что он студент. */
    suspend fun selectGroup(group: GroupDto) {
        // Смотрим до смены роли: после неё «та же группа» уже не значит
        // «ничего не изменилось» — у преподавателя лежит чужое расписание.
        val unchanged = !store.teacherMode() && store.currentGroupId() == group.id
        store.setTeacherMode(false)
        store.selectGroup(group.id, group.name, unchanged)
        // Перерисовать сразу, не дожидаясь сети: смена роли стирает расписание,
        // и до конца запроса виджет иначе показывает чужое — то, что осталось от
        // прошлой роли. Если запрос не дойдёт, честнее «ещё не загружено».
        updateWidgets()
        refresh(force = true)
    }

    /** Преподаватель выбрал себя — дальше всё работает как у студента. */
    suspend fun selectSelfAsTeacher(teacher: GroupDto) {
        store.setTeacherMode(true)
        store.selectTeacher(teacher.id, teacher.name)
        updateWidgets()
        refresh(force = true)
    }

    /** Расписание группы — для просмотра чужого, без смены роли. */
    suspend fun groupSchedule(groupId: String): ScheduleDto? = withContext(Dispatchers.IO) {
        runCatching { api.schedule(groupId, from = weekStart(), days = DAYS) }.getOrNull()
    }

    /**
     * Обновляет расписание.
     *
     * Сначала спрашиваем /v1/meta: если сервер не перечитывал таблицу с
     * прошлого раза, качать расписание незачем. Ошибку наружу не выносим —
     * пусть виджет молча показывает прежнее, это лучше, чем сообщение об
     * ошибке вместо пар.
     */
    suspend fun refresh(force: Boolean = false): RefreshResult = withContext(Dispatchers.IO) {
        val asked = subject()
        val teacherMode = asked.teacher
        val subject = asked.id
        val second = asked.second
        if (subject == null) return@withContext RefreshResult.NoGroup
        try {
            // Сохранённые дни начинаются с даты загрузки, поэтому со временем
            // сегодняшнего среди них может не оказаться — и виджет пустеет.
            // В этом случае перезапрашиваем, даже если сервер говорит, что у
            // него ничего не изменилось.
            val outdated = !coversToday(schedule.first())
            // Состояние сервера спрашиваем всегда, даже когда идём за
            // расписанием напрямую. Раньше /v1/meta пропускался ровно в
            // тех случаях, ради которых состояние и нужно: при ручном
            // обновлении и когда сегодняшнего дня в данных нет.
            val meta = runCatching { api.meta() }.getOrNull()
            meta?.let { store.putServerState(it.status, it.sourceUrl) }
            if (!force && !outdated && meta != null) {
                if (meta.generatedAt == store.generatedAt.first()) {
                    // Данные те же, но проверку показать надо: иначе кажется,
                    // что кнопка обновления не работает.
                    store.touchChecked()
                    updateWidgets()
                    return@withContext RefreshResult.AlreadyFresh
                }
            }
            val fresh = if (teacherMode) {
                api.teacher(subject, from = weekStart(), days = DAYS)
            } else {
                api.schedule(subject, from = weekStart(), days = DAYS)
            }
            val merged = if (teacherMode) Merged(fresh, whole = true) else withSecondGroup(fresh)
            val full = merged.schedule

            // Пока шёл запрос, человек мог сменить группу, роль или подгруппу.
            // Тогда пришедшее расписание — чужое, и записывать его нельзя: оно
            // молча возвращало на экран прежние пары поверх только что выбранных.
            // Подгруппа входит в сравнение наравне с ролью и группой: запрос,
            // начатый до её смены, приносил склейку с прежней соседкой и
            // записывал её поверх новой.
            if (subject() != asked) return@withContext RefreshResult.AlreadyFresh

            // Ответ пришёл под другим id: группу или преподавателя переименовали
            // в таблице, и сервер ответил по памяти о старом имени. Переписываем
            // выбор у себя — после сверки, иначе она сочла бы его чужим.
            if (fresh.groupId != subject) {
                if (teacherMode) store.adoptTeacher(fresh.groupId, fresh.groupName)
                else store.adoptGroup(fresh.groupId, fresh.groupName)
            }
            merged.secondRenamed?.let { (id, name) -> store.adoptSecondGroup(id, name) }

            val previous = schedule.first()
            store.putSchedule(json.encodeToString(full), full.generatedAt)
            updateWidgets()
            // Об изменениях говорим только по целому снимку: обрубок без пар
            // соседней подгруппы отличается от прежнего так же, как отмена.
            if (merged.whole) announceChanges(previous, full)
            LessonAlarms.reschedule(context)
            // И будильник к звонку: он считается по сетке из снимка, а при
            // первом запуске её ещё нет. Взведённый в WhensClassApp по пустой
            // сетке, он не ставился вовсе — и подсветка «идёт сейчас» до
            // следующего запуска процесса сама не появлялась.
            MidnightUpdater.schedule(context)
            RefreshResult.Updated
        } catch (error: Exception) {
            RefreshResult.Failed(error)
        }
    }
}
