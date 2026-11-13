package ru.whensclass.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.whensclass.BuildConfig
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import ru.whensclass.work.MidnightUpdater

class ScheduleRepository(
    private val context: Context,
    private val api: ScheduleApi,
    private val store: ScheduleStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Живёт с процессом, как и сам репозиторий (AppContainer). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Разбор — не на главном потоке: collectAsState собирает поток там же, где рисует, а недельный
    // JSON на медленном телефоне разбирается заметно.
    val schedule: Flow<ScheduleDto?> = store.scheduleJson
        // Хранилище общее: иначе любая запись в него (счётчик, тема) заново разбирала неизменный JSON.
        .distinctUntilChanged()
        .map { body ->
            body?.let { runCatching { json.decodeFromString<ScheduleDto>(it) }.getOrNull() }
        }
        .flowOn(Dispatchers.Default)
        // Один разбор на всех: экран собирает и расписание, и [shownSchedule] поверх него.
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

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
     * Для экрана: своё расписание вместе с парами остальных групп ([combineGroups]).
     * Пропавшая из таблицы группа держит место в значках, но её прежних пар не показываем: они могли поменяться.
     */
    val shownSchedule: Flow<ScheduleDto?> = combine(schedule, extraGroups, extraSchedules) { main, extras, saved ->
        main?.let {
            // Своя среди остальных бывает только из старого выбора.
            val others = extras.filter { group -> group.id != it.groupId }
            combineGroups(it, others.map { group -> group.name to saved[group.id]?.takeUnless { group.gone } })
        }
    }.flowOn(Dispatchers.Default)

    val fetchedAt: Flow<Long> = store.fetchedAt

    /**
     * Чьё расписание сейчас просим: роль, выбранный и остальные группы.
     * Пока идёт запрос, выбор могли сменить, и чужой ответ вернул бы на экран прежние пары:
     * поэтому перед записью спрашиваем ещё раз и сверяем целиком.
     */
    private suspend fun subject(): Subject {
        val teacher = store.teacherMode()
        return Subject(
            teacher = teacher,
            id = if (teacher) store.teacherId.first() else store.currentGroupId(),
            extras = store.currentExtraGroups().map { it.id },
        )
    }

    /**
     * Лежит ли сегодня внутри сохранённого: дни есть и до, и начиная с него. Воскресенья в ответе
     * нет, иначе каждый заход в него шёл бы полным путём.
     */
    private fun coversToday(saved: ScheduleDto?): Boolean {
        if (saved == null) return false
        val today = ru.whensclass.widget.collegeToday().toString()
        return saved.days.any { it.date >= today } && saved.days.any { it.date <= today }
    }

    /**
     * Рассказать об изменениях, если человек этого хотел. Далёкое (послезавтрашнюю замену) посреди
     * пары не сообщаем.
     */
    private suspend fun announceChanges(old: ScheduleDto?, fresh: ScheduleDto, adopted: Boolean = false) {
        if (!store.notifyChangesEnabled()) return
        val today = ru.whensclass.widget.collegeToday()
        val soon = setOf(today.toString(), today.plusDays(1).toString())
        // Id сменил сервер (переименование), а не человек.
        val changes = ScheduleDiff.compare(old, fresh, sameSubject = adopted.takeIf { it })
            .filter { it.day in soon }
        if (changes.isEmpty()) return

        // Непрочитанное и придержанное на ночь дописываем ([ScheduleStore.pendingChanges]);
        // прочитанное или смахнутое — заново.
        val held = store.changesHeld()
        val pending = if (held || Notifications.changesShown(context)) store.pendingChanges() else emptyList()
        val lines = mergeChanges(pending, changes.map { it.day to it.text }, today)
        store.putPendingChanges(lines)
        if (quietHours(ru.whensclass.widget.collegeNow())) {
            store.setChangesHeld(true)
            return
        }
        if (held) store.setChangesHeld(false)
        showChanges(lines)
    }

    /** Ночь кончилась — придержанное одним уведомлением ([quietHours]). */
    private suspend fun releaseHeldChanges() {
        if (!store.changesHeld() || quietHours(ru.whensclass.widget.collegeNow())) return
        // Целиком: отмена между снятием флага и показом потеряла бы ночь.
        withContext(NonCancellable) {
            if (store.notifyChangesEnabled()) {
                val lines = mergeChanges(store.pendingChanges(), emptyList(), ru.whensclass.widget.collegeToday())
                store.putPendingChanges(lines)
                if (lines.isNotEmpty()) showChanges(lines)
            }
            store.setChangesHeld(false)
        }
    }

    private fun showChanges(lines: List<Pair<String, String>>) {
        // День — словами и датой, а не «Завтра»: уведомление висит до утра, и «Завтра» читалось бы
        // как послезавтра. Снимается само к концу последнего дня.
        val text = lines.joinToString("\n") { (day, change) ->
            "${ru.whensclass.widget.formatDayDate(java.time.LocalDate.parse(day))}: $change"
        }
        val lastDay = lines.maxOf { java.time.LocalDate.parse(it.first) }
        val until = lastDay.plusDays(1).atStartOfDay(ru.whensclass.widget.COLLEGE_ZONE)
            .toInstant().toEpochMilli()
        Notifications.changes(context, "Расписание изменилось", text, until)
    }

    /**
     * Сервер лежит дольше двух часов — сказать уведомлением, один раз на сбой. Плашку видит только
     * открывший приложение; задержка отсекает чихи Google, которые сервер лечит к следующему
     * заходу.
     */
    /**
     * `countFrom` — с какого момента считать срок, если не с `sinceIso`: у «не отвечает» — с начала серии неудач,
     * иначе вечерняя неудача и утренние полчаса сложились бы через ночь в «два часа».
     */
    private suspend fun announceStale(status: String?, sinceIso: String?, countFrom: java.time.Instant? = null) {
        if (status == "ok" || sinceIso == null) {
            if (store.staleNotifiedFor() != null) {
                store.setStaleNotifiedFor(null)
                Notifications.serverBack(context)
            }
            return
        }
        if (!store.notifyServerEnabled()) return
        // Колледж правит таблицу: ждать, а не тревожиться.
        if (status == STATUS_EDITING) return
        if (store.staleNotifiedFor() == sinceIso) return
        val since = runCatching { java.time.Instant.parse(sinceIso) }.getOrNull() ?: return
        if (System.currentTimeMillis() - (countFrom ?: since).toEpochMilli() < STALE_NOTIFY_AFTER_MILLIS) return
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

    /** Есть ли у телефона проверенный выход в интернет — тогда молчание сервера не наше. */
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
    }

    /**
     * Расписания остальных групп разом. Не достучались до какой-то — остаётся прежний снимок: он
     * лучше пустого места.
     */
    private suspend fun fetchExtras(
        extras: List<ExtraGroup>, serverOk: Boolean, from: java.time.LocalDate,
    ): Extras = coroutineScope {
        val answers = extras.map { group ->
            async { group to runCatching { api.schedule(group.id, from = from, days = DAYS) } }
        }.awaitAll()
        val result = Extras()
        val saved = extraSchedules.first()
        for ((group, answer) in answers) {
            val schedule = answer.getOrElse { error ->
                if (error is CancellationException) throw error
                // 404 при здоровом сервере — группы, похоже, нет в таблице; первое 404 лишь
                // подозрение, пропажа подтвердится через час.
                val notFound = error is HttpFailure && error.code == 404 && serverOk
                if (notFound && store.noteExtraNotFound(group.id, System.currentTimeMillis())) {
                    result.gone += group
                } else if (!group.gone && !notFound) {
                    result.missed += group.name
                    if (saved[group.id] == null) result.fresh += group.name
                }
                continue
            }
            result.found += group.id
            result.schedules[group.id] = schedule
            // Переименование: новый id пишем только после сверки выбора — она сочла бы его чужим.
            if (schedule.groupId != group.id) result.renamed[group.id] = schedule.groupId to schedule.groupName
        }
        result
    }

    /**
     * Снимок одной из остальных групп не с того же разбора (gen), что своё, — её пары пора
     * принести. Сверка по gen, а не по наличию сегодняшнего дня: иначе не пришедшая группа держала
     * бы отменённые пары до правки таблицы.
     */
    private suspend fun extrasMissing(groups: List<ExtraGroup>, from: String): Boolean {
        val asked = groups.filterNot { it.gone }
        if (asked.isEmpty()) return false
        val saved = extraSchedules.first()
        val gen = store.generatedAt.first()
        // Gen тот же, а снимок прошлой недели: своё окно пришло в понедельник, запрос другой группы сорвался.
        return asked.any { group ->
            val one = saved[group.id]
            one == null || one.generatedAt != gen || one.days.firstOrNull()?.date?.let { it < from } == true
        }
    }

    /**
     * Записать принесённое остальными группами — внутри сверки выбора.
     * Переименованные — под новым id, пропавшие — отметкой и уведомлением, не ответившие — с прежним снимком.
     */
    private suspend fun writeExtras(extras: Extras) {
        extras.renamed.forEach { (old, new) -> store.adoptExtra(old, new.first, new.second) }
        store.clearExtrasNotFound(extras.found.map { extras.renamed[it]?.first ?: it }.toSet())
        // Выбор не стираем, а отмечаем: вернётся группа — вернутся пары.
        // Все пропавшие за заход — одним уведомлением (с одним id следующее затёрло бы прежнее).
        extras.gone.forEach { store.markExtraGone(it.id) }
        // Сказано — только показанное: при запрещённых уведомлениях скажут, когда разрешат.
        val untold = store.currentExtraGroups().filter { it.gone && !it.told }
        val newlyGone = untold.map { it.name }
        if (newlyGone.isNotEmpty() && store.notifyGroupsGoneEnabled()) {
            val one = newlyGone.size == 1
            val shown = Notifications.subgroupGone(
                context,
                if (one) "Группы ${newlyGone[0]} сейчас нет в таблице"
                else "Групп ${newlyGone.joinToString(", ")} сейчас нет в таблице",
                (if (one) "Её пары пока не показываются и вернутся сами, когда она появится. " +
                    "Если её переименовали — выберите заново в настройках."
                else "Их пары пока не показываются и вернутся сами, когда они появятся. " +
                    "Если их переименовали — выберите заново в настройках."),
            )
            if (shown) store.markExtrasTold(untold.map { it.id }.toSet())
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
        /** Не пришли (сеть, 429) — имена. */
        val missed = mutableListOf<String>()
        /** Из них — те, чьих пар на телефоне ещё не было. */
        val fresh = mutableSetOf<String>()
    }

    /** Списки групп и преподавателей: сохранённый — сразу и без сети, свежий — следом, чтобы не крутить спиннер. */
    suspend fun cachedGroups(): List<GroupDto> = withContext(Dispatchers.IO) {
        store.groupsJson.first()
            ?.let { runCatching { json.decodeFromString<GroupsDto>(it).groups }.getOrNull() }
            .orEmpty()
    }

    /**
     * Расписание сервер этой сборке не отдаст: «обновите» на экране, в виджетах и одним
     * уведомлением, а не сбой сервера.
     */
    private suspend fun markUnsupported(sourceUrl: String? = null) {
        val first = store.serverStatus.first() != STATUS_UNSUPPORTED
        store.clearUnreachable()
        store.putServerState(STATUS_UNSUPPORTED, sourceUrl, null)
        updateWidgets()
        if (first) {
            ru.whensclass.notify.Notifications.newVersion(
                context,
                "Нужно обновить приложение",
                "Сервер больше не поддерживает эту версию, расписание не обновляется. " +
                    "Нажмите, чтобы установить новую.",
            )
        }
    }

    private suspend fun <T> loadList(load: () -> T): T? {
        val answer = runCatching(load)
        if ((answer.exceptionOrNull() as? HttpFailure)?.code == 426) markUnsupported()
        return answer.getOrNull()
    }

    /** Кабинеты дня: ответ, или «дня в таблице нет» (404), или сбой — оба пусты. */
    suspend fun rooms(date: String?): RoomsAnswer = withContext(Dispatchers.IO) {
        val answer = runCatching { api.rooms(date) }
        val code = (answer.exceptionOrNull() as? HttpFailure)?.code
        if (code == 426) markUnsupported()
        RoomsAnswer(answer.getOrNull(), missing = code == 404, answered = code != null)
    }

    /** Лист пересдач; body null — не пришёл: `answered` — сервер ответил ошибкой, иначе нет связи. */
    suspend fun retakes(): RetakesAnswer = withContext(Dispatchers.IO) {
        val answer = runCatching { api.retakes() }
        val code = (answer.exceptionOrNull() as? HttpFailure)?.code
        if (code == 426) markUnsupported()
        RetakesAnswer(answer.getOrNull(), answered = code != null)
    }

    /** Свежий список групп; null — сервер не ответил. */
    suspend fun freshGroups(): List<GroupDto>? = withContext(Dispatchers.IO) {
        val fresh = loadList { api.groups() } ?: return@withContext null
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
        val fresh = loadList { api.teachers() } ?: return@withContext null
        store.putTeachers(json.encodeToString(fresh))
        fresh.teachers
    }

    /**
     * Закреплённые преподаватели — за исправленным именем. Id, которого нет в свежем списке,
     * спрашиваем у сервера: он отвечает расписанием исправленного (docs/api.md).
     */
    suspend fun followRenamedPins(groups: List<GroupDto>, teachers: List<GroupDto>) =
        withContext(Dispatchers.IO) {
            // Группы по старому имени сервер не сводит (404): закреплённая остаётся и видна, если группа вернётся.
            val teacherIds = teachers.map { it.id }.toSet()
            for (old in store.pinnedTeachers.first().filter { it !in teacherIds }) {
                val now = runCatching { api.teacher(old, days = 1).groupId }.getOrNull() ?: continue
                if (now != old && now in teacherIds) store.replacePinnedTeacher(old, now)
            }
        }

    /** Расписание преподавателя — берём по запросу, на телефоне не храним. */
    suspend fun teacherSchedule(teacherId: String): ScheduleAnswer = withContext(Dispatchers.IO) {
        loadOpened { api.teacher(teacherId, from = weekStart(), days = DAYS) }
    }

    /** Открытое расписание и код ошибки, если сервер ответил ею: «проверьте интернет» — только без ответа. */
    private suspend fun loadOpened(load: () -> ScheduleDto): ScheduleAnswer {
        val answer = runCatching(load)
        val code = (answer.exceptionOrNull() as? HttpFailure)?.code
        if (code == 426) markUnsupported()
        return ScheduleAnswer(answer.getOrNull(), code)
    }

    /** Студент выбрал группу — заодно это и означает, что он студент. */
    suspend fun selectGroup(group: GroupDto): RefreshResult {
        // Смотрим до смены роли: после неё «та же группа» уже не значит «ничего не изменилось».
        val unchanged = !store.teacherMode() && store.currentGroupId() == group.id
        withContext(NonCancellable) {
            choiceLock.withLock {
                store.setTeacherMode(false)
                store.selectGroup(group.id, group.name, unchanged)
            }
            followChoice()
        }
        return afterSelection()
    }

    /** Преподаватель выбрал себя — дальше всё работает как у студента. */
    suspend fun selectSelfAsTeacher(teacher: GroupDto): RefreshResult {
        val unchanged = store.teacherMode() && store.teacherId.first() == teacher.id
        withContext(NonCancellable) {
            choiceLock.withLock {
                store.setTeacherMode(true)
                store.selectTeacher(teacher.id, teacher.name, unchanged)
            }
            followChoice()
        }
        return afterSelection()
    }

    /**
     * Добавить группы к остальным — в конец, без своей и без повторов, пока влезает [MAX_GROUPS].
     * Виджеты и напоминания они не трогают.
     */
    suspend fun addExtraGroups(groups: List<GroupDto>): RefreshResult? {
        val own = store.currentGroupId()
        var added = emptyList<ExtraGroup>()
        // Чтение и запись одной правкой: фон мог отметить пропажу между ними.
        store.editExtras { current ->
            added = groups
                .filter { group -> group.id != own && current.none { it.id == group.id } }
                .distinctBy { it.id }
                .map { ExtraGroup(it.id, it.name) }
            (current + added).take(MAX_GROUPS - 1)
        }
        if (added.isEmpty()) return null
        // Добавленная группа не пришла — повторить при связи: значок без пар читается как «пар нет».
        val result = refresh(force = true)
        if (result is RefreshResult.Failed || result is RefreshResult.Partial) {
            ru.whensclass.work.SyncWorker.now(context)
        }
        return result
    }

    /** Убрать группу из остальных — вместе с её парами на экране, сразу и без сети. */
    suspend fun removeExtraGroup(id: String) {
        store.editExtras { list -> list.filter { it.id != id } }
    }

    /**
     * После смены выбора: перерисовать, переставить напоминания по лежащему на телефоне и сходить
     * за новым; не вышло — повторит фон.
     */
    /** Итог загрузки — для значка ⟳ на экране. */
    /**
     * Виджеты и будильники — за записанным выбором, неотменяемым куском: иначе остались бы
     * напоминания прежней группы.
     */
    private suspend fun followChoice() {
        updateWidgets()
        LessonAlarms.reschedule(context)
    }

    private suspend fun afterSelection(): RefreshResult {
        val result = try {
            refresh(force = true)
        } catch (error: CancellationException) {
            // Загрузку оборвали («назад», поворот): без повтора снимка не будет до следующего захода.
            ru.whensclass.work.SyncWorker.now(context)
            throw error
        }
        if (result is RefreshResult.Failed) {
            ru.whensclass.work.SyncWorker.now(context)
        }
        return result
    }

    /** Расписание группы — для просмотра чужого, без смены роли. */
    suspend fun groupSchedule(groupId: String): ScheduleAnswer = withContext(Dispatchers.IO) {
        loadOpened { api.schedule(groupId, from = weekStart(), days = DAYS) }
    }

    /** Обновления — по одному: параллельные затирали бы флаги друг друга, а старый ответ мог лечь поверх нового. */
    private val refreshLock = Mutex()
    // Сверка выбора и запись ответа — одним куском: иначе выбор, записанный между ними, получил бы
    // снимок прежней группы. Короткий: сеть под ним не ждут.
    private val choiceLock = Mutex()

    /** Идёт ли сейчас обновление. */
    val refreshing: Boolean get() = refreshLock.isLocked

    /**
     * Обновляет расписание. Сначала /v1/meta: если сервер таблицу не перечитывал, качать незачем.
     * Ошибку наружу не выносим: прежние пары лучше сообщения.
     */
    suspend fun refresh(force: Boolean = false): RefreshResult {
        val asked = System.currentTimeMillis()
        return refreshLock.withLock {
            // Ждали чужой заход, а он не дозвался сервера: второй круг по тому же таймауту ничего не даст.
            silentFailure?.takeIf { (at, _) -> at in asked..System.currentTimeMillis() }?.let { (_, result) -> return@withLock result }
            refreshOnce(force).also { result ->
                silentFailure = if (result is RefreshResult.Failed && result.error is java.io.IOException &&
                    result.error !is HttpFailure
                ) {
                    System.currentTimeMillis() to result
                } else {
                    null
                }
            }
        }
    }

    /** Последний заход, не дозвавшийся сервера, и когда он кончился. Под [refreshLock]. */
    private var silentFailure: Pair<Long, RefreshResult>? = null

    private suspend fun refreshOnce(force: Boolean): RefreshResult = withContext(Dispatchers.IO) {
        // Снимок сборок до 0.1.4 склеен с парами соседки: сравнение объявило бы её пары отменёнными.
        store.migrateGroups()
        store.migrateFormat()
        store.forgetUnsupportedAfterUpdate()
        val asked = subject()
        val teacherMode = asked.teacher
        val subject = asked.id
        val extraGroups = if (teacherMode) emptyList() else store.currentExtraGroups().filter { it.id in asked.extras }
        if (subject == null) return@withContext RefreshResult.NoGroup
        releaseHeldChanges()
        // Неделя — одна на весь заход: иначе запрос через полночь воскресенья записал бы окно
        // прошлой недели с меткой новой.
        val from = weekStart()
        try {
            // Перезапрашиваем, даже если у сервера ничего не изменилось, когда:
            // сегодняшнего дня среди сохранённых нет (виджет пустеет);
            val outdated = !coversToday(schedule.first()) ||
                // началась новая неделя: понедельник лежал восьмым днём прошлого окна;
                store.windowFrom() != windowMark(from) ||
                // снимок какой-то из остальных групп не дошёл.
                extrasMissing(extraGroups, from.toString())
            // Состояние сервера спрашиваем всегда, и при ручном обновлении: тогда оно и нужно.
            val metaAnswer = runCatching { api.meta() }
            val meta = metaAnswer.getOrNull()
            // 429 — сервер занят (лимит nginx на адрес оператора), это не «не отвечает».
            val busy = (metaAnswer.exceptionOrNull() as? HttpFailure)?.code == 429
            val tooOld = (metaAnswer.exceptionOrNull() as? HttpFailure)?.code == 426 ||
                (meta?.minBuild ?: 0) > appBuild(BuildConfig.VERSION_CODE, BuildConfig.CHANNEL)
            if (tooOld) {
                markUnsupported(meta?.sourceUrl)
                return@withContext RefreshResult.Failed(HttpFailure(426, "/v1/meta"))
            }
            if (meta != null) {
                store.clearUnreachable()
                store.putServerState(meta.health, meta.sourceUrl, meta.since)
                announceStale(meta.health, meta.since)
            } else if (!busy && networkUp()) {
                val now = java.time.Instant.now()
                val down = store.noteUnreachable(now)
                if (now.toEpochMilli() - down.run.toEpochMilli() >= UNREACHABLE_BROKEN_AFTER_MILLIS) {
                    store.putServerState(STATUS_UNREACHABLE, null, down.since.toString())
                    announceStale(STATUS_UNREACHABLE, down.since.toString(), countFrom = down.run)
                }
            }
            // Сервер занят — другие группы упрутся в тот же лимит.
            if (busy) return@withContext RefreshResult.Failed(metaAnswer.exceptionOrNull()!!)
            // Сервер не ответил вовсе (таймаут, обрыв): расписание упрётся в тот же таймаут.
            metaAnswer.exceptionOrNull()?.takeIf { it is java.io.IOException && it !is HttpFailure }?.let { throw it }
            if (!force && !outdated && meta != null) {
                if (meta.generatedAt == store.generatedAt.first()) {
                    // Данные те же, но проверку показать надо: иначе кажется, что ⟳ не работает.
                    store.touchChecked()
                    updateWidgets()
                    return@withContext RefreshResult.AlreadyFresh
                }
            }
            coroutineScope {
                // Другие группы — вместе со своей: иначе своё ждало бы лишний круг сети и терялось при отмене.
                val extrasAsked = async { fetchExtras(extraGroups, serverOk = meta?.health == "ok", from = from) }
                val fresh = try {
                    if (teacherMode) {
                        api.teacher(subject, from = from, days = DAYS)
                    } else {
                        api.schedule(subject, from = from, days = DAYS)
                    }
                } catch (error: HttpFailure) {
                    extrasAsked.cancel()
                    // 404 при здоровом сервере — группы в таблице больше нет (переименовали,
                    // разделили, убрали); не с первого раза ([ScheduleStore.gone]).
                    if (error.code == 404 && meta?.health == "ok") {
                        val confirmed = store.noteNotFound(System.currentTimeMillis())
                        if (confirmed) {
                            updateWidgets()
                            // Будильники по прежнему снимку снять сразу: иначе позвали бы на пару пропавшей группы.
                            LessonAlarms.reschedule(context)
                            return@coroutineScope RefreshResult.Gone
                        }
                    }
                    throw error
                }
                store.clearNotFound()
                // Отметка «не отвечает» снимается любым удачным ответом, даже без /v1/meta, — и плашка с ней.
                store.clearUnreachable()
                if (store.forgetUnreachable()) announceStale("ok", null)
                val extras = extrasAsked.await()

                // Сверка выбора ([subject]) — внутри writeIfStillAsked, чтобы её нельзя было забыть
                // или переставить за запись. Другие группы пишет writeExtras.
                choiceLock.withLock { writeIfStillAsked(asked.own(), { subject().own() }) {
                    // Ответ под другим id: группу или преподавателя переименовали, сервер ответил
                    // по памяти о старом имени.
                    val adopted = fresh.groupId != subject
                    if (adopted) {
                        if (teacherMode) store.adoptTeacher(fresh.groupId, fresh.groupName)
                        else store.adoptGroup(fresh.groupId, fresh.groupName)
                    }
                    writeExtras(extras)

                    val previous = schedule.first()
                    // Окна нет: снимок сборки до 81-й не помечал обрубок, «добавилась пара» была бы ложной.
                    val comparable = store.windowFrom() != null
                    // Запись — внутри: отменённое посреди записи ожидание оставляет снимок, а
                    // следующий заход уже не объявит и не переставит будильники — иначе сработал бы
                    // будильник об отменённой паре.
                    withContext(NonCancellable) {
                        store.putSchedule(json.encodeToString(fresh), fresh.generatedAt, windowFrom = windowMark(from))
                        updateWidgets()
                        // Об изменениях — только своей группы: остальные для справки.
                        if (comparable) announceChanges(previous, fresh, adopted)
                        LessonAlarms.reschedule(context)
                        // И будильник к звонку: считается по сетке из снимка, а при первом запуске сетки ещё нет.
                        MidnightUpdater.schedule(context)
                    }
                    // Убранным уже группам «не обновилась» не говорим.
                    val chosen = store.currentExtraGroups().map { it.name }.toSet()
                    val missed = extras.missed.filter { it in chosen }
                    if (missed.isEmpty()) RefreshResult.Updated
                    else RefreshResult.Partial(missed, extras.fresh.filter { it in chosen }.toSet())
                } } ?: RefreshResult.AlreadyFresh
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Неудача — тоже повод перерисовать: иначе «сервер не отвечает» дойдёт до виджетов только со звонком.
            withContext(NonCancellable) { updateWidgets() }
            RefreshResult.Failed(error)
        }
    }
}

/** Ответ /v1/rooms: `missing` — дня в таблице нет, а не сбой связи. */
data class RoomsAnswer(val body: RoomsDto?, val missing: Boolean = false, val answered: Boolean = false)

/** Открытое расписание; body null — не пришло, `code` — чем ответил сервер (null — не ответил). */
data class ScheduleAnswer(val body: ScheduleDto?, val code: Int? = null)

data class RetakesAnswer(val body: RetakesDto?, val answered: Boolean = false)
