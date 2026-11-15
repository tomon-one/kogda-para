package ru.whensclass.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import ru.whensclass.notify.LessonAlarms

// ScheduleRepository: выбор группы или себя, остальные группы и что после выбора.

/** Студент выбрал группу — заодно это и означает, что он студент. */
internal suspend fun ScheduleRepository.selectGroup(group: GroupDto): RefreshResult {
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
internal suspend fun ScheduleRepository.selectSelfAsTeacher(teacher: GroupDto): RefreshResult {
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

internal suspend fun ScheduleRepository.addExtraGroups(groups: List<GroupDto>): RefreshResult? {
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
internal suspend fun ScheduleRepository.removeExtraGroup(id: String) {
    store.editExtras { list -> list.filter { it.id != id } }
}

internal suspend fun ScheduleRepository.followChoice() {
    updateWidgets()
    LessonAlarms.reschedule(context)
}

internal suspend fun ScheduleRepository.afterSelection(): RefreshResult {
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
