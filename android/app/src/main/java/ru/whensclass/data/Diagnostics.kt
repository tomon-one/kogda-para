package ru.whensclass.data

import android.os.Build
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import ru.whensclass.BuildConfig

/**
 * Что приложение знает о себе — одним куском текста.
 *
 * «У меня не работает» само по себе неразрешимо: неизвестно ни какая стоит
 * сборка, ни что она в последний раз получила от сервера. Выспрашивать это по
 * одному в переписке долго, и человек всё равно не знает, где смотреть.
 * Пусть отвечает приложение.
 *
 * Здесь нет ничего, чего не видно на экранах: ни опознавателя телефона, ни
 * чего-либо, что связало бы обращения к серверу с человеком. Обещание из
 * раздела «О данных» действует и тут.
 */
internal suspend fun collectDiagnostics(store: ScheduleStore, schedule: ScheduleDto?): String {
    val zone = ZoneId.systemDefault()
    val lines = mutableListOf(
        "Когда пара? ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    // Опознаватель рядом с именем: имя человек прочтёт сам, а искать
    // расписание на сервере я буду по идентификатору.
    lines += if (store.teacherMode()) {
        "Преподаватель: ${store.teacherName.first() ?: "не выбран"} " +
            "[${store.teacherId.first() ?: "—"}]"
    } else {
        "Группа: ${store.groupName.first() ?: "не выбрана"} " +
            "[${store.groupId.first() ?: "—"}]"
    }
    store.secondGroupName.first()?.let { name ->
        lines += "Подгруппа: $name [${store.secondGroupId.first() ?: "—"}]"
    }

    if (schedule == null) {
        lines += "Расписание: не загружено"
    } else {
        lines += "Расписание: получено ${stamp(store.fetchedAt.first(), zone)}, " +
            "собрано ${stamp(schedule.generatedAt, zone)}"
        schedule.sheet?.let { lines += "Лист: $it" }
        if (schedule.coverage.size == 2) {
            lines += "Дни листа: ${schedule.coverage[0]} — ${schedule.coverage[1]}"
        }
        lines += "Дней на телефоне: ${schedule.days.size}"
    }
    lines += "Сервер: ${store.serverStatus.first()}"
    lines += "Телефон: ${LocalDateTime.now().format(STAMP)}, $zone"

    return lines.joinToString("\n")
}

/** Дата с часами, без года и секунд: отчёт читает человек, а не машина. */
private val STAMP = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun stamp(millis: Long, zone: ZoneId): String =
    if (millis <= 0L) "никогда" else Instant.ofEpochMilli(millis).atZone(zone).format(STAMP)

/**
 * Время сервера — в местное. В ответе оно в UTC, и в отчёте это выглядело бы
 * как «собрано на семь часов раньше, чем получено».
 */
private fun stamp(iso: String, zone: ZoneId): String =
    runCatching { Instant.parse(iso).atZone(zone).format(STAMP) }.getOrDefault(iso)
