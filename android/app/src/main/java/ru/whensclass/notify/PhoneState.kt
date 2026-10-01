package ru.whensclass.notify

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import java.time.Instant
import java.time.ZoneId
import ru.whensclass.widget.COLLEGE_ZONE

/**
 * Что телефон делает с приложением помимо его настроек: выключенный канал
 * уведомлений, ограниченная фоновая работа, экономия трафика, неверный
 * часовой пояс молча гасят напоминания, часовое обновление и уведомления об
 * отменах. Читается при каждом возвращении в приложение.
 */
data class PhoneState(
    /** Канал «Скоро пара» выключен в настройках телефона. */
    val lessonChannelOff: Boolean = false,
    /** Канал «Изменения в расписании» выключен. */
    val changesChannelOff: Boolean = false,
    /** Канал «Сбои сервера» выключен. */
    val serverChannelOff: Boolean = false,
    /** Канал «Другие группы» выключен. */
    val subgroupChannelOff: Boolean = false,
    /** Канал «Новые версии» выключен. */
    val updateChannelOff: Boolean = false,
    /** Почему фоновая работа может не идти — словами; пусто — не видно причин. */
    val backgroundLimits: List<String> = emptyList(),
    /** Пояс телефона не совпадает с поясом колледжа при выключенном автоопределении. */
    val zoneWarning: String? = null,
    /** Разрешено ли «Когда пара?» ставить обновление самой себе. */
    val canInstall: Boolean = true,
    /** Система не экономит на приложении батарею. */
    val unrestricted: Boolean = true,
) {
    companion object {
        fun read(context: Context): PhoneState = PhoneState(
            lessonChannelOff = Notifications.channelOff(context, Notifications.CHANNEL_LESSON),
            changesChannelOff = Notifications.channelOff(context, Notifications.CHANNEL_CHANGES),
            serverChannelOff = Notifications.channelOff(context, Notifications.CHANNEL_SERVER),
            subgroupChannelOff = Notifications.channelOff(context, Notifications.CHANNEL_SUBGROUP),
            updateChannelOff = Notifications.channelOff(context, Notifications.CHANNEL_UPDATE),
            backgroundLimits = backgroundLimits(context),
            zoneWarning = zoneWarning(context),
            canInstall = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true),
            unrestricted = Background.unrestricted(context),
        )

        private fun backgroundLimits(context: Context): List<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val activity = context.getSystemService(ActivityManager::class.java)
                if (runCatching { activity?.isBackgroundRestricted }.getOrNull() == true) {
                    add("фоновая работа ограничена в настройках батареи")
                }
                // Группу «редко» (appStandbyBucket) не смотрим: читается это,
                // когда приложение открыто, а открытое всегда в «активных».
            }
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val saver = runCatching { connectivity?.restrictBackgroundStatus }.getOrNull()
            if (saver == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                add("включена экономия трафика: в фоне приложение сети не получает")
            }
        }

        /**
         * Пояс телефона не колледжа, и определяется он не сам. Время колледжа
         * считается от абсолютного времени телефона: часы, выставленные
         * вручную «по-новосибирски» при поясе «Москва», сдвигают всё на
         * разницу поясов. Сами часы проверить не по чему — только предупреждаем.
         */
        private fun zoneWarning(context: Context): String? {
            val auto = runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1) == 1
            }.getOrDefault(true)
            return zoneWarning(auto, ZoneId.systemDefault(), Instant.now())
        }

        internal fun zoneWarning(autoZone: Boolean, phone: ZoneId, now: Instant): String? {
            if (autoZone) return null
            if (phone.rules.getOffset(now) == COLLEGE_ZONE.rules.getOffset(now)) return null
            return "Выставлен пояс (${phone.id}). Расписание адаптировано под Новосибирск: " +
                "если часы переведены под него вручную, напоминания и отображение " +
                "текущей пары будут некорректными."
        }
    }
}
