package ru.whensclass.data

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
fun weekStart(today: java.time.LocalDate = ru.whensclass.widget.collegeToday()): java.time.LocalDate =
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

/**
 * Записать ответ, только если он всё ещё про то, о чём спрашивали.
 *
 * Кто выбран сейчас, спрашиваем заново прямо перед записью, и всё, что пишет
 * на телефон, — внутри [write]. Подгруппа входит в сравнение наравне с ролью и
 * группой: запрос, начатый до её смены, приносил склейку с прежней соседкой и
 * записывал её поверх новой. `null` — ответ чужой, записано ничего не было.
 */
internal suspend fun <T : Any> writeIfStillAsked(
    asked: Subject,
    current: suspend () -> Subject,
    write: suspend () -> T,
): T? = if (current() == asked) write() else null

/** Что случилось при обновлении — приложению есть что показать, виджету нет. */
sealed interface RefreshResult {
    data object Updated : RefreshResult
    data object AlreadyFresh : RefreshResult
    data object NoGroup : RefreshResult
    /** Группы (преподавателя) в таблице больше нет — пора выбрать заново. */
    data object Gone : RefreshResult
    data class Failed(val error: Throwable) : RefreshResult
}

/** Сколько сервер должен пролежать, прежде чем телефон скажет об этом уведомлением. */
const val STALE_NOTIFY_AFTER_MILLIS = 2L * 60 * 60 * 1000

/**
 * Сколько сервер может не отвечать вовсе при живой сети телефона, прежде чем это
 * сбой, а не чих. Раньше «сервер недоступен» (упал процесс, nginx, домен) не
 * давал ни плашки, ни «сбой» на виджетах, ни уведомления: всё держалось на
 * ответе /v1/meta, а ответа-то и нет (второй аудит, В15).
 */
const val UNREACHABLE_BROKEN_AFTER_MILLIS = 30L * 60 * 1000

