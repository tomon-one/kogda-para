package ru.whensclass.data

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.WeekWidget
import ru.whensclass.work.MidnightUpdater

/**
 * Сколько дней держим на телефоне: эта неделя и следующая, с понедельника.
 *
 * Было восемь — неделя и следующий понедельник, — и в выходные приложение не
 * показывало следующую неделю, хотя в таблице она уже была (Tomon, 27.09).
 * Сервер отдаёт до 14 дней за запрос.
 */
const val DAYS = 14

/**
 * Отметка окна: понедельник и размер. Размер — затем, чтобы телефон со
 * старым окном перезапросил его сам, а не ждал до новой недели или правки
 * таблицы: сверка идёт по строке, «2026-09-21» ≠ «2026-09-21/14».
 */
internal fun windowMark(from: java.time.LocalDate): String = "$from/$DAYS"

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
    /** Остальные выбранные группы, id по порядку. */
    val extras: List<String> = emptyList(),
)

/**
 * Записать ответ, только если он всё ещё про то, о чём спрашивали.
 *
 * Кто выбран сейчас, спрашиваем заново прямо перед записью, и всё, что пишет
 * на телефон, — внутри [write]. Остальные группы входят в сравнение наравне с
 * ролью и своей: запрос, начатый до их смены, приносил пары прежних и
 * записывал их поверх новых. `null` — ответ чужой, записано ничего не было.
 */
internal suspend fun <T : Any> writeIfStillAsked(
    asked: Subject,
    current: suspend () -> Subject,
    write: suspend () -> T,
): T? = if (current() == asked) write() else null

/**
 * Строки уведомления об изменениях: непрочитанные прежние и новые, без
 * прошедших дней и повторов, не больше [MAX_CHANGE_LINES] последних.
 */
internal fun mergeChanges(
    pending: List<Pair<String, String>>,
    fresh: List<Pair<String, String>>,
    today: java.time.LocalDate,
): List<Pair<String, String>> =
    (pending + fresh).filter { it.first >= today.toString() }.distinct().takeLast(MAX_CHANGE_LINES)

