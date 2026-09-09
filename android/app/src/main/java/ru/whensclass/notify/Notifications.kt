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

    fun changes(context: Context, title: String, text: String) {
        show(context, CHANNEL_CHANGES, ID_CHANGES, title, text, day = null)
    }

    /** Нажатие открывает настройки на кнопке установки — идти искать не надо. */
    fun newVersion(context: Context, title: String, text: String) {
        show(context, CHANNEL_UPDATE, ID_UPDATE, title, text, day = null, update = true)
    }

    private fun show(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        day: String?,
        update: Boolean = false,
    ) {
        if (!allowed(context)) return
        ensureChannels(context)

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_DAY, day)
            .putExtra(MainActivity.EXTRA_UPDATE, update)
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
