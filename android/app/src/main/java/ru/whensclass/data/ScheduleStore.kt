package ru.whensclass.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.whensclass.BuildConfig

// Испорченный файл настроек — начать с пустого: без обработчика каждый старт
// падал на CorruptionException, и помогала только «Очистить данные».
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    "whensclass",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        androidx.datastore.preferences.core.emptyPreferences()
    },
)

/** За сколько минут напоминать о паре, пока человек не выбрал своё время. */
const val DEFAULT_NOTIFY_BEFORE = 20

/**
 * Выключатель, отделённый от «изменений»: пока человек его не трогал, он
 * повторяет «изменения» — прежние сборки держали сбой сервера и пропажу
 * подгруппы под ними, и кто гасил изменения, гасил и их.
 */
internal fun notifyFlag(own: String?, changes: String?): Boolean =
    own?.let { it != "0" } ?: (changes != "0")

/**
 * Всё, что приложение помнит между запусками: выбранная группа и последний
 * ответ сервера.
 *
 * Виджет рисуется только отсюда и никогда из сети, поэтому одинаково работает
 * в метро и при выключенном сервере.
 */
class ScheduleStore(internal val context: Context) {
    internal val json = Json { ignoreUnknownKeys = true }

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
     * Хранится, потому что без сети спросить некого; может устареть — сервер
     * мог починиться, пока телефон был вне сети.
     */
    val serverStatus: Flow<String> = context.dataStore.data.map { it[KEY_SERVER_STATUS] ?: "ok" }

    /** Адрес таблицы колледжа, как его назвал сервер. */
    val sourceUrl: Flow<String?> = context.dataStore.data.map { it[KEY_SOURCE_URL] }

    /** С какого момента сервер лежит (ISO, UTC); null, когда всё в порядке. */
    val serverSince: Flow<String?> = context.dataStore.data.map { it[KEY_SERVER_SINCE] }

    /**
     * Сервер отказал прежней сборке (426), а приложение с тех пор обновили:
     * отказ к нынешней не относится, и до первого ответа сервера на экране и в
     * виджетах не должно висеть «обновите приложение».
     */
    suspend fun forgetUnsupportedAfterUpdate() {
        context.dataStore.edit {
            if (it[KEY_SERVER_STATUS] != STATUS_UNSUPPORTED) return@edit
            if (it[KEY_UNSUPPORTED_BUILD] == BuildConfig.VERSION_CODE.toString()) return@edit
            it[KEY_SERVER_STATUS] = "ok"
            it.remove(KEY_SERVER_SINCE)
            it.remove(KEY_UNSUPPORTED_BUILD)
        }
    }

    data class Unreachable(val since: java.time.Instant, val run: java.time.Instant)

    /**
     * Группы (или преподавателя) в таблице больше нет: сервер ответил 404 при
     * здоровом состоянии.
     *
     * Верим не первому ответу: опечатку в заголовке колледж чинит минут за
     * двадцать. 404 должен повториться не раньше чем через час после первого.
     */
    val gone: Flow<Boolean> = context.dataStore.data.map { it[KEY_GONE] == "1" }

    /** Сервер ответил 404 на свою группу, но час ещё не прошёл ([noteNotFound]). */
    val notFoundPending: Flow<Boolean> = context.dataStore.data.map { it[KEY_GONE_SINCE] != null }

