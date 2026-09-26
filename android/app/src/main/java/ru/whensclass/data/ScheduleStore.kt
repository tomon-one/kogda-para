package ru.whensclass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("whensclass")

/** За сколько минут напоминать о паре, пока человек не выбрал своё время. */
const val DEFAULT_NOTIFY_BEFORE = 20

/**
 * Всё, что приложение помнит между запусками: выбранная группа и последний
 * ответ сервера.
 *
 * Виджет рисуется только отсюда и никогда из сети — поэтому он одинаково
 * работает в метро и при выключенном сервере, а обновление всего лишь меняет
 * содержимое хранилища.
 */
class ScheduleStore(private val context: Context) {

    /**
     * Прочитаны ли настройки с диска.
     *
     * Пока не прочитаны, показывать нечего: иначе приложение на первых кадрах
     * рисует экран выбора группы поверх уже выбранной, и окна «скачут».
     */
    val loaded: Flow<Boolean> = context.dataStore.data.map { true }

    val groupId: Flow<String?> = context.dataStore.data.map { it[KEY_GROUP_ID] }
    val groupName: Flow<String?> = context.dataStore.data.map { it[KEY_GROUP_NAME] }
    val scheduleJson: Flow<String?> = context.dataStore.data.map { it[KEY_SCHEDULE] }
    val groupsJson: Flow<String?> = context.dataStore.data.map { it[KEY_GROUPS] }
    val fetchedAt: Flow<Long> = context.dataStore.data.map { it[KEY_FETCHED_AT]?.toLongOrNull() ?: 0L }
    val generatedAt: Flow<String?> = context.dataStore.data.map { it[KEY_GENERATED_AT] }

    /**
     * Что сервер сказал о себе в последний раз: `ok`, `stale` или `empty`.
     *
     * Хранится, а не спрашивается на месте: без сети спросить некого, а
     * последнее известное состояние — всё же знание. Оно же и устаревает:
     * сервер мог починиться, пока телефон был вне сети.
     */
    val serverStatus: Flow<String> = context.dataStore.data.map { it[KEY_SERVER_STATUS] ?: "ok" }

    /** Адрес таблицы колледжа, как его назвал сервер. */
    val sourceUrl: Flow<String?> = context.dataStore.data.map { it[KEY_SOURCE_URL] }

    /** С какого момента сервер лежит (ISO, UTC); null, когда всё в порядке. */
    val serverSince: Flow<String?> = context.dataStore.data.map { it[KEY_SERVER_SINCE] }

    suspend fun putServerState(status: String, sourceUrl: String?, since: String? = null) {
        context.dataStore.edit {
            it[KEY_SERVER_STATUS] = status
            sourceUrl?.let { url -> it[KEY_SOURCE_URL] = url }
            if (status == "ok" || since == null) it.remove(KEY_SERVER_SINCE)
            else it[KEY_SERVER_SINCE] = since
        }
    }

    /** О каком сбое (по его `since`) телефон уже сказал уведомлением. */
    /**
     * С какого момента сервер не отвечает вовсе, хотя сеть у телефона есть.
     * Запоминается первый раз и держится, пока сервер не ответит.
     */
    suspend fun noteUnreachable(now: java.time.Instant): java.time.Instant {
        var first = now
        context.dataStore.edit { prefs ->
            val known = prefs[KEY_UNREACHABLE_SINCE]
                ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            val last = prefs[KEY_UNREACHABLE_LAST]
                ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            // Неудачи — подряд: между двумя случайными с разницей в несколько
            // часов сервер отвечал, и они не складываются в «не отвечает с
            // утра» (третий аудит, М12 прогона 1).
            val streak = known != null && last != null &&
                java.time.Duration.between(last, now) < UNREACHABLE_STREAK_GAP
            if (streak) first = known!! else prefs[KEY_UNREACHABLE_SINCE] = now.toString()
            prefs[KEY_UNREACHABLE_LAST] = now.toString()
        }
        return first
    }

    suspend fun clearUnreachable() {
        context.dataStore.edit {
            it.remove(KEY_UNREACHABLE_SINCE)
            it.remove(KEY_UNREACHABLE_LAST)
        }
    }

