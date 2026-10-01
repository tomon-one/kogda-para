package ru.whensclass.data

import kotlinx.coroutines.flow.first

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
) {
    /**
     * Только своё: роль и выбранный. Своё расписание сверяется по нему —
     * «Убрать» другую группу посреди обновления выбрасывало и своё свежее, а
     * новый заход не ставился. Другие группы
     * пишутся по нынешнему выбору ([ScheduleRepository.writeExtras]).
     */
    fun own(): Subject = copy(extras = emptyList())
}

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
): List<Pair<String, String>> {
    // Повтор строки встаёт на своё последнее место, а не остаётся на первом:
    // «вернули → отменили → вернули» читалось последней строкой «отменили».
    val lines = (pending + fresh).filter { it.first >= today.toString() }
    val kept = lines.filterIndexed { index, line -> lines.subList(index + 1, lines.size).none { it == line } }
    if (kept.size <= MAX_CHANGE_LINES) return kept
    // Свежая правка целиком важнее висящих строк; внутри неё — сначала
    // сегодня: строки идут по дням, и «последние восемь» выбрасывали
    // сегодняшнее ради завтрашнего.
    val freshKept = fresh.filter { it.first >= today.toString() }.distinct()
    if (freshKept.size >= MAX_CHANGE_LINES) return freshKept.take(MAX_CHANGE_LINES)
    val older = kept.filterNot { it in freshKept }
    // Из висящих уходят сначала строки о более далёком дне, в одном дне — более
    // старые: непрочитанное «отменили 1 пару» сегодня важнее завтрашнего.
    // Порядок оставшихся — прежний.
    val room = MAX_CHANGE_LINES - freshKept.size
    val keep = older.indices.sortedWith(compareBy({ older[it].first }, { -it })).take(room).toSet()
    return older.filterIndexed { index, _ -> index in keep } + freshKept
}

/** Больше строк шторка всё равно не покажет развёрнутой. */
internal const val MAX_CHANGE_LINES = 8

/** Что случилось при обновлении — приложению есть что показать, виджету нет. */
sealed interface RefreshResult {
    data object Updated : RefreshResult
    /**
     * Своё обновилось, а другие группы — не все: на экране их прежние пары.
     * Галочка обещала бы свежесть, которой нет.
     * [fresh] — те из [missed], чьих пар на телефоне ещё нет (только что добавлены).
     */
    data class Partial(val missed: List<String>, val fresh: Set<String> = emptySet()) : RefreshResult
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