/** Состояние, которое телефон ставит сам, когда сервер не отвечает. */
const val STATUS_UNREACHABLE = "unreachable"


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
        val today = ru.whensclass.widget.collegeToday().toString()
        return saved.days.any { it.date == today }
    }

    /**
     * Рассказать об изменениях, если человек этого хотел.
     *
     * Молчим, когда меняется что-то далёкое: про послезавтрашнюю замену
     * сообщать посреди пары — только раздражать.
     */
    private suspend fun announceChanges(old: ScheduleDto?, fresh: ScheduleDto, adopted: Boolean = false) {
        if (!store.notifyChangesEnabled()) return
        val today = ru.whensclass.widget.collegeToday()
        val soon = setOf(today.toString(), today.plusDays(1).toString())
        // Id сменил сервер (переименование), а не человек — это тот же субъект.
        val changes = ScheduleDiff.compare(old, fresh, sameSubject = adopted.takeIf { it })
            .filter { it.day in soon }
        if (changes.isEmpty()) return

        // День — словами и датой, а не «Завтра»: уведомление висит в шторке до
        // утра, и наутро «Завтра: добавилась пара» читалось как новость о
        // послезавтра. Снимается само к концу последнего дня, о котором
        // говорит (третий аудит, М71 прогона 2).
        val text = changes.joinToString("\n") { change ->
            val date = java.time.LocalDate.parse(change.day)
            "${ru.whensclass.widget.formatDayTitleShort(date)}: ${change.text}"
        }
        val lastDay = changes.maxOf { java.time.LocalDate.parse(it.day) }
        val until = lastDay.plusDays(1).atStartOfDay(ru.whensclass.widget.COLLEGE_ZONE)
            .toInstant().toEpochMilli()
        Notifications.changes(context, "Расписание изменилось", text, until)
    }

    /**
     * Сервер лежит дольше двух часов — сказать уведомлением, один раз на сбой.
     *
     * Плашка на экране видна только тому, кто открыл приложение; после
     * раздачи о сбое первым узнает тот, кто пришёл на пару по позапрошлой
     * неделе. Двухчасовая задержка отсекает чихи Google, которые сервер и
     * сам лечит к следующему заходу.
     */
    private suspend fun announceStale(status: String?, sinceIso: String?) {
        if (status == "ok" || sinceIso == null) {
            if (store.staleNotifiedFor() != null) store.setStaleNotifiedFor(null)
            return
        }
        if (!store.notifyChangesEnabled()) return
        if (store.staleNotifiedFor() == sinceIso) return
        val since = runCatching { java.time.Instant.parse(sinceIso) }.getOrNull() ?: return
        if (System.currentTimeMillis() - since.toEpochMilli() < STALE_NOTIFY_AFTER_MILLIS) return
        val silent = status == STATUS_UNREACHABLE
        Notifications.serverDown(
            context,
            if (silent) "Сервер расписания не отвечает" else "Сервер расписания не обновляется",
            "Сбой у нас с ${ru.whensclass.widget.formatSince(sinceIso)}. " +
                "Приложение показывает последнее, что пришло, — пары могли поменяться. " +
                "Таблица колледжа открывается из настроек.",
        )
        store.setStaleNotifiedFor(sinceIso)
    }

    /** Есть ли у телефона проверенный выход в интернет — тогда молчание сервера наше. */
    private fun networkUp(): Boolean = runCatching {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    /** Сведения для отчёта об ошибке — см. [collectDiagnostics]. */
    suspend fun diagnostics(): String =
        collectDiagnostics(context, store, schedule.first())

    /** Перерисовать виджеты по тому, что уже лежит на телефоне. */
    suspend fun redrawWidgets() = updateWidgets()

    private suspend fun updateWidgets() {
        ru.whensclass.widget.redrawWidgets(context)
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
    private suspend fun withSecondGroup(
        mine: ScheduleDto, serverOk: Boolean, from: java.time.LocalDate,
    ): Merged {
        val second = store.currentSecondGroupId() ?: return Merged(mine, whole = true)
        val extra = try {
            api.schedule(second, from = from, days = DAYS)
        } catch (error: HttpFailure) {
            // 404 при здоровом сервере — соседки больше нет в таблице. Раньше
            // это было неотличимо от сети: пары соседки молча пропадали, а
            // обрубок навсегда выключал уведомления (второй аудит, В17).
            if (error.code == 404 && serverOk &&
                store.noteSecondNotFound(System.currentTimeMillis())
            ) {
                return Merged(mine, whole = true, secondGone = store.secondGroupName.first() ?: second)
            }
            return Merged(mine, whole = false)
        } catch (error: Exception) {
            return Merged(mine, whole = false)
        }
        store.clearSecondNotFound()
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
        /** Имя соседней подгруппы, которой больше нет в таблице (подтверждено). */
        val secondGone: String? = null,
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
        afterSelection()
    }

    /** Преподаватель выбрал себя — дальше всё работает как у студента. */
    suspend fun selectSelfAsTeacher(teacher: GroupDto) {
        val unchanged = store.teacherMode() && store.teacherId.first() == teacher.id
        store.setTeacherMode(true)
        store.selectTeacher(teacher.id, teacher.name, unchanged)
        afterSelection()
    }

    /**
     * Соседняя подгруппа выбрана или снята (`group` = null). Свои пары на
     * телефоне остаются — без пар прежней соседки, — и без связи экран не
     * пустеет (третий аудит, М1 прогона 2).
     */
    suspend fun selectSecondGroup(group: GroupDto?) {
        val saved = schedule.first()
        val ownOnly = saved?.takeIf { !store.teacherMode() }?.let { json.encodeToString(it.ownOnly()) }
        store.setSecondGroup(group?.id, group?.name, ownOnly)
        afterSelection()
    }

    /**
     * После смены выбора: перерисовать сразу, переставить напоминания по тому,
     * что теперь лежит на телефоне, и сходить за новым. Раньше будильники
     * переставлялись только после удачного обновления, и без связи ближайшее
     * напоминание приходило о паре прежней группы (третий аудит, М29 прогона
     * 1). Не вышло — повторит фоновая работа, как только будет сеть.
     */
    private suspend fun afterSelection() {
        updateWidgets()
        LessonAlarms.reschedule(context)
        if (refresh(force = true) is RefreshResult.Failed) {
            ru.whensclass.work.SyncWorker.now(context)
        }
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
    /**
     * Обновления — по одному. Параллельные (двойное нажатие ⟳, часовой заход и
     * открытие приложения разом) затирали флаги друг друга, а более старый
     * ответ мог лечь поверх нового с обратным «изменилось» (третий аудит, М15
     * прогона 2).
     */
    private val refreshLock = Mutex()

    /** Идёт ли сейчас обновление. */
    val refreshing: Boolean get() = refreshLock.isLocked

    suspend fun refresh(force: Boolean = false): RefreshResult =
        refreshLock.withLock { refreshOnce(force) }

    private suspend fun refreshOnce(force: Boolean): RefreshResult = withContext(Dispatchers.IO) {
        val asked = subject()
        val teacherMode = asked.teacher
        val subject = asked.id
        val second = asked.second
        if (subject == null) return@withContext RefreshResult.NoGroup
        // Неделя — одна на весь заход: запрос, начатый до полуночи воскресенья
        // и кончившийся после, записывал окно прошлой недели с меткой новой
        // (третий аудит, М18 прогона 1).
        val from = weekStart()
        try {
            // Сохранённые дни начинаются с даты загрузки, поэтому со временем
            // сегодняшнего среди них может не оказаться — и виджет пустеет.
            // В этом случае перезапрашиваем, даже если сервер говорит, что у
            // него ничего не изменилось.
            val outdated = !coversToday(schedule.first()) ||
                // Началась новая неделя: сегодняшний понедельник уже лежал
                // восьмым днём прошлого окна, gen на сервере тот же — и до
                // первой правки таблицы приложение показывало один понедельник
                // (второй аудит, М27).
                store.windowFrom() != from.toString() ||
                // Обрубок без пар соседки держался до следующей правки
                // таблицы: gen тот же — «уже свежее» (третий аудит, В13
                // прогона 1).
                store.schedulePartial()
            // Состояние сервера спрашиваем всегда, даже когда идём за
            // расписанием напрямую. Раньше /v1/meta пропускался ровно в
            // тех случаях, ради которых состояние и нужно: при ручном
            // обновлении и когда сегодняшнего дня в данных нет.
            val meta = runCatching { api.meta() }.getOrNull()
            if (meta != null) {
                store.clearUnreachable()
                store.putServerState(meta.status, meta.sourceUrl, meta.since)
                announceStale(meta.status, meta.since)
            } else if (networkUp()) {
                val now = java.time.Instant.now()
                val since = store.noteUnreachable(now)
                if (now.toEpochMilli() - since.toEpochMilli() >= UNREACHABLE_BROKEN_AFTER_MILLIS) {
                    store.putServerState(STATUS_UNREACHABLE, null, since.toString())
                    announceStale(STATUS_UNREACHABLE, since.toString())
                }
            }
            if (!force && !outdated && meta != null) {
                if (meta.generatedAt == store.generatedAt.first()) {
                    // Данные те же, но проверку показать надо: иначе кажется,
                    // что кнопка обновления не работает.
                    store.touchChecked()
                    updateWidgets()
                    return@withContext RefreshResult.AlreadyFresh
                }
            }
            val fresh = try {
                if (teacherMode) {
                    api.teacher(subject, from = from, days = DAYS)
                } else {
                    api.schedule(subject, from = from, days = DAYS)
                }
            } catch (error: HttpFailure) {
                // 404 при здоровом сервере — группы в таблице больше нет:
                // переименовали, разделили, убрали. Это не сетевой сбой, и
                // молчать прежним расписанием здесь значит врать. Но и не с
                // первого раза: опечатку в заголовке колледж чинит за час.
                if (error.code == 404 && meta?.status == "ok") {
                    val confirmed = store.noteNotFound(System.currentTimeMillis())
                    if (confirmed) {
                        updateWidgets()
                        return@withContext RefreshResult.Gone
                    }
                }
                throw error
            }
            store.clearNotFound()
            // Ответил и сервер, и тот, кому не ответил /v1/meta: отметка «не
            // отвечает» снимается любым удачным ответом (М12 прогона 1).
            store.clearUnreachable()
            val merged = if (teacherMode) {
                Merged(fresh, whole = true)
            } else {
                withSecondGroup(fresh, serverOk = meta?.status == "ok", from = from)
            }
            val full = merged.schedule

            // Пока шёл запрос, человек мог сменить группу, роль или подгруппу.
            // Тогда пришедшее расписание — чужое, и записывать его нельзя: оно
            // молча возвращало на экран прежние пары поверх только что выбранных.
            // Всё, что пишет, — внутри writeIfStillAsked: сверку не забыть и не
            // переставить за запись (третий аудит, В17 прогона 2).
            writeIfStillAsked(asked, ::subject) {
                // Ответ пришёл под другим id: группу или преподавателя переименовали
                // в таблице, и сервер ответил по памяти о старом имени. Переписываем
                // выбор у себя — после сверки, иначе она сочла бы его чужим.
                val adopted = fresh.groupId != subject
                if (adopted) {
                    if (teacherMode) store.adoptTeacher(fresh.groupId, fresh.groupName)
                    else store.adoptGroup(fresh.groupId, fresh.groupName)
                }
                merged.secondRenamed?.let { (id, name) -> store.adoptSecondGroup(id, name) }
                merged.secondGone?.let { name ->
                    // Не стираем выбор, а отмечаем: вернётся соседка — вернутся и
                    // её пары (М41 прогона 1). Сказать — один раз, своим
                    // уведомлением и только тому, кто просил сообщать (М15).
                    if (store.markSecondGone() && store.notifyChangesEnabled()) {
                        Notifications.subgroupGone(
                            context,
                            "Подгруппы $name сейчас нет в таблице",
                            "Её пары не показываются рядом с вашими. Появится снова — " +
                                "вернутся сами; если подгруппу переименовали — выберите её " +
                                "заново в настройках.",
                        )
                    }
                }

                val previous = schedule.first()
                val previousPartial = store.schedulePartial()
                // Окна не было — снимок записан сборкой до 81-й, где обрубок не
                // помечался: сравнивать с ним нельзя, «добавилась пара» было бы
                // ложным (третий аудит, М43 прогона 1).
                val comparable = store.windowFrom() != null
                store.putSchedule(
                    json.encodeToString(full), full.generatedAt,
                    partial = !merged.whole, windowFrom = from.toString(),
                )
                // Записанное — уже на телефоне. Объявить и переставить будильники
                // надо и тогда, когда корутину отменили посреди (ушли из
                // приложения): раньше это глоталось как Failed, изменение не
                // объявлялось никогда, а будильник об отменённой паре срабатывал
                // (третий аудит, М9 прогона 2).
                withContext(NonCancellable) {
                    updateWidgets()
                    // Об изменениях — только по сопоставимому. Обрубок без пар
                    // соседней подгруппы отличается от целого так же, как отмена:
                    // сравнивать целое с обрубком нельзя. Раньше обрубок
                    // записывался, а следующее целое объявляло давние пары соседки
                    // «добавившимися» (второй аудит, В10). Если прежнее — обрубок
                    // или соседка пропала, сравниваем свои пары со своими: так и
                    // при её пропаже говорим об отменах своих (В17; М15 прогона 1).
                    when {
                        !comparable -> Unit
                        previousPartial || merged.secondGone != null ->
                            announceChanges(previous?.ownOnly(), fresh, adopted)
                        merged.whole -> announceChanges(previous, full, adopted)
                    }
                    LessonAlarms.reschedule(context)
                    // И будильник к звонку: он считается по сетке из снимка, а при
                    // первом запуске её ещё нет. Взведённый в WhensClassApp по
                    // пустой сетке, он не ставился вовсе — и подсветка «идёт
                    // сейчас» до следующего запуска процесса сама не появлялась.
                    MidnightUpdater.schedule(context)
                }
                RefreshResult.Updated
            } ?: RefreshResult.AlreadyFresh
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Неудача — тоже повод перерисовать: «сервер не отвечает» иначе
            // доходил до виджетов только со звонком или в 00:01 (третий аудит,
            // В4 прогона 2).
            withContext(NonCancellable) { updateWidgets() }
            RefreshResult.Failed(error)
        }
    }
}
