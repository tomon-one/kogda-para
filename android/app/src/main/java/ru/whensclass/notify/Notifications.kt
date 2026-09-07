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
 * Их два вида, и намеренно в разных каналах: напоминание о паре человек хочет
 * слышать, а сообщение об изменении расписания — скорее видеть. Разделение
 * даёт отключить одно, не трогая другое, прямо в системных настройках.
 */
object Notifications {

    const val CHANNEL_LESSON = "lesson_soon"
    const val CHANNEL_CHANGES = "schedule_changes"

    private const val ID_LESSON = 1
    private const val ID_CHANGES = 2

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
    }

    /** Разрешено ли показывать уведомления. С Android 13 их надо спрашивать. */
    fun allowed(context: Context): Boolean {
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

    private fun show(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        day: String?,
    ) {
        if (!allowed(context)) return
        ensureChannels(context)

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_DAY, day)
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
