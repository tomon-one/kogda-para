package ru.whensclass.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

// ScheduleRepository: списки групп и преподавателей — сохранённый и свежий.

/** Списки групп и преподавателей: сохранённый — сразу и без сети, свежий — следом, чтобы не крутить спиннер. */
internal suspend fun ScheduleRepository.cachedGroups(): List<GroupDto> = withContext(Dispatchers.IO) {
    store.groupsJson.first()
        ?.let { runCatching { json.decodeFromString<GroupsDto>(it).groups }.getOrNull() }
        .orEmpty()
}

/** Свежий список групп; null — сервер не ответил. */
internal suspend fun ScheduleRepository.freshGroups(): List<GroupDto>? = withContext(Dispatchers.IO) {
    val fresh = loadList { api.groups() } ?: return@withContext null
    store.putGroups(json.encodeToString(fresh))
    fresh.groups
}

internal suspend fun ScheduleRepository.cachedTeachers(): List<GroupDto> = withContext(Dispatchers.IO) {
    store.teachersJson.first()
        ?.let { runCatching { json.decodeFromString<TeachersDto>(it).teachers }.getOrNull() }
        .orEmpty()
}

/** Свежий список преподавателей; null — сервер не ответил. */
internal suspend fun ScheduleRepository.freshTeachers(): List<GroupDto>? = withContext(Dispatchers.IO) {
    val fresh = loadList { api.teachers() } ?: return@withContext null
    store.putTeachers(json.encodeToString(fresh))
    fresh.teachers
}

internal suspend fun ScheduleRepository.followRenamedPins(groups: List<GroupDto>, teachers: List<GroupDto>) =
    withContext(Dispatchers.IO) {
        // Группы по старому имени сервер не сводит (404): закреплённая остаётся и видна, если группа вернётся.
        val teacherIds = teachers.map { it.id }.toSet()
        for (old in store.pinnedTeachers.first().filter { it !in teacherIds }) {
            val now = runCatching { api.teacher(old, days = 1).groupId }.getOrNull() ?: continue
            if (now != old && now in teacherIds) store.replacePinnedTeacher(old, now)
        }
    }