/** Больше строк шторка всё равно не покажет развёрнутой. */
internal const val MAX_CHANGE_LINES = 8

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
 * ответе /v1/meta, а ответа-то и нет.
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

    /** Остальные выбранные группы по порядку. */
    val extraGroups: Flow<List<ExtraGroup>> = store.extraGroups.distinctUntilChanged()

    /** Их расписания, id → снимок. */
    private val extraSchedules: Flow<Map<String, ScheduleDto>> = store.extraSchedulesJson
        .distinctUntilChanged()
        .map { body ->
            body?.let { runCatching { json.decodeFromString<Map<String, ScheduleDto>>(it) }.getOrNull() }
                .orEmpty()
        }
        .flowOn(Dispatchers.Default)

    /**
     * Для экрана: своё расписание вместе с парами остальных выбранных групп
     * ([combineGroups]). Пропавшая из таблицы группа держит своё место в
     * значках, но её прежних пар не показываем: они могли уже поменяться.
     */
    val shownSchedule: Flow<ScheduleDto?> = combine(schedule, extraGroups, extraSchedules) { main, extras, saved ->
        main?.let { combineGroups(it, extras.map { group -> group.name to saved[group.id]?.takeUnless { group.gone } }) }
    }.flowOn(Dispatchers.Default)

    val fetchedAt: Flow<Long> = store.fetchedAt

    /**
     * Чьё расписание мы сейчас просим: роль, выбранный и остальные группы.
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
            extras = store.currentExtraGroups().map { it.id },
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

        // Непрочитанное прежнее не затираем: новое с тем же id заменяло его, и
        // отмена, пришедшая утром, к вечеру пропадала. Прочитанное или смахнутое — начинаем заново.
        val pending = if (Notifications.changesShown(context)) store.pendingChanges() else emptyList()
        val lines = mergeChanges(pending, changes.map { it.day to it.text }, today)
        store.putPendingChanges(lines)

        // День — словами и датой, а не «Завтра»: уведомление висит в шторке до
        // утра, и наутро «Завтра: добавилась пара» читалось как новость о
        // послезавтра. Снимается само к концу последнего дня, о котором
        // говорит.
        val text = lines.joinToString("\n") { (day, change) ->
            "${ru.whensclass.widget.formatDayDate(java.time.LocalDate.parse(day))}: $change"
        }
        val lastDay = lines.maxOf { java.time.LocalDate.parse(it.first) }
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
            if (store.staleNotifiedFor() != null) {
                store.setStaleNotifiedFor(null)
                Notifications.serverBack(context)
            }
            return
        }
        if (!store.notifyServerEnabled()) return
        if (store.staleNotifiedFor() == sinceIso) return
        val since = runCatching { java.time.Instant.parse(sinceIso) }.getOrNull() ?: return
        if (System.currentTimeMillis() - since.toEpochMilli() < STALE_NOTIFY_AFTER_MILLIS) return
        val silent = status == STATUS_UNREACHABLE
        val shown = Notifications.serverDown(
            context,
            if (silent) "Сервер расписания не отвечает" else "Сервер расписания не обновляется",
            "Сбой с ${ru.whensclass.widget.formatSinceMoment(sinceIso)}. " +
                "Приложение и виджеты показывают прежнее, пары могли поменяться. " +
                "Таблицу колледжа можно открыть из приложения.",
        )
        if (shown) store.setStaleNotifiedFor(sinceIso)
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
     * Расписания остальных выбранных групп — разом.
     *
     * Не достучались до какой-то — у неё остаётся прежний снимок: своё
     * расписание от этого не зависит, а экран с прежними чужими парами лучше
     * пустого места.
     */
    private suspend fun fetchExtras(
        extras: List<ExtraGroup>, serverOk: Boolean, from: java.time.LocalDate,
    ): Extras = coroutineScope {
        val answers = extras.map { group ->
            async { group to runCatching { api.schedule(group.id, from = from, days = DAYS) } }
        }.awaitAll()
        val result = Extras()
        for ((group, answer) in answers) {
            val schedule = answer.getOrElse { error ->
                if (error is CancellationException) throw error
                // 404 при здоровом сервере — группы больше нет в таблице. Это не
                // сеть: её пары молча пропадали бы, а причины никто не узнал бы.
                if (error is HttpFailure && error.code == 404 && serverOk &&
                    store.noteExtraNotFound(group.id, System.currentTimeMillis())
                ) {
                    result.gone += group
                }
                continue
            }
            result.found += group.id
            result.schedules[group.id] = schedule
            // Группу переименовали: сервер ответил под новым id. Записать его
            // здесь нельзя — сверка «не сменил ли человек выбор, пока шёл
            // запрос» сочла бы это чужим ответом. Запишется после неё.
            if (schedule.groupId != group.id) result.renamed[group.id] = schedule.groupId to schedule.groupName
        }
        result
    }

    /** Снимка какой-то из остальных групп нет или он не про эту неделю — её пары пора принести. */
    private suspend fun extrasMissing(groups: List<ExtraGroup>): Boolean {
        val asked = groups.filterNot { it.gone }
        if (asked.isEmpty()) return false
        val saved = extraSchedules.first()
        return asked.any { !coversToday(saved[it.id]) }
    }

    /**
     * Записать принесённое остальными группами — внутри сверки выбора.
     * Переименованные — под новым id, пропавшие — отметкой и, если просили,
     * уведомлением, не ответившие — с прежним снимком.
     */
    private suspend fun writeExtras(extras: Extras) {
        extras.renamed.forEach { (old, new) -> store.adoptExtra(old, new.first, new.second) }
        store.clearExtrasNotFound(extras.found.map { extras.renamed[it]?.first ?: it }.toSet())
        extras.gone.forEach { group ->
            // Выбор не стираем, а отмечаем: вернётся группа — вернутся и её
            // пары. Сказать — один раз и только тому, кто просил сообщать.
            if (store.markExtraGone(group.id) && store.notifyGroupsGoneEnabled()) {
                Notifications.subgroupGone(
                    context,
                    "Группы ${group.name} сейчас нет в таблице",
                    "Её пары пока не показываются и вернутся сами, когда она " +
                        "появится. Если её переименовали — выберите заново в настройках.",
                )
            }
        }
        if (extras.schedules.isEmpty()) return
        val keep = store.currentExtraGroups().map { it.id }.toSet()
        val saved = extraSchedules.first().toMutableMap()
        extras.schedules.forEach { (id, schedule) ->
            saved.remove(id)
            saved[extras.renamed[id]?.first ?: id] = schedule
        }
        store.putExtraSchedules(json.encodeToString(saved.filterKeys { it in keep }))
    }

    /** Что принесли остальные группы. */
    private class Extras {
        val schedules = mutableMapOf<String, ScheduleDto>()
        /** Прежний id → новые id и имя. */
        val renamed = mutableMapOf<String, Pair<String, String>>()
        /** Ответили — отметки о пропаже снять. */
        val found = mutableSetOf<String>()
        /** Пропажа подтверждена этим заходом. */
        val gone = mutableListOf<ExtraGroup>()
    }

    /**
     * Списки групп и преподавателей: сохранённый — сразу и без сети, свежий —
     * следом. Раньше сохранённый брался только при ошибке сети, и в плохой
     * сети спиннер крутился до минуты при списке на телефоне, хотя
     * комментарий обещал обратное.
     */
    suspend fun cachedGroups(): List<GroupDto> = withContext(Dispatchers.IO) {
        store.groupsJson.first()
            ?.let { runCatching { json.decodeFromString<GroupsDto>(it).groups }.getOrNull() }
            .orEmpty()
    }

    /** Свежий список групп; null — сервер не ответил. */
    suspend fun freshGroups(): List<GroupDto>? = withContext(Dispatchers.IO) {
        val fresh = runCatching { api.groups() }.getOrNull() ?: return@withContext null
        store.putGroups(json.encodeToString(fresh))
        fresh.groups
    }

    suspend fun cachedTeachers(): List<GroupDto> = withContext(Dispatchers.IO) {
        store.teachersJson.first()
            ?.let { runCatching { json.decodeFromString<TeachersDto>(it).teachers }.getOrNull() }
            .orEmpty()
    }

    /** Свежий список преподавателей; null — сервер не ответил. */
    suspend fun freshTeachers(): List<GroupDto>? = withContext(Dispatchers.IO) {
        val fresh = runCatching { api.teachers() }.getOrNull() ?: return@withContext null
        store.putTeachers(json.encodeToString(fresh))
        fresh.teachers
    }

    /**
     * Закреплённые — за переименованием. Id, которого нет в свежем списке,
     * спрашиваем у сервера: по памяти о старом имени он отвечает под новым
     * id, его и закрепляем. Раньше звёздочка молча пропадала, а старый id
     * оставался в настройках навсегда.
     */
    suspend fun followRenamedPins(groups: List<GroupDto>, teachers: List<GroupDto>) =
        withContext(Dispatchers.IO) {
            val groupIds = groups.map { it.id }.toSet()
            for (old in store.pinnedGroups.first().filter { it !in groupIds }) {
                val now = runCatching { api.schedule(old, days = 1).groupId }.getOrNull() ?: continue
                if (now != old && now in groupIds) store.replacePinnedGroup(old, now)
            }
            val teacherIds = teachers.map { it.id }.toSet()
            for (old in store.pinnedTeachers.first().filter { it !in teacherIds }) {
                val now = runCatching { api.teacher(old, days = 1).groupId }.getOrNull() ?: continue
                if (now != old && now in teacherIds) store.replacePinnedTeacher(old, now)
            }
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
     * Добавить группы к остальным — в конец, без своей и без повторов, пока
     * влезает [MAX_GROUPS]. Их пары придут с обновлением; виджеты и
     * напоминания они не трогают.
     */
    suspend fun addExtraGroups(groups: List<GroupDto>) {
        val current = store.currentExtraGroups()
        val own = store.currentGroupId()
        val added = groups
            .filter { group -> group.id != own && current.none { it.id == group.id } }
            .distinctBy { it.id }
            .map { ExtraGroup(it.id, it.name) }
        if (added.isEmpty()) return
        store.setExtraGroups(current + added)
        if (refresh(force = true) is RefreshResult.Failed) {
            ru.whensclass.work.SyncWorker.now(context)
        }
    }

    /** Убрать группу из остальных — вместе с её парами на экране, сразу и без сети. */
    suspend fun removeExtraGroup(id: String) {
        store.setExtraGroups(store.currentExtraGroups().filter { it.id != id })
    }

    /**
     * После смены выбора: перерисовать сразу, переставить напоминания по тому,
     * что теперь лежит на телефоне, и сходить за новым. Раньше будильники
     * переставлялись только после удачного обновления, и без связи ближайшее
     * напоминание приходило о паре прежней группы. Не вышло — повторит фоновая работа, как только будет сеть.
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
     * ответ мог лечь поверх нового с обратным «изменилось».
     */
    private val refreshLock = Mutex()

    /** Идёт ли сейчас обновление. */
    val refreshing: Boolean get() = refreshLock.isLocked

    suspend fun refresh(force: Boolean = false): RefreshResult =
        refreshLock.withLock { refreshOnce(force) }

    private suspend fun refreshOnce(force: Boolean): RefreshResult = withContext(Dispatchers.IO) {
        // Снимок сборок до 0.1.4 лежит склеенным с парами соседки: сравнить
        // его со своими парами значило бы объявить её пары отменёнными.
        store.migrateGroups()
        val asked = subject()
        val teacherMode = asked.teacher
        val subject = asked.id
        val extraGroups = if (teacherMode) emptyList() else store.currentExtraGroups().filter { it.id in asked.extras }
        if (subject == null) return@withContext RefreshResult.NoGroup
        // Неделя — одна на весь заход: запрос, начатый до полуночи воскресенья
        // и кончившийся после, записывал окно прошлой недели с меткой новой.
        val from = weekStart()
        try {
            // Сохранённые дни начинаются с даты загрузки, поэтому со временем
            // сегодняшнего среди них может не оказаться — и виджет пустеет.
            // В этом случае перезапрашиваем, даже если сервер говорит, что у
            // него ничего не изменилось.
            val outdated = !coversToday(schedule.first()) ||
                // Началась новая неделя: сегодняшний понедельник уже лежал
                // восьмым днём прошлого окна, gen на сервере тот же — и до
                // первой правки таблицы приложение показывало один понедельник.
                store.windowFrom() != windowMark(from) ||
                // Группу только что добавили или её снимок не дошёл: gen тот
                // же — «уже свежее», и её пар не было бы до правки таблицы.
                extrasMissing(extraGroups)
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
                        // Будильники по прежнему снимку снять сразу: иначе
                        // первое напоминание звало на пару пропавшей группы.
                        LessonAlarms.reschedule(context)
                        return@withContext RefreshResult.Gone
                    }
                }
                throw error
            }
            store.clearNotFound()
            // Ответил и сервер, и тот, кому не ответил /v1/meta: отметка «не
            // отвечает» снимается любым удачным ответом.
            store.clearUnreachable()
            val extras = fetchExtras(extraGroups, serverOk = meta?.status == "ok", from = from)

            // Пока шёл запрос, человек мог сменить группу, роль или остальные группы.
            // Тогда пришедшее расписание — чужое, и записывать его нельзя: оно
            // молча возвращало на экран прежние пары поверх только что выбранных.
            // Всё, что пишет, — внутри writeIfStillAsked: сверку не забыть и не
            // переставить за запись.
            writeIfStillAsked(asked, ::subject) {
                // Ответ пришёл под другим id: группу или преподавателя переименовали
                // в таблице, и сервер ответил по памяти о старом имени. Переписываем
                // выбор у себя — после сверки, иначе она сочла бы его чужим.
                val adopted = fresh.groupId != subject
                if (adopted) {
                    if (teacherMode) store.adoptTeacher(fresh.groupId, fresh.groupName)
                    else store.adoptGroup(fresh.groupId, fresh.groupName)
                }
                writeExtras(extras)

                val previous = schedule.first()
                // Окна не было — снимок записан сборкой до 81-й, где обрубок не
                // помечался: сравнивать с ним нельзя, «добавилась пара» было бы
                // ложным.
                val comparable = store.windowFrom() != null
                store.putSchedule(json.encodeToString(fresh), fresh.generatedAt, windowFrom = windowMark(from))
                // Записанное — уже на телефоне. Объявить и переставить будильники
                // надо и тогда, когда корутину отменили посреди (ушли из
                // приложения): раньше это глоталось как Failed, изменение не
                // объявлялось никогда, а будильник об отменённой паре срабатывал.
                withContext(NonCancellable) {
                    updateWidgets()
                    // Об изменениях — только своей группы: остальные на экране
                    // для справки, и уведомлять о каждой их замене — шум.
                    if (comparable) announceChanges(previous, fresh, adopted)
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
            // доходил до виджетов только со звонком или в 00:01.
            withContext(NonCancellable) { updateWidgets() }
            RefreshResult.Failed(error)
        }
    }
}
