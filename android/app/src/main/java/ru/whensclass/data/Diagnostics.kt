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
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.WeekWidget

/**
 * Что приложение знает о себе — одним куском текста.
 *
 * «У меня не работает» само по себе неразрешимо: неизвестно ни какая стоит
 * сборка, ни что она в последний раз получила от сервера. Выспрашивать это по
 * одному в переписке долго, и человек всё равно не знает, где смотреть.
 * Пусть отвечает приложение.
 *
 * Собрано не «всё, что нашлось», а ответы на вопросы, которые иначе пришлось
 * бы задавать: какая сборка, чей это сбой — наш или телефона, стоит ли виджет
 * вообще и что мешает напоминанию прийти.
 *
 * Здесь есть модель телефона, версия Android, часовой пояс и — у
 * преподавателя — его имя из таблицы: без них жалобу не разобрать. Нет
 * опознавателя телефона и ничего, что связало бы обращения к серверу с
 * человеком. Окно отчёта говорит об этом прямо, а текст человек видит до
 * отправки.
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
        // Две даты, а не одна: расходятся они по-разному. Свежая правка при
        // старой проверке — телефон перестал ходить за расписанием; старые
        // обе — расписание встало у нас. Слова те же, что на плашке сбоя: раньше
        // «получено» здесь и там стояло у разных дат (третий аудит, М74
        // прогона 2).
        lines += "Расписание: телефон проверял ${stamp(store.fetchedAt.first(), zone)}, " +
            "последняя правка таблицы у сервера ${stamp(schedule.generatedAt, zone)}"
        schedule.sheet?.let { lines += "Лист: $it" }
        if (schedule.coverage.size == 2) {
            lines += "Дни листа: ${schedule.coverage[0]} — ${schedule.coverage[1]}"
        }
        lines += "Дней на телефоне: ${schedule.days.size}"
    }
    lines += "Сервер: ${store.serverStatus.first()}"
    lines += widgets(context)
    lines += reminders(context, store)
    lines += "Телефон: ${LocalDateTime.now().format(STAMP)}, $zone"

    return lines.joinToString("\n")
}

/**
 * Сколько наших виджетов стоит на экране.
 *
 * «Виджет не обновляется» — самая вероятная жалоба, и первый вопрос по ней:
 * а виджет вообще добавлен? Спрашивать это словами неловко, а ошибиться легко:
 * приложение и виджет — разные вещи, и не всем это очевидно.
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
 * Почему могло не прийти напоминание — тремя условиями сразу.
 *
 * Условий три, и человек обычно не знает, какое у него не выполнено:
 * напоминания выключены в приложении, уведомления запрещены системой или
 * не выдано разрешение будить телефон в точное время.
 */
private suspend fun reminders(context: Context, store: ScheduleStore): String {
    if (!store.notifyEnabled.first()) return "Напоминания: выключены"
    val allowed = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(false)
    return "Напоминания: за ${store.notifyBeforeMinutes()} мин, " +
        "уведомления ${if (allowed) "разрешены" else "запрещены"}, " +
        "точные будильники ${if (LessonAlarms.exactAllowed(context)) "да" else "нет"}"
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