    suspend fun staleNotifiedFor(): String? = context.dataStore.data.first()[KEY_STALE_NOTIFIED]

    suspend fun setStaleNotifiedFor(since: String?) {
        context.dataStore.edit {
            if (since == null) it.remove(KEY_STALE_NOTIFIED) else it[KEY_STALE_NOTIFIED] = since
        }
    }

    /**
     * Группы (или преподавателя) в таблице больше нет: сервер ответил 404 при
     * здоровом состоянии.
     *
     * Верим не первому ответу: опечатку в заголовке колледж чинит через
     * двадцать минут, и выбивать из-за неё всех на перевыбор — лишнее. 404
     * должен повториться не раньше чем через час после первого.
     */
    val gone: Flow<Boolean> = context.dataStore.data.map { it[KEY_GONE] == "1" }

    /** Отмечает 404. True, если пора говорить человеку. */
    suspend fun noteNotFound(nowMillis: Long): Boolean {
        var confirmed = false
        context.dataStore.edit {
            val first = it[KEY_GONE_SINCE]?.toLongOrNull()
            when {
                first == null -> it[KEY_GONE_SINCE] = nowMillis.toString()
                nowMillis - first >= GONE_CONFIRM_MILLIS -> {
                    it[KEY_GONE] = "1"
                    confirmed = true
                }
            }
        }
        return confirmed
    }

    suspend fun clearNotFound() {
        context.dataStore.edit {
            it.remove(KEY_GONE_SINCE)
            it.remove(KEY_GONE)
        }
    }

    /**
     * За сколько минут напоминать о паре.
     *
     * Выбранное время и выключатель — разные вещи. Раньше выключение писало
     * ноль в то же поле, и время приходилось выбирать заново при каждом
     * включении; роль тут ни при чём, настройка общая для студента и
     * преподавателя.
     */
    val notifyBefore: Flow<Int> = context.dataStore.data.map(::rememberedMinutes)

    /** Включены ли напоминания. */
    val notifyEnabled: Flow<Boolean> = context.dataStore.data.map(::notifyOn)

    suspend fun notifyBeforeMinutes(): Int = context.dataStore.data.first().let {
        if (notifyOn(it)) rememberedMinutes(it) else 0
    }

    suspend fun setNotifyBefore(minutes: Int) {
        context.dataStore.edit {
            it[KEY_NOTIFY_BEFORE] = minutes.toString()
            it[KEY_NOTIFY_ON] = "1"
        }
    }

