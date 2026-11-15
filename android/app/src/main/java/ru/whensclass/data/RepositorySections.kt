package ru.whensclass.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// ScheduleRepository: разделы рядом с расписанием — кабинеты, пересдачи, чужое и 426.

internal suspend fun ScheduleRepository.markUnsupported(sourceUrl: String? = null) {
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

internal suspend fun <T> ScheduleRepository.loadList(load: () -> T): T? {
    val answer = runCatching(load)
    if ((answer.exceptionOrNull() as? HttpFailure)?.code == 426) markUnsupported()
    return answer.getOrNull()
}

/** Кабинеты дня: ответ, или «дня в таблице нет» (404), или сбой — оба пусты. */
internal suspend fun ScheduleRepository.rooms(date: String?): RoomsAnswer = withContext(Dispatchers.IO) {
    val answer = runCatching { api.rooms(date) }
    val code = (answer.exceptionOrNull() as? HttpFailure)?.code
    if (code == 426) markUnsupported()
    RoomsAnswer(answer.getOrNull(), missing = code == 404, answered = code != null)
}

/** Лист пересдач; body null — не пришёл: `answered` — сервер ответил ошибкой, иначе нет связи. */
internal suspend fun ScheduleRepository.retakes(): RetakesAnswer = withContext(Dispatchers.IO) {
    val answer = runCatching { api.retakes() }
    val code = (answer.exceptionOrNull() as? HttpFailure)?.code
    if (code == 426) markUnsupported()
    RetakesAnswer(answer.getOrNull(), answered = code != null)
}

/** Расписание преподавателя — берём по запросу, на телефоне не храним. */
internal suspend fun ScheduleRepository.teacherSchedule(teacherId: String): ScheduleAnswer = withContext(Dispatchers.IO) {
    loadOpened { api.teacher(teacherId, from = weekStart(), days = DAYS) }
}

/** Открытое расписание и код ошибки, если сервер ответил ею: «проверьте интернет» — только без ответа. */
internal suspend fun ScheduleRepository.loadOpened(load: () -> ScheduleDto): ScheduleAnswer {
    val answer = runCatching(load)
    val code = (answer.exceptionOrNull() as? HttpFailure)?.code
    if (code == 426) markUnsupported()
    return ScheduleAnswer(answer.getOrNull(), code)
}

/** Расписание группы — для просмотра чужого, без смены роли. */
internal suspend fun ScheduleRepository.groupSchedule(groupId: String): ScheduleAnswer = withContext(Dispatchers.IO) {
    loadOpened { api.schedule(groupId, from = weekStart(), days = DAYS) }
}
