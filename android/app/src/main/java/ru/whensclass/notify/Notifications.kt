package ru.whensclass.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.whensclass.R
import ru.whensclass.ui.MainActivity

/**
 * Уведомления приложения.
 *
 * Их три вида, и намеренно в разных каналах: напоминание о паре человек хочет
 * слышать, сообщение об изменении расписания — скорее видеть, а о новой сборке
 * достаточно узнать молча. Разделение даёт отключить одно, не трогая другое,
 * прямо в системных настройках.
 */
object Notifications {

    const val CHANNEL_LESSON = "lesson_soon"
    const val CHANNEL_CHANGES = "schedule_changes"
    const val CHANNEL_UPDATE = "app_update"

    private const val ID_LESSON = 1
    private const val ID_CHANGES = 2
    private const val ID_UPDATE = 3
    // Своё для «сервер лежит»: с общим ID_CHANGES новое уведомление об
    // изменениях перезаписывало непрочитанное о сбое и наоборот (второй
    // аудит, М12).
    private const val ID_SERVER = 4
    // Своё для «подгруппы нет в таблице»: с общим ID_CHANGES оно затирало
    // непрочитанное «Расписание изменилось» (третий аудит, М15 прогона 1).
    private const val ID_SUBGROUP = 5

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LESSON,
                "Скоро пара",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Напоминание перед началом занятия" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CHANGES,
                "Изменения в расписании",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Отмены, переносы и новые пары" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATE,
                "Новые версии",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Вышла новая сборка приложения" },
        )
    }

    /**
     * Разрешено ли показывать уведомления.
     *
     * С Android 13 есть отдельное разрешение, которое спрашивают. Но
     * выключить уведомления руками в настройках телефона можно было
     * всегда, и раньше на Android 8–12 мы отвечали «разрешено» не глядя:
     * приложение уверяло, что напомнит о паре, а система молча гасила
     * каждое уведомление.
     */
    fun allowed(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun lessonSoon(context: Context, title: String, text: String, day: String?) {
        show(context, CHANNEL_LESSON, ID_LESSON, title, text, day)
    }

    /**
     * `until` — когда снять само: новость про сегодняшний день к полуночи
     * устаревает, и наутро висящее «Завтра: добавилась пара» читалось бы как
     * новость о послезавтра (третий аудит, М71 прогона 2).
     */
    fun changes(context: Context, title: String, text: String, until: Long? = null) {
        show(context, CHANNEL_CHANGES, ID_CHANGES, title, text, day = null, until = until)
    }

    fun subgroupGone(context: Context, title: String, text: String) {
        show(context, CHANNEL_CHANGES, ID_SUBGROUP, title, text, day = null)
    }

    /**
     * Сервер лежит дольше двух часов — канал тот же, что у изменений, id свой.
     * true — показано: засчитывать сбой объявленным можно только тогда, иначе
     * после выдачи разрешения посреди сбоя уведомление не приходило никогда
     * (третий аудит, М32 прогона 1).
     */
    fun serverDown(context: Context, title: String, text: String): Boolean =
        show(context, CHANNEL_CHANGES, ID_SERVER, title, text, day = null) && !channelOff(context, CHANNEL_CHANGES)

    /**
     * Сервер починился — снять «не обновляется»: оно висело в шторке рядом со
     * свежими данными сколько угодно (М8 прогона 2).
     */
    fun serverBack(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_SERVER) }
    }

    /** Висит ли непрочитанное «Расписание изменилось». */
    fun changesShown(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications?.any { it.id == ID_CHANGES } == true
    }.getOrDefault(false)

    /** Канал выключен в настройках телефона: notify() туда система молча отбрасывает. */
    fun channelOff(context: Context, channel: String): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    /**
     * Нажатие открывает настройки на кнопке установки — идти искать не надо.
     * true — уведомление ушло в систему; false — уведомления запрещены.
     */
    fun newVersion(context: Context, title: String, text: String): Boolean =
        show(context, CHANNEL_UPDATE, ID_UPDATE, title, text, day = null, update = true)

    private fun show(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        day: String?,
        update: Boolean = false,
        until: Long? = null,
    ): Boolean {
        if (!allowed(context)) return false
        ensureChannels(context)

        val intent = Intent(context, MainActivity::class.java)
            // SINGLE_TOP — живой экран получает onNewIntent, а не
            // пересоздаётся (третий аудит, М32 прогона 2).
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .putExtra(MainActivity.EXTRA_DAY, day)
            .putExtra(MainActivity.EXTRA_UPDATE, update)
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, channel)
        until?.let { builder.setTimeoutAfter((it - System.currentTimeMillis()).coerceAtLeast(60_000L)) }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        return runCatching { NotificationManagerCompat.from(context).notify(id, notification) }.isSuccess
    }
}
