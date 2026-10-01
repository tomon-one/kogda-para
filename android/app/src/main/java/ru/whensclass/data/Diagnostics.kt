package ru.whensclass.data

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import ru.whensclass.BuildConfig
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.WeekWidget

/**
 * Что приложение знает о себе — одним куском текста для отчёта об ошибке:
 * ответы на вопросы, которые иначе пришлось бы задавать в переписке. Какая
 * сборка, чей это сбой — сервера или телефона, стоит ли виджет вообще и что
 * мешает напоминанию прийти.
 *
 * Здесь есть модель телефона, версия Android, часовой пояс и — у
 * преподавателя — его имя из таблицы: без них жалобу не разобрать. Нет
 * опознавателя телефона и ничего, что связало бы обращения к серверу с
 * человеком. Текст человек видит до отправки.
 */
internal suspend fun collectDiagnostics(
    context: Context,
    store: ScheduleStore,
    schedule: ScheduleDto?,
): String {
    val zone = ZoneId.systemDefault()
    val lines = mutableListOf(
        "Когда пара? ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    // Идентификатор рядом с именем: по нему расписание ищется на сервере.
    val teacher = store.teacherMode()
    lines += if (teacher) {
        "Преподаватель: ${store.teacherName.first() ?: "не выбран"} " +
            "[${store.teacherId.first() ?: "—"}]"
    } else {
        "Группа: ${store.groupName.first() ?: "не выбрана"} " +
            "[${store.groupId.first() ?: "—"}]"
    }
    // Только у студента: у преподавателя список от старого выбора экран прячет.
    if (!teacher) store.currentExtraGroups().takeIf { it.isNotEmpty() }?.let { extras ->
        lines += "Ещё группы: " + extras.joinToString(", ") {
            "${it.name} [${it.id}]" + if (it.gone) " — нет в таблице" else ""
        }
    }

    if (schedule == null) {
        lines += "Расписание: не загружено"
    } else {
        // Две даты: старая проверка при свежем снимке — телефон перестал
        // ходить за расписанием; старые обе — расписание встало на сервере.
        lines += "Расписание: телефон проверял ${stamp(store.fetchedAt.first(), zone)}, " +
            // gen — не обязательно правка: сервер пишет новый и после
            // перезапуска и ночного поиска листа.
            "снимок сервера от ${stamp(schedule.generatedAt, zone)}"
        schedule.sheet?.let { lines += "Лист: $it" }
        if (schedule.coverage.size == 2) {
            // cov в ответе расписания — край группы, а не листа.
            lines += "Дни группы: ${schedule.coverage[0]} — ${schedule.coverage[1]}"
        }
        lines += "Дней на телефоне: ${schedule.days.size}"
    }
    lines += "Сервер: ${store.serverStatus.first()}"
    lines += widgets(context)
    lines += reminders(context, store)
    // Выключатели сообщений — первая причина «не приходят сообщения об отменах».
    fun yes(on: Boolean) = if (on) "да" else "нет"
    lines += "Сообщать: изменения ${yes(store.notifyChangesEnabled())}, " +
        "сбои ${yes(store.notifyServerEnabled())}, версии ${yes(store.notifyUpdatesEnabled())}, " +
        "пропажа групп ${yes(store.notifyGroupsGoneEnabled())}"
    // То, что телефон делает с приложением сам.
    val phone = ru.whensclass.notify.PhoneState.read(context)
    val off = listOfNotNull(
        Notifications.CHANNEL_LESSON.takeIf { phone.lessonChannelOff },
        Notifications.CHANNEL_CHANGES.takeIf { phone.changesChannelOff },
        Notifications.CHANNEL_SERVER.takeIf { phone.serverChannelOff },
        Notifications.CHANNEL_SUBGROUP.takeIf { phone.subgroupChannelOff },
        Notifications.CHANNEL_UPDATE.takeIf { phone.updateChannelOff },
    ).map { "«${Notifications.channelName(it)}»" }
    if (off.isNotEmpty()) lines += "Каналы выключены в телефоне: ${off.joinToString(", ")}"
    lines += "Фон: " + phone.backgroundLimits.joinToString("; ").ifEmpty { "ограничений не видно" } +
        // Те же слова, что в разделе «Работа в фоне».
        ", без экономии батареи ${if (phone.unrestricted) "да" else "нет"}"
    if (phone.zoneWarning != null) lines += "Пояс: выставлен вручную и не колледжа"
    lines += "Телефон: ${LocalDateTime.now().format(STAMP)}, $zone"

    return lines.joinToString("\n")
}

/**
 * Сколько виджетов приложения стоит на экране: первый вопрос на «виджет не
 * обновляется» — а добавлен ли он вообще.
 */
private suspend fun widgets(context: Context): String = runCatching {
    val manager = GlanceAppWidgetManager(context)
    val count = listOf(
        ScheduleWidget::class.java,
        WeekWidget::class.java,
        NextLessonWidget::class.java,
    ).sumOf { manager.getGlanceIds(it).size }
    "Виджетов на экране: $count"
}.getOrDefault("Виджетов на экране: посчитать не вышло")

/**
 * Почему могло не прийти напоминание — тремя условиями сразу: выключены в
 * приложении, запрещены системой, нет разрешения на точное время.
 */
private suspend fun reminders(context: Context, store: ScheduleStore): String {
    if (!store.notifyEnabled.first()) return "Напоминания: выключены"
    val allowed = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(false)
    return "Напоминания: за ${store.notifyBeforeMinutes()} мин, " +
        "уведомления ${if (allowed) "разрешены" else "запрещены"}, " +
        // Те же слова, что на экране настроек.
        "точное время ${if (LessonAlarms.exactAllowed(context)) "да" else "нет"}"
}

/** Дата с часами, без года и секунд: отчёт читает человек, а не машина. */
private val STAMP = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun stamp(millis: Long, zone: ZoneId): String =
    if (millis <= 0L) "никогда" else Instant.ofEpochMilli(millis).atZone(zone).format(STAMP)

/** Время сервера (в ответе — UTC) — в местное. */
private fun stamp(iso: String, zone: ZoneId): String =
    runCatching { Instant.parse(iso).atZone(zone).format(STAMP) }.getOrDefault(iso)