    /**
     * За сколько минут напоминать о паре; общее для студента и преподавателя.
     * Выключатель — отдельное поле, чтобы выбранное время переживало
     * выключение.
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
     * Сообщать ли о новой версии приложения. Магазина нет: если не сказать,
     * человек так и останется со старой сборкой.
     */
    val notifyUpdates: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_NOTIFY_UPDATES] != "0"
    }

    /** Сообщать ли об изменениях в расписании. */
    val notifyChanges: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_NOTIFY_CHANGES] != "0"
    }

    /** Сообщать ли о сбое сервера — своим выключателем, а не «изменениями». */
    val notifyServer: Flow<Boolean> = context.dataStore.data.map {
        notifyFlag(it[KEY_NOTIFY_SERVER], it[KEY_NOTIFY_CHANGES])
    }

    /** Сообщать ли о пропаже из таблицы одной из остальных выбранных групп. */
    val notifyGroupsGone: Flow<Boolean> = context.dataStore.data.map {
        notifyFlag(it[KEY_NOTIFY_SUBGROUP], it[KEY_NOTIFY_CHANGES])
    }

    /**
     * Закреплённые преподаватели — вверху списка: в полутора сотнях фамилий
     * обычно смотрят одних и тех же.
     */
    val pinnedTeachers: Flow<List<String>> = context.dataStore.data.map {
        it[KEY_PINNED_TEACHERS]?.split("\n")?.filter(String::isNotBlank).orEmpty()
    }

    /** Закреплённые группы — для тех, кто смотрит чужие расписания. */
    val pinnedGroups: Flow<List<String>> = context.dataStore.data.map {
        it[KEY_PINNED_GROUPS]?.split("\n")?.filter(String::isNotBlank).orEmpty()
    }

    /**
     * Кто пользуется приложением: студент или преподаватель. От этого зависит,
     * чьё расписание качается и показывается на экране, в виджетах и в
     * напоминаниях.
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
            it[KEY_TEACHER_ID] = id
            it[KEY_TEACHER_NAME] = name
            // Повторный выбор себя же — не повод стирать расписание без связи
            // и не повод снимать пропажу: снимок прежний.
            if (unchanged) return@edit
            it.remove(KEY_GONE_SINCE)
            it.remove(KEY_GONE)
            it.remove(KEY_SCHEDULE)
            it.remove(KEY_GENERATED_AT)
            it.remove(KEY_FETCHED_AT)
            it.remove(KEY_PENDING_CHANGES)
            it.remove(KEY_CHANGES_HELD)
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

    /** «Стекло» (beta): красный свет за экраном тёмной темы, матовая полоска дней. По умолчанию выключено. */
    val glow: Flow<Boolean> = context.dataStore.data.map { it[KEY_GLOW] == "1" }

    suspend fun setGlow(on: Boolean) {
        context.dataStore.edit { it[KEY_GLOW] = if (on) "1" else "0" }
    }

    suspend fun currentGroupId(): String? = groupId.first()

    /**
     * Всё, что нужно виджету, за одно чтение: по чтению на поле переключение
     * дня заметно тормозило.
     */
    suspend fun widgetState(): WidgetState = widgetStates.first()

    /**
     * То же самое потоком — за ним следит сама разметка виджета.
     *
     * Разовое чтение годится только на создание сессии: код до
     * `provideContent` больше не выполняется, сколько ни зови `updateAll`, и
     * после смены роли виджет рисовал бы прежнюю.
     */
    val widgetStates: Flow<WidgetState> = context.dataStore.data.map(::toWidgetState)

    private fun toWidgetState(prefs: Preferences): WidgetState {
        val teacher = prefs[KEY_ROLE] == "teacher"
        return WidgetState(
            // Виджету всё равно, чьё расписание, — он рисует то, что лежит.
            groupName = if (teacher) prefs[KEY_TEACHER_NAME] else prefs[KEY_GROUP_NAME],
            scheduleJson = prefs[KEY_SCHEDULE],
            serverBroken = (prefs[KEY_SERVER_STATUS] ?: "ok") != "ok",
            unsupported = prefs[KEY_SERVER_STATUS] == STATUS_UNSUPPORTED,
            editing = prefs[KEY_SERVER_STATUS] == STATUS_EDITING,
            sourceUrl = prefs[KEY_SOURCE_URL],
            gone = prefs[KEY_GONE] == "1",
            fetchedAt = prefs[KEY_FETCHED_AT]?.toLongOrNull() ?: 0L,
            theme = prefs[KEY_THEME] ?: "system",
        )
    }

    /**
     * Запомнить выбранную группу.
     *
     * [unchanged] — человек выбрал ту же группу при той же роли. Тогда трогать
     * нечего: список уже выбранную не выделяет, промахнуться легко, а стирание
     * унесло бы и выставленную подгруппу.
     */
    suspend fun selectGroup(id: String, name: String, unchanged: Boolean = false) {
        context.dataStore.edit {
            it[KEY_GROUP_ID] = id
            it[KEY_GROUP_NAME] = name
            // Та же группа выбрана заново — пропажа не снята: снимок прежний,
            // плашку и снятые напоминания решит обновление.
            if (unchanged) return@edit
            it.remove(KEY_GONE_SINCE)
            it.remove(KEY_GONE)
            // Расписание прошлой группы и время его загрузки — прочь сразу:
            // иначе рядом с «ещё не загружено» стояло бы «обновлено в …»
            // прежнего выбора.
            it.remove(KEY_SCHEDULE)
            it.remove(KEY_GENERATED_AT)
            it.remove(KEY_FETCHED_AT)
            // Непрочитанные изменения — про прежнюю группу: к новым не копить.
            it.remove(KEY_PENDING_CHANGES)
            it.remove(KEY_CHANGES_HELD)
            // Остальные выбранные группы к своей не привязаны и остаются —
            // кроме новой своей: дважды одну группу не показываем.
            val extras = readExtras(it)
            if (extras.any { extra -> extra.id == id }) writeExtras(it, extras.filter { extra -> extra.id != id })
        }
    }

    /**
     * Группу переименовали в таблице — сервер ответил за неё под новым id.
     * Записываем новый id и имя, ничего не стирая: память сервера о старом
     * имени не вечна.
     */
    suspend fun adoptGroup(id: String, name: String) {
        context.dataStore.edit {
            it[KEY_GROUP_ID] = id
            it[KEY_GROUP_NAME] = name
        }
    }

    suspend fun adoptTeacher(id: String, name: String) {
        context.dataStore.edit {
            it[KEY_TEACHER_ID] = id
            it[KEY_TEACHER_NAME] = name
        }
    }

    /** Подписи выбранных групп у пар: названиями (так по умолчанию) или номерами. */
    val groupsByName: Flow<Boolean> = context.dataStore.data.map { it[KEY_GROUP_LABELS] != "numbers" }

    suspend fun setGroupsByName(byName: Boolean) {
        context.dataStore.edit { it[KEY_GROUP_LABELS] = if (byName) "names" else "numbers" }
    }

    /** Плашку о канале новостей уже видели (открыли канал или закрыли её). */
    val channelSeen: Flow<Boolean> = context.dataStore.data.map { it[KEY_CHANNEL_SEEN] == "1" }

    suspend fun setChannelSeen() {
        context.dataStore.edit { it[KEY_CHANNEL_SEEN] = "1" }
    }

    /**
     * Остальные выбранные группы по порядку выбора — их пары видны на экране
     * рядом со своими ([combineGroups]). Своя группа сюда не входит.
     */
    val extraGroups: Flow<List<ExtraGroup>> = context.dataStore.data.map { readExtras(it) }

    suspend fun currentExtraGroups(): List<ExtraGroup> = extraGroups.first()

    /**
     * Их расписания: объект «id → расписание», как его записал репозиторий.
     * Разбирает репозиторий; хранилищу нужно только выбросить лишние.
     */
    val extraSchedulesJson: Flow<String?> = context.dataStore.data.map { it[KEY_EXTRA_SCHEDULES] }

    /**
     * Перевод со «соседней подгруппы» сборок до 0.1.4 на список групп.
     *
     * Снимок там лежал склеенным с парами соседки, а по нему работают
     * виджеты, напоминания и уведомления — только о своей группе. Оставляем
     * свои пары; пары соседки придут следующим обновлением своим снимком.
     * Повторный вызов ничего не делает.
     */
    suspend fun migrateGroups() {
        context.dataStore.edit { prefs ->
            val id = prefs[KEY_GROUP2_ID]
            val name = prefs[KEY_GROUP2_NAME]
            if (id == null && prefs[KEY_PARTIAL] == null) return@edit
            // Старая версия давала выбрать соседкой свою же группу — такую не
            // переносим.
            if (id != null && name != null && prefs[KEY_EXTRA_GROUPS] == null && id != prefs[KEY_GROUP_ID]) {
                val gone = prefs[KEY_GROUP2_GONE] == "1"
                val since = prefs[KEY_GROUP2_GONE_SINCE]?.toLongOrNull()
                prefs[KEY_EXTRA_GROUPS] = json.encodeToString(listOf(ExtraGroup(id, name, gone, since)))
            }
            prefs[KEY_SCHEDULE]?.let { body ->
                runCatching { json.decodeFromString<ScheduleDto>(body) }.getOrNull()?.let { saved ->
                    prefs[KEY_SCHEDULE] = json.encodeToString(saved.ownOnly())
                }
            }
            prefs.remove(KEY_GROUP2_ID)
            prefs.remove(KEY_GROUP2_NAME)
            prefs.remove(KEY_GROUP2_GONE)
            prefs.remove(KEY_GROUP2_GONE_SINCE)
            prefs.remove(KEY_PARTIAL)
        }
    }

    /**
     * Расписание, сохранённое прежней версией без нынешних полей (пометки
     * непрочитанного дня), перезапрашивается один раз: иначе до правки
     * таблицы gen тот же, и пометок не было бы.
     */
    suspend fun migrateFormat() {
        context.dataStore.edit { prefs ->
            if (prefs[KEY_FORMAT] == FORMAT) return@edit
            prefs.remove(KEY_GENERATED_AT)
            prefs[KEY_FORMAT] = FORMAT
        }
    }

    /** Дата последней перерисовки виджетов: по ней узнаём смену суток. */
    suspend fun lastWidgetDay(): String? = context.dataStore.data.first()[KEY_WIDGET_DAY]

    suspend fun setLastWidgetDay(day: String) {
        context.dataStore.edit { it[KEY_WIDGET_DAY] = day }
    }

    suspend fun putSchedule(
        body: String,
        generatedAt: String,
        windowFrom: String? = null,
    ) {
        context.dataStore.edit {
            it[KEY_SCHEDULE] = body
            it[KEY_GENERATED_AT] = generatedAt
            it[KEY_FETCHED_AT] = System.currentTimeMillis().toString()
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

    /**
     * Когда списки групп и преподавателей пришли свежими целиком: качать их на
     * каждый экран и каждое ⟳ незачем.
     */
    suspend fun listsFetchedAt(): Long =
        context.dataStore.data.first()[KEY_LISTS_AT]?.toLongOrNull() ?: 0L

    suspend fun setListsFetchedAt(millis: Long) {
        context.dataStore.edit { it[KEY_LISTS_AT] = millis.toString() }
    }

    /** Когда фон последний раз спрашивал GitHub о выпусках ([AppUpdate.announceIfNew]). */
    suspend fun githubCheckedAt(): Long =
        context.dataStore.data.first()[KEY_GITHUB_AT]?.toLongOrNull() ?: 0L

    suspend fun setGithubCheckedAt(millis: Long) {
        context.dataStore.edit { it[KEY_GITHUB_AT] = millis.toString() }
    }

    /** Список преподавателей: полторы сотни имён, качать их каждый раз незачем. */
    val teachersJson: Flow<String?> = context.dataStore.data.map { it[KEY_TEACHERS] }

    suspend fun putTeachers(body: String) {
        context.dataStore.edit { it[KEY_TEACHERS] = body }
    }

    /**
     * Сколько раз приложение ответило вместо таблицы колледжа: открытия и
     * возвращения к нему. Перерисовки виджетов не в счёт — смотрел ли на них
     * кто-то, не узнать.
     */
    suspend fun countOpen() = bump(KEY_TALLY_OPENS)

    suspend fun tally(): Tally = context.dataStore.data.first().let {
        Tally(
            opens = it[KEY_TALLY_OPENS]?.toLongOrNull() ?: 0L,
            since = it[KEY_TALLY_SINCE]?.toLongOrNull() ?: 0L,
        )
    }

    private suspend fun bump(key: Preferences.Key<String>) {
        context.dataStore.edit { prefs ->
            prefs[key] = ((prefs[key]?.toLongOrNull() ?: 0L) + 1).toString()
            // Дата первого счёта: без неё число ни о чём не говорит.
            if (prefs[KEY_TALLY_SINCE] == null) {
                prefs[KEY_TALLY_SINCE] = System.currentTimeMillis().toString()
            }
        }
    }

    /** Счёт ответов: открытия приложения и с какого дня. */
    data class Tally(val opens: Long, val since: Long)

    data class WidgetState(
        val groupName: String?,
        val scheduleJson: String?,
        /** Сервер сам признал, что расписание у него не обновилось. */
        val serverBroken: Boolean = false,
        /** Адрес таблицы колледжа: куда уйти, когда дня у нас нет. */
        val sourceUrl: String? = null,
        /** Группы в таблице больше нет — пора выбрать заново. */
        val gone: Boolean = false,
        /** Сервер эту сборку больше не обслуживает — нужно обновить приложение. */
        val unsupported: Boolean = false,
        /** Колледж правит таблицу ([STATUS_EDITING]): вместо «сбой» — «правка таблицы». */
        val editing: Boolean = false,
        val fetchedAt: Long,
        val theme: String,
    )
}