    suspend fun setNotifyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFY_ON] = if (enabled) "1" else "0" }
    }

    private fun rememberedMinutes(prefs: Preferences): Int =
        prefs[KEY_NOTIFY_BEFORE]?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_NOTIFY_BEFORE

    // У тех, кто обновился с прежней сборки, выключателя в хранилище нет:
    // тогда о нём судим по старому полю, где ноль означал «выключено».
    private fun notifyOn(prefs: Preferences): Boolean =
        prefs[KEY_NOTIFY_ON]?.let { it == "1" }
            ?: ((prefs[KEY_NOTIFY_BEFORE]?.toIntOrNull() ?: 0) > 0)

    /**
     * Сообщать ли о новой версии приложения.
     *
     * Магазина нет, обновление никто не принесёт: если о нём не сказать,
     * человек останется со сборкой, в которой ошибка, уже починенная неделю
     * назад.
     */
    val notifyUpdates: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_NOTIFY_UPDATES] != "0"
    }

    suspend fun notifyUpdatesEnabled(): Boolean = notifyUpdates.first()

    suspend fun setNotifyUpdates(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFY_UPDATES] = if (enabled) "1" else "0" }
    }

    /** Про какую сборку уже сказали — чтобы не повторяться каждый час. */
    suspend fun announcedVersion(): Int =
        context.dataStore.data.first()[KEY_UPDATE_ANNOUNCED]?.toIntOrNull() ?: 0

    suspend fun setAnnouncedVersion(code: Int) {
        context.dataStore.edit { it[KEY_UPDATE_ANNOUNCED] = code.toString() }
    }

    /** Сообщать ли об изменениях в расписании. */
    val notifyChanges: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_NOTIFY_CHANGES] != "0"
    }

    suspend fun notifyChangesEnabled(): Boolean = notifyChanges.first()

    suspend fun setNotifyChanges(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFY_CHANGES] = if (enabled) "1" else "0" }
    }

    /**
     * Закреплённые преподаватели — их показываем вверху списка.
     *
     * Списком в полторы сотни фамилий пользоваться каждый день невозможно, а
     * смотрят обычно одних и тех же: своих или собственное расписание, если
     * приложением пользуется преподаватель.
     */
    val pinnedTeachers: Flow<List<String>> = context.dataStore.data.map {
        it[KEY_PINNED_TEACHERS]?.split("\n")?.filter(String::isNotBlank).orEmpty()
    }

    /** Закреплённые группы — для тех, кто смотрит чужие расписания. */
    val pinnedGroups: Flow<List<String>> = context.dataStore.data.map {
        it[KEY_PINNED_GROUPS]?.split("\n")?.filter(String::isNotBlank).orEmpty()
    }

    suspend fun togglePinnedGroup(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PINNED_GROUPS]?.split("\n")?.filter(String::isNotBlank)
                .orEmpty()
            val next = if (id in current) current - id else current + id
            prefs[KEY_PINNED_GROUPS] = next.joinToString("\n")
        }
    }

    /** Закреплённый id сменился — переименование (М11 прогона 2). */
    suspend fun replacePinnedGroup(old: String, new: String) = replacePinned(KEY_PINNED_GROUPS, old, new)

    suspend fun replacePinnedTeacher(old: String, new: String) = replacePinned(KEY_PINNED_TEACHERS, old, new)

    private suspend fun replacePinned(key: Preferences.Key<String>, old: String, new: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.split("\n")?.filter(String::isNotBlank).orEmpty()
            prefs[key] = current.map { if (it == old) new else it }.distinct().joinToString("\n")
        }
    }

    suspend fun togglePinnedTeacher(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PINNED_TEACHERS]?.split("\n")?.filter(String::isNotBlank)
                .orEmpty()
            val next = if (id in current) current - id else current + id
            prefs[KEY_PINNED_TEACHERS] = next.joinToString("\n")
        }
    }

    /**
     * Кто пользуется приложением: студент или преподаватель.
     *
     * От этого зависит, чьё расписание качается и показывается везде — на
     * экране, в виджетах и в напоминаниях. Преподавателю нужны его пары, а не
     * пары какой-то группы.
     */
    val isTeacher: Flow<Boolean> = context.dataStore.data.map { it[KEY_ROLE] == "teacher" }

    suspend fun teacherMode(): Boolean = isTeacher.first()

    suspend fun setTeacherMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ROLE] = if (enabled) "teacher" else "student"
        }
    }

    /** Выбранный преподаватель — для роли преподавателя. */
    val teacherId: Flow<String?> = context.dataStore.data.map { it[KEY_TEACHER_ID] }
    val teacherName: Flow<String?> = context.dataStore.data.map { it[KEY_TEACHER_NAME] }

    suspend fun selectTeacher(id: String, name: String, unchanged: Boolean = false) {
        context.dataStore.edit {
            it.remove(KEY_GONE_SINCE)
            it.remove(KEY_GONE)
            it[KEY_TEACHER_ID] = id
            it[KEY_TEACHER_NAME] = name
            // Повторный выбор себя же — не повод стирать своё расписание без
            // связи (третий аудит, М1 прогона 2).
            if (unchanged) return@edit
            it.remove(KEY_SCHEDULE)
            it.remove(KEY_GENERATED_AT)
            it.remove(KEY_FETCHED_AT)
        }
    }

    /** Прочитано ли приветствие при первом запуске. */
    val welcomeSeen: Flow<Boolean> = context.dataStore.data.map { it[KEY_WELCOME] == "1" }

    suspend fun markWelcomeSeen() {
        context.dataStore.edit { it[KEY_WELCOME] = "1" }
    }

    /** «system», «light» или «dark». По умолчанию — как в системе. */
    val theme: Flow<String> = context.dataStore.data.map { it[KEY_THEME] ?: "system" }

    suspend fun setTheme(value: String) {
        context.dataStore.edit { it[KEY_THEME] = value }
    }

    suspend fun currentGroupId(): String? = groupId.first()

    /**
     * Всё, что нужно виджету, за одно чтение.
     *
     * Раньше он спрашивал хранилище по разу на каждое поле — четыре обращения
     * к диску на каждую перерисовку, и переключение дня заметно подтормаживало.
     */
    suspend fun widgetState(): WidgetState = widgetStates.first()

    /**
     * То же самое потоком — за ним следит сама разметка виджета.
     *
     * Разовое чтение годится только на создание сессии: код до
     * `provideContent` больше не выполняется, сколько ни зови `updateAll`.
     * Смена роли меняла хранилище, перерисовка происходила — а виджет
     * рисовал то, что прочитал при создании сессии, то есть чужую роль.
     */
    val widgetStates: Flow<WidgetState> = context.dataStore.data.map(::toWidgetState)

    private fun toWidgetState(prefs: Preferences): WidgetState {
        val teacher = prefs[KEY_ROLE] == "teacher"
        return WidgetState(
            // Виджету всё равно, чьё расписание, — он рисует то, что лежит.
            groupName = if (teacher) prefs[KEY_TEACHER_NAME] else prefs[KEY_GROUP_NAME],
            scheduleJson = prefs[KEY_SCHEDULE],
            serverBroken = (prefs[KEY_SERVER_STATUS] ?: "ok") != "ok",
            sourceUrl = prefs[KEY_SOURCE_URL],
            gone = prefs[KEY_GONE] == "1",
            fetchedAt = prefs[KEY_FETCHED_AT]?.toLongOrNull() ?: 0L,
            theme = prefs[KEY_THEME] ?: "system",
        )
    }

    /**
     * Запомнить выбранную группу.
     *
     * [unchanged] — человек выбрал ту же группу, в которой уже был, и роль при
     * этом не менялась. Тогда трогать нечего: он ничего не выбрал заново, а
     * терял при этом подгруппу, которую сам поставил. Список групп ту, что уже
     * выбрана, никак не выделяет, так что промахнуться легко.
     */
    suspend fun selectGroup(id: String, name: String, unchanged: Boolean = false) {
        context.dataStore.edit {
            val sameGroup = it[KEY_GROUP_ID] == id
            val wasGone = it[KEY_GONE] == "1"
            it[KEY_GROUP_ID] = id
            it[KEY_GROUP_NAME] = name
            it.remove(KEY_GONE_SINCE)
            it.remove(KEY_GONE)
            if (unchanged) return@edit
            // Расписание прошлой группы показывать нельзя ни секунды — и время
            // его загрузки тоже: рядом с «ещё не загружено» стояло «обновлено
            // в 14:20» прежнего выбора (третий аудит, М3 прогона 2).
            it.remove(KEY_SCHEDULE)
            it.remove(KEY_GENERATED_AT)
            it.remove(KEY_FETCHED_AT)
            // Соседняя подгруппа была парой к прежней группе, к новой она
            // отношения не имеет. Но та же группа после роли преподавателя —
            // та же пара подгрупп (М4 прогона 2), а перевыбор после «группы
            // больше нет» — обычно та же группа под новым именем, и соседку
            // молча стирать нельзя (М6): не найдётся она — скажет сама.
            if (!sameGroup && !wasGone) {
                it.remove(KEY_GROUP2_ID)
                it.remove(KEY_GROUP2_NAME)
                it.remove(KEY_GROUP2_GONE_SINCE)
                it.remove(KEY_GROUP2_GONE)
            }
        }
    }

    /**
     * Группу переименовали в таблице — сервер ответил за неё под новым id.
     *
     * Записываем новый id и имя, ничего не стирая: расписание то же, только
     * подпись другая. Иначе мы бы зависели от памяти сервера о старом имени,
     * а она не вечна.
     */
    suspend fun adoptGroup(id: String, name: String) {
        context.dataStore.edit {
            it[KEY_GROUP_ID] = id
            it[KEY_GROUP_NAME] = name
        }
    }

    suspend fun adoptSecondGroup(id: String, name: String) {
        context.dataStore.edit {
            it[KEY_GROUP2_ID] = id
            it[KEY_GROUP2_NAME] = name
        }
    }

    suspend fun adoptTeacher(id: String, name: String) {
        context.dataStore.edit {
            it[KEY_TEACHER_ID] = id
            it[KEY_TEACHER_NAME] = name
        }
    }

    /**
     * Вторая подгруппа.
     *
     * В таблице колледжа подгруппы стоят разными колонками, и общая пара
     * нередко записана только в одной из них. Кто смотрит только свою
     * колонку, такую пару пропускает — поэтому соседнюю можно добавить.
     */
    val secondGroupId: Flow<String?> = context.dataStore.data.map { it[KEY_GROUP2_ID] }
    val secondGroupName: Flow<String?> = context.dataStore.data.map { it[KEY_GROUP2_NAME] }

    suspend fun currentSecondGroupId(): String? = secondGroupId.first()

    /**
     * Соседняя подгруппа выбрана или снята (`id` = null). Свои пары остаются —
     * `ownOnly` это они, без пар прежней соседки, и снимок помечен обрубком: без
     * связи раньше стиралось всё, и экран с виджетами писали «ещё не
     * загружено» (третий аудит, М1 прогона 2). Отметки о пропаже прежней
     * соседки — прочь: иначе один 404 новой снимал её без часа проверки (М42
     * прогона 1).
     */
    suspend fun setSecondGroup(id: String?, name: String?, ownOnly: String?) {
        context.dataStore.edit {
            if (id != null && name != null) {
                it[KEY_GROUP2_ID] = id
                it[KEY_GROUP2_NAME] = name
            } else {
                it.remove(KEY_GROUP2_ID)
                it.remove(KEY_GROUP2_NAME)
            }
            it.remove(KEY_GROUP2_GONE_SINCE)
            it.remove(KEY_GROUP2_GONE)
            if (ownOnly != null) {
                it[KEY_SCHEDULE] = ownOnly
                it[KEY_PARTIAL] = "1"
            } else {
                dropSchedule(it)
            }
        }
    }

    /**
     * Забыть сохранённое расписание.
     *
     * Состав пар поменялся не в колледже, а у нас: показывать прежнее нельзя,
     * и сообщать «убрали пару» — тем более. Сравнивать будет не с чем, и
     * уведомление об изменениях промолчит.
     */
    private fun dropSchedule(prefs: MutablePreferences) {
        prefs.remove(KEY_SCHEDULE)
        prefs.remove(KEY_GENERATED_AT)
        prefs.remove(KEY_PARTIAL)
        prefs.remove(KEY_FETCHED_AT)
    }

    /**
     * Соседней подгруппы больше нет в таблице: сервер отвечает на неё 404 при
     * здоровом состоянии. Подтверждается тем же часом, что и пропажа своей
     * группы: опечатку в заголовке колледж чинит быстрее. true — подтверждено.
     */
    suspend fun noteSecondNotFound(nowMillis: Long): Boolean {
        var confirmed = false
        context.dataStore.edit {
            val first = it[KEY_GROUP2_GONE_SINCE]?.toLongOrNull()
            if (first == null) it[KEY_GROUP2_GONE_SINCE] = nowMillis.toString()
            else if (nowMillis - first >= GONE_CONFIRM_MILLIS) confirmed = true
        }
        return confirmed
    }

    /** Соседка снова отвечает: отметки о пропаже — прочь, её пары вернутся. */
    suspend fun clearSecondNotFound() {
        context.dataStore.edit {
            it.remove(KEY_GROUP2_GONE_SINCE)
            it.remove(KEY_GROUP2_GONE)
        }
    }

    /**
     * Соседки нет в таблице — подтверждено. Выбор не стирается: раньше через
     * час 404 его стирало навсегда, и после починки таблицы её пары сами не
     * возвращались (третий аудит, М41 прогона 1). true — отметка новая, о ней
     * пора сказать.
     */
    suspend fun markSecondGone(): Boolean {
        var fresh = false
        context.dataStore.edit {
            fresh = it[KEY_GROUP2_GONE] != "1"
            it[KEY_GROUP2_GONE] = "1"
        }
        return fresh
    }

    val secondGone: Flow<Boolean> = context.dataStore.data.map { it[KEY_GROUP2_GONE] == "1" }

    /** Лежит ли на телефоне расписание без пар соседней подгруппы (не дошли до неё). */
    suspend fun schedulePartial(): Boolean = context.dataStore.data.first()[KEY_PARTIAL] == "1"

    /** Дата последней перерисовки виджетов: по ней узнаём смену суток. */
    suspend fun lastWidgetDay(): String? = context.dataStore.data.first()[KEY_WIDGET_DAY]

    suspend fun setLastWidgetDay(day: String) {
        context.dataStore.edit { it[KEY_WIDGET_DAY] = day }
    }

    suspend fun putSchedule(
        body: String,
        generatedAt: String,
        partial: Boolean = false,
        windowFrom: String? = null,
    ) {
        context.dataStore.edit {
            it[KEY_SCHEDULE] = body
            it[KEY_GENERATED_AT] = generatedAt
            it[KEY_FETCHED_AT] = System.currentTimeMillis().toString()
            if (partial) it[KEY_PARTIAL] = "1" else it.remove(KEY_PARTIAL)
            if (windowFrom != null) it[KEY_WINDOW_FROM] = windowFrom else it.remove(KEY_WINDOW_FROM)
        }
    }

    /** С какого дня просили лежащее расписание (понедельник той недели). */
    suspend fun windowFrom(): String? = context.dataStore.data.first()[KEY_WINDOW_FROM]

    /** Отмечает, что расписание проверяли, даже если оно не изменилось. */
    suspend fun touchChecked() {
        context.dataStore.edit { it[KEY_FETCHED_AT] = System.currentTimeMillis().toString() }
    }

    suspend fun putGroups(body: String) {
        context.dataStore.edit { it[KEY_GROUPS] = body }
    }

    /** Список преподавателей: полторы сотни имён, качать их каждый раз незачем. */
    val teachersJson: Flow<String?> = context.dataStore.data.map { it[KEY_TEACHERS] }

    suspend fun putTeachers(body: String) {
        context.dataStore.edit { it[KEY_TEACHERS] = body }
    }

    /**
     * Сколько раз приложение ответило вместо таблицы колледжа.
     *
     * Два счётчика, а не один: приложение человек открывает сам, а
     * виджет отвечает и без него. Сложить их в одно число значило бы
     * выдать одно за другое.
     */
    suspend fun countOpen() = bump(KEY_TALLY_OPENS)

    /**
     * Показ виджета — не чаще раза в три часа: каждая техническая перерисовка
     * (звонок, полночь, часовой заход) за сутки набирала десятки «ответов
     * вместо таблицы», даже если на телефон никто не смотрел (третий аудит,
     * М38 прогона 2).
     */
    suspend fun countWidgetDraw() {
        val now = System.currentTimeMillis()
        var due = false
        context.dataStore.edit {
            val last = it[KEY_DRAW_COUNTED]?.toLongOrNull()
            due = last == null || now - last >= DRAW_COUNT_GAP_MILLIS
            if (due) it[KEY_DRAW_COUNTED] = now.toString()
        }
        if (due) bump(KEY_TALLY_DRAWS)
    }

    suspend fun tally(): Tally = context.dataStore.data.first().let {
        Tally(
            opens = it[KEY_TALLY_OPENS]?.toLongOrNull() ?: 0L,
            draws = it[KEY_TALLY_DRAWS]?.toLongOrNull() ?: 0L,
            since = it[KEY_TALLY_SINCE]?.toLongOrNull() ?: 0L,
        )
    }

    private suspend fun bump(key: Preferences.Key<String>) {
        context.dataStore.edit { prefs ->
            prefs[key] = ((prefs[key]?.toLongOrNull() ?: 0L) + 1).toString()
            // Дату первого счёта запоминаем один раз: без неё число
            // ни о чём не говорит — за неделю оно значит одно, за год другое.
            if (prefs[KEY_TALLY_SINCE] == null) {
                prefs[KEY_TALLY_SINCE] = System.currentTimeMillis().toString()
            }
        }
    }

    /** Счёт ответов: открытия приложения, перерисовки виджетов и с какого дня. */
    data class Tally(val opens: Long, val draws: Long, val since: Long)

    data class WidgetState(
        val groupName: String?,
        val scheduleJson: String?,
        /** Сервер сам признал, что расписание у него не обновилось. */
        val serverBroken: Boolean = false,
        /** Адрес таблицы колледжа: куда уйти, когда дня у нас нет. */
        val sourceUrl: String? = null,
        /** Группы в таблице больше нет — пора выбрать заново. */
        val gone: Boolean = false,
        val fetchedAt: Long,
        val theme: String,
    )

    private companion object {
        val KEY_GROUP_ID = stringPreferencesKey("group_id")
        val KEY_SERVER_SINCE = stringPreferencesKey("server_since")
        val KEY_STALE_NOTIFIED = stringPreferencesKey("stale_notified")
        val KEY_UNREACHABLE_SINCE = stringPreferencesKey("unreachable_since")
        val KEY_GONE_SINCE = stringPreferencesKey("gone_since")
        val KEY_GONE = stringPreferencesKey("gone")
        const val GONE_CONFIRM_MILLIS = 60L * 60 * 1000
        val KEY_PARTIAL = stringPreferencesKey("schedule_partial")
        val KEY_WINDOW_FROM = stringPreferencesKey("window_from")
        val KEY_GROUP2_GONE_SINCE = stringPreferencesKey("group2_gone_since")
        val KEY_GROUP2_GONE = stringPreferencesKey("group2_gone")
        val KEY_UNREACHABLE_LAST = stringPreferencesKey("unreachable_last")
        val KEY_DRAW_COUNTED = stringPreferencesKey("draw_counted")
        /** Неудачи, разделённые таким перерывом, — не одна беда. */
        val UNREACHABLE_STREAK_GAP: java.time.Duration = java.time.Duration.ofHours(1)
        /** Счёт показов виджета — не чаще раза в столько. */
        const val DRAW_COUNT_GAP_MILLIS = 3L * 60 * 60 * 1000
        val KEY_GROUP_NAME = stringPreferencesKey("group_name")
        val KEY_SCHEDULE = stringPreferencesKey("schedule_json")
        val KEY_GROUPS = stringPreferencesKey("groups_json")
        val KEY_TEACHERS = stringPreferencesKey("teachers_json")
        val KEY_FETCHED_AT = stringPreferencesKey("fetched_at")
        val KEY_GENERATED_AT = stringPreferencesKey("generated_at")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_WIDGET_DAY = stringPreferencesKey("widget_day")
        val KEY_WELCOME = stringPreferencesKey("welcome_seen")
        val KEY_GROUP2_ID = stringPreferencesKey("group2_id")
        val KEY_GROUP2_NAME = stringPreferencesKey("group2_name")
        val KEY_NOTIFY_UPDATES = stringPreferencesKey("notify_updates")
        val KEY_UPDATE_ANNOUNCED = stringPreferencesKey("update_announced")
        val KEY_NOTIFY_BEFORE = stringPreferencesKey("notify_before")
        val KEY_NOTIFY_ON = stringPreferencesKey("notify_on")
        val KEY_NOTIFY_CHANGES = stringPreferencesKey("notify_changes")
        val KEY_PINNED_TEACHERS = stringPreferencesKey("pinned_teachers")
        val KEY_PINNED_GROUPS = stringPreferencesKey("pinned_groups")
        val KEY_ROLE = stringPreferencesKey("role")
        val KEY_TEACHER_ID = stringPreferencesKey("teacher_id")
        val KEY_TEACHER_NAME = stringPreferencesKey("teacher_name")
        val KEY_TALLY_OPENS = stringPreferencesKey("tally_opens")
        val KEY_TALLY_DRAWS = stringPreferencesKey("tally_draws")
        val KEY_TALLY_SINCE = stringPreferencesKey("tally_since")
        val KEY_SERVER_STATUS = stringPreferencesKey("server_status")
        val KEY_SOURCE_URL = stringPreferencesKey("source_url")
    }
}
