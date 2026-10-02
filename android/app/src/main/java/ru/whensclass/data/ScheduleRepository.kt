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
import ru.whensclass.BuildConfig
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.WeekWidget
import ru.whensclass.work.MidnightUpdater

class ScheduleRepository(
    private val context: Context,
    private val api: ScheduleApi,
    private val store: ScheduleStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Разбор — не на главном потоке: collectAsState собирает поток там же, где
    // идёт отрисовка, а недельный JSON на медленном телефоне разбирается заметно.
    val schedule: Flow<ScheduleDto?> = store.scheduleJson
        // Хранилище общее: без этого любая запись в него (счётчик, тема, время
        // проверки) заново разбирала неизменный JSON.
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
        main?.let {
            // Своя среди остальных бывает только из старого выбора — дважды не показываем.
            val others = extras.filter { group -> group.id != it.groupId }
            combineGroups(it, others.map { group -> group.name to saved[group.id]?.takeUnless { group.gone } })
        }
    }.flowOn(Dispatchers.Default)

    val fetchedAt: Flow<Long> = store.fetchedAt

    /**
     * Чьё расписание сейчас просим: роль, выбранный и остальные группы.
     *
     * Пока идёт запрос, человек успевает сменить группу или роль, и ответ
     * тогда чужой — он вернул бы на экран прежние пары поверх только что
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

        // Непрочитанное прежнее не затираем — дописываем ([ScheduleStore.pendingChanges]).
        // Прочитанное или смахнутое — начинаем заново.
        val pending = if (Notifications.changesShown(context)) store.pendingChanges() else emptyList()
        val lines = mergeChanges(pending, changes.map { it.day to it.text }, today)
        store.putPendingChanges(lines)

        // День — словами и датой, а не «Завтра»: уведомление висит в шторке до
        // утра, и наутро «Завтра» читалось бы как послезавтра. Снимается само
        // к концу последнего дня, о котором говорит.
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
     * Плашку на экране видит только тот, кто открыл приложение. Двухчасовая
     * задержка отсекает чихи Google, которые сервер сам лечит к следующему
     * заходу.
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
        // Считаем, только когда виджет и правда стоит на экране: перерисовка
        // пустого места — не показ. Счётчик — не повод падать: при забитой
        // памяти запись бросает, а виджеты уже перерисованы.
        if (widgetsPlaced()) runCatching { store.countWidgetDraw() }
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
     * Расписания остальных выбранных групп — разом. Не достучались до какой-то
     * — у неё остаётся прежний снимок: прежние пары лучше пустого места.
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
                // 404 при здоровом сервере — группы, похоже, больше нет в
                // таблице. Первое 404 — только подозрение (как у своей группы):
                // не «не обновилась», а подтвердится через час — пропажа.
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
            // Группу переименовали: сервер ответил под новым id. Записать его
            // можно только после сверки выбора — она сочла бы его чужим.
            if (schedule.groupId != group.id) result.renamed[group.id] = schedule.groupId to schedule.groupName
        }
        result
    }

    /**
     * Снимок какой-то из остальных групп не с того же разбора таблицы (gen),
     * что своё, — её пары пора принести. Сверка по gen, а не по наличию
     * сегодняшнего дня: иначе не пришедшая группа держала бы отменённые пары
     * до следующей правки таблицы, а группа на практике качалась бы на каждом
     * открытии.
     */
    private suspend fun extrasMissing(groups: List<ExtraGroup>): Boolean {
        val asked = groups.filterNot { it.gone }
        if (asked.isEmpty()) return false
        val saved = extraSchedules.first()
        val gen = store.generatedAt.first()
        return asked.any { saved[it.id]?.generatedAt != gen }
    }

    /**
     * Записать принесённое остальными группами — внутри сверки выбора.
     * Переименованные — под новым id, пропавшие — отметкой и, если просили,
     * уведомлением, не ответившие — с прежним снимком.
     */
    private suspend fun writeExtras(extras: Extras) {
        extras.renamed.forEach { (old, new) -> store.adoptExtra(old, new.first, new.second) }
        store.clearExtrasNotFound(extras.found.map { extras.renamed[it]?.first ?: it }.toSet())
        // Выбор не стираем, а отмечаем: вернётся группа — вернутся и её пары.
        // Сказать — один раз, если просили; все пропавшие за заход — одним
        // уведомлением: с одним id каждое следующее затёрло бы прежнее.
        val newlyGone = extras.gone.filter { store.markExtraGone(it.id) }.map { it.name }
        if (newlyGone.isNotEmpty() && store.notifyGroupsGoneEnabled()) {
            val one = newlyGone.size == 1
            Notifications.subgroupGone(
                context,
                if (one) "Группы ${newlyGone[0]} сейчас нет в таблице"
                else "Групп ${newlyGone.joinToString(", ")} сейчас нет в таблице",
                (if (one) "Её пары пока не показываются и вернутся сами, когда она появится. " +
                    "Если её переименовали — выберите заново в настройках."
                else "Их пары пока не показываются и вернутся сами, когда они появятся. " +
                    "Если их переименовали — выберите заново в настройках."),
            )
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

    /**
     * Списки групп и преподавателей: сохранённый — сразу и без сети, свежий —
     * следом, чтобы в плохой сети не крутить спиннер при списке на телефоне.
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
     * id, его и закрепляем. Иначе звёздочка молча пропала бы.
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
    suspend fun addExtraGroups(groups: List<GroupDto>): RefreshResult? {
        val current = store.currentExtraGroups()
        val own = store.currentGroupId()
        val added = groups
            .filter { group -> group.id != own && current.none { it.id == group.id } }
            .distinctBy { it.id }
            .map { ExtraGroup(it.id, it.name) }
        if (added.isEmpty()) return null
        store.setExtraGroups(current + added)
        // Не пришла добавленная группа — повторить, как только будет связь:
        // её значок без пар читается как «пар нет».
        val result = refresh(force = true)
        if (result is RefreshResult.Failed || result is RefreshResult.Partial) {
            ru.whensclass.work.SyncWorker.now(context)
        }
        return result
    }

    /** Убрать группу из остальных — вместе с её парами на экране, сразу и без сети. */
    suspend fun removeExtraGroup(id: String) {
        store.setExtraGroups(store.currentExtraGroups().filter { it.id != id })
    }

    /**
     * После смены выбора: перерисовать сразу, переставить напоминания по тому,
     * что теперь лежит на телефоне (без связи иначе пришло бы напоминание о
     * паре прежней группы), и сходить за новым. Не вышло — повторит фоновая
     * работа, как только будет сеть.
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
     * Обновления — по одному: параллельные (двойное нажатие ⟳, часовой заход и
     * открытие приложения разом) затирали бы флаги друг друга, а более старый
     * ответ мог бы лечь поверх нового.
     */
    private val refreshLock = Mutex()

    /** Идёт ли сейчас обновление. */
    val refreshing: Boolean get() = refreshLock.isLocked

    /**
     * Обновляет расписание.
     *
     * Сначала спрашиваем /v1/meta: если сервер не перечитывал таблицу с
     * прошлого раза, качать расписание незачем. Ошибку наружу не выносим:
     * прежние пары на виджете лучше сообщения об ошибке.
     */
    suspend fun refresh(force: Boolean = false): RefreshResult =
        refreshLock.withLock { refreshOnce(force) }

    private suspend fun refreshOnce(force: Boolean): RefreshResult = withContext(Dispatchers.IO) {
        // Снимок сборок до 0.1.4 лежит склеенным с парами соседки: сравнить
        // его со своими парами значило бы объявить её пары отменёнными.
        store.migrateGroups()
        store.migrateFormat()
        val asked = subject()
        val teacherMode = asked.teacher
        val subject = asked.id
        val extraGroups = if (teacherMode) emptyList() else store.currentExtraGroups().filter { it.id in asked.extras }
        if (subject == null) return@withContext RefreshResult.NoGroup
        // Неделя — одна на весь заход: иначе запрос через полночь воскресенья
        // записал бы окно прошлой недели с меткой новой.
        val from = weekStart()
        try {
            // Перезапрашиваем, даже если у сервера ничего не изменилось, когда:
            // сегодняшнего дня среди сохранённых нет (виджет пустеет);
            val outdated = !coversToday(schedule.first()) ||
                // началась новая неделя — понедельник лежал восьмым днём
                // прошлого окна, и до правки таблицы был бы виден один он;
                store.windowFrom() != windowMark(from) ||
                // снимок какой-то из остальных групп не дошёл.
                extrasMissing(extraGroups)
            // Состояние сервера спрашиваем всегда, и при ручном обновлении
            // тоже: именно тогда оно и нужно.
            val metaAnswer = runCatching { api.meta() }
            val meta = metaAnswer.getOrNull()
            // 429 — сервер ответил, он просто занят (лимит nginx на адрес
            // оператора): это не «не отвечает».
            val busy = (metaAnswer.exceptionOrNull() as? HttpFailure)?.code == 429
            val tooOld = (metaAnswer.exceptionOrNull() as? HttpFailure)?.code == 426 ||
                (meta?.minBuild ?: 0) > appBuild(BuildConfig.VERSION_CODE, BuildConfig.CHANNEL)
            if (tooOld) {
                // Расписание сервер этой сборке уже не отдаст: на экране —
                // «обновите», а не сбой сервера и не попытка за попыткой.
                store.clearUnreachable()
                store.putServerState(STATUS_UNSUPPORTED, meta?.sourceUrl, null)
                updateWidgets()
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
                    announceStale(STATUS_UNREACHABLE, down.since.toString())
                }
            }
            // Сервер занят — и своё, и другие группы упрутся в тот же лимит:
            // не тратить его на заведомо отказанные запросы.
            if (busy) return@withContext RefreshResult.Failed(metaAnswer.exceptionOrNull()!!)
            if (!force && !outdated && meta != null) {
                if (meta.generatedAt == store.generatedAt.first()) {
                    // Данные те же, но проверку показать надо: иначе кажется,
                    // что кнопка обновления не работает.
                    store.touchChecked()
                    updateWidgets()
                    return@withContext RefreshResult.AlreadyFresh
                }
            }
            coroutineScope {
                // Другие группы — вместе со своей, а не после неё: иначе своё
                // ждало бы лишний круг сети и терялось при отмене захода.
                val extrasAsked = async { fetchExtras(extraGroups, serverOk = meta?.health == "ok", from = from) }
                val fresh = try {
                    if (teacherMode) {
                        api.teacher(subject, from = from, days = DAYS)
                    } else {
                        api.schedule(subject, from = from, days = DAYS)
                    }
                } catch (error: HttpFailure) {
                    extrasAsked.cancel()
                    // 404 при здоровом сервере — группы в таблице больше нет:
                    // переименовали, разделили, убрали. Молчать прежним
                    // расписанием — врать, но и не с первого раза ([ScheduleStore.gone]).
                    if (error.code == 404 && meta?.health == "ok") {
                        val confirmed = store.noteNotFound(System.currentTimeMillis())
                        if (confirmed) {
                            updateWidgets()
                            // Будильники по прежнему снимку снять сразу: иначе
                            // напоминание позвало бы на пару пропавшей группы.
                            LessonAlarms.reschedule(context)
                            return@coroutineScope RefreshResult.Gone
                        }
                    }
                    throw error
                }
                store.clearNotFound()
                // Отметка «не отвечает» снимается любым удачным ответом, даже
                // если /v1/meta не ответил.
                store.clearUnreachable()
                val extras = extrasAsked.await()

                // Сверка выбора ([subject]). Всё, что пишет, — внутри
                // writeIfStillAsked, чтобы сверку нельзя было забыть или
                // переставить за запись. Другие группы в сверку не входят — их
                // пишет writeExtras по нынешнему выбору.
                writeIfStillAsked(asked.own(), { subject().own() }) {
                    // Ответ пришёл под другим id: группу или преподавателя переименовали
                    // в таблице, и сервер ответил по памяти о старом имени.
                    val adopted = fresh.groupId != subject
                    if (adopted) {
                        if (teacherMode) store.adoptTeacher(fresh.groupId, fresh.groupName)
                        else store.adoptGroup(fresh.groupId, fresh.groupName)
                    }
                    writeExtras(extras)

                    val previous = schedule.first()
                    // Окна нет — снимок записан сборкой до 81-й, где обрубок не
                    // помечался: «добавилась пара» по нему было бы ложным.
                    val comparable = store.windowFrom() != null
                    store.putSchedule(json.encodeToString(fresh), fresh.generatedAt, windowFrom = windowMark(from))
                    // Записанное — уже на телефоне: объявить и переставить будильники
                    // надо, даже если корутину отменили (ушли из приложения), иначе
                    // сработал бы будильник об отменённой паре.
                    withContext(NonCancellable) {
                        updateWidgets()
                        // Об изменениях — только своей группы: остальные на экране
                        // для справки, и уведомлять о каждой их замене — шум.
                        if (comparable) announceChanges(previous, fresh, adopted)
                        LessonAlarms.reschedule(context)
                        // И будильник к звонку: он считается по сетке из снимка, а при
                        // первом запуске, когда его взводит WhensClassApp, сетки ещё нет.
                        MidnightUpdater.schedule(context)
                    }
                    // Выбор другой группы мог смениться, пока шёл запрос: о тех,
                    // что уже убраны, «не обновилась» не говорим.
                    val chosen = store.currentExtraGroups().map { it.name }.toSet()
                    val missed = extras.missed.filter { it in chosen }
                    if (missed.isEmpty()) RefreshResult.Updated
                    else RefreshResult.Partial(missed, extras.fresh.filter { it in chosen }.toSet())
                } ?: RefreshResult.AlreadyFresh
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Неудача — тоже повод перерисовать: иначе «сервер не отвечает»
            // дошёл бы до виджетов только со звонком.
            withContext(NonCancellable) { updateWidgets() }
            RefreshResult.Failed(error)
        }
    }
}
