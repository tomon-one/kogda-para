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
 * Уведомления приложения — каждый вид в своём канале: напоминание о паре
 * человек хочет слышать, изменения — видеть, а о новой сборке достаточно
 * узнать молча. Так любое можно отключить в системных настройках, не трогая
 * остальные.
 */
object Notifications {

    const val CHANNEL_LESSON = "lesson_soon"
    const val CHANNEL_CHANGES = "schedule_changes"
    const val CHANNEL_UPDATE = "app_update"
    // Id канала пропажи — от «соседней подгруппы» сборок до 0.1.4: новый id
    // оставил бы у людей прежний канал висеть в настройках телефона.
    const val CHANNEL_SERVER = "server_down"
    const val CHANNEL_SUBGROUP = "subgroup_gone"

    /**
     * Названия каналов, как их показывает телефон: ими же говорят настройки
     * («Уведомления «…» выключены») и отчёт.
     */
    private val NAMES = mapOf(
        CHANNEL_LESSON to "Скоро пара",
        CHANNEL_CHANGES to "Изменения в расписании",
        CHANNEL_SERVER to "Сбои сервера",
        CHANNEL_SUBGROUP to "Другие группы",
        CHANNEL_UPDATE to "Новые версии",
    )

    fun channelName(channel: String): String = NAMES[channel] ?: channel

    private const val ID_LESSON = 1
    private const val ID_CHANGES = 2
    private const val ID_UPDATE = 3
    // У каждого вида свой id: с общим новое уведомление затирает
    // непрочитанное другого вида.
    private const val ID_SERVER = 4
    private const val ID_SUBGROUP = 5

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LESSON,
                channelName(CHANNEL_LESSON),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Напоминание перед началом занятия" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CHANGES,
                channelName(CHANNEL_CHANGES),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Отмены и замены на сегодня и завтра" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVER,
                channelName(CHANNEL_SERVER),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Сервер не обновляет расписание дольше двух часов" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SUBGROUP,
                channelName(CHANNEL_SUBGROUP),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Одной из выбранных групп не стало в таблице" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATE,
                channelName(CHANNEL_UPDATE),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Вышла новая сборка приложения" },
        )
    }

    /**
     * Разрешено ли показывать уведомления: и разрешение Android 13+, и
     * выключатель в настройках телефона, который бывает на любой версии.
     */
    fun allowed(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** `until` — конец пары: к нему напоминание снимается само. */
    fun lessonSoon(
        context: Context, title: String, text: String, day: String?, until: Long? = null,
        /** Какая пара: «2026-09-29T21:47|Физика» — чтобы снять, если её отменят. */
        key: String? = null,
    ) {
        show(context, CHANNEL_LESSON, ID_LESSON, title, text, day, until = until,
            extras = key?.let { android.os.Bundle().apply { putString(EXTRA_LESSON_KEY, it) } })
    }

    /** `until` — когда снять само: новость о дне к его концу устаревает. */
    fun changes(context: Context, title: String, text: String, until: Long? = null) {
        show(context, CHANNEL_CHANGES, ID_CHANGES, title, text, day = null, until = until)
    }

    fun subgroupGone(context: Context, title: String, text: String) {
        show(context, CHANNEL_SUBGROUP, ID_SUBGROUP, title, text, day = null)
    }

    /**
     * Сервер лежит дольше двух часов. true — показано: только тогда сбой
     * засчитывается объявленным, иначе после выдачи разрешения посреди сбоя
     * уведомление не пришло бы никогда.
     */
    fun serverDown(context: Context, title: String, text: String): Boolean =
        show(context, CHANNEL_SERVER, ID_SERVER, title, text, day = null) && !channelOff(context, CHANNEL_SERVER)

    /** Сервер починился — снять «не обновляется» из шторки. */
    fun serverBack(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_SERVER) }
    }

    private const val EXTRA_LESSON_KEY = "ru.whensclass.lesson"

    /** Какую пару называет висящее напоминание ([lessonSoon], `key`). */
    fun shownLessonKey(context: Context): String? = runCatching {
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.activeNotifications?.firstOrNull { it.id == ID_LESSON }
            ?.notification?.extras?.getString(EXTRA_LESSON_KEY)
    }.getOrNull()

    /** Снять напоминание о паре: её отменили или убрали. */
    fun lessonGone(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_LESSON) }
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
        // Канал выключен — система уведомление молча выбросит, и сборка не
        // должна считаться объявленной, как и у сбоя.
        show(context, CHANNEL_UPDATE, ID_UPDATE, title, text, day = null, update = true) &&
            !channelOff(context, CHANNEL_UPDATE)

    // Разрешение проверяет allowed() в первой строке, а SecurityException, если
    // его отберут между проверкой и показом, ловит runCatching: lint видит
    // только вызов notify.
    @android.annotation.SuppressLint("MissingPermission")
    private fun show(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        day: String?,
        update: Boolean = false,
        until: Long? = null,
        extras: android.os.Bundle? = null,
    ): Boolean {
        if (!allowed(context)) return false
        ensureChannels(context)

        val intent = Intent(context, MainActivity::class.java)
            // SINGLE_TOP — живой экран получает onNewIntent, а не пересоздаётся.
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
        extras?.let { builder.addExtras(it) }
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
