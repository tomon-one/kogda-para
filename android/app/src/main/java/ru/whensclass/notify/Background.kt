package ru.whensclass.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Марки, которые сверх обычного Android не дают приложению просыпаться в фоне.
 *
 * Будильники звонка, напоминания и часовое обновление на них не срабатывают,
 * пока приложение не открыть, особенно после перезагрузки или очистки
 * недавних. Снимается это только руками в настройках телефона, у каждой
 * марки на своём экране. Какие экраны и что на них — dontkillmyapp.com и
 * списки из открытых приложений, собранные 28.09.2026.
 */
enum class Vendor(
    private val defaultTitle: String,
    /** Очистка недавних выгружает всё, что не закреплено. */
    val pinInRecents: Boolean,
) {
    XIAOMI("Xiaomi", pinInRecents = true),
    HUAWEI("Huawei", pinInRecents = true),
    HONOR("Honor", pinInRecents = true),
    SAMSUNG("Samsung", pinInRecents = false),
    TRANSSION("Tecno", pinInRecents = true),
    /** OPPO, realme и OnePlus — прошивка ColorOS (у realme — realme UI на ней). */
    OPPO("OPPO", pinInRecents = true),
    VIVO("vivo", pinInRecents = true);

    /** Как марку называют люди: Redmi, а не Xiaomi. */
    fun title(brand: String? = Build.BRAND): String =
        BRAND_TITLES[brand?.lowercase()?.trim()] ?: defaultTitle

    companion object {
        private val BRAND_TITLES = mapOf(
            "redmi" to "Redmi",
            "poco" to "POCO",
            "infinix" to "Infinix",
            "itel" to "itel",
            "realme" to "realme",
            "oneplus" to "OnePlus",
            "iqoo" to "iQOO",
        )

        fun current(): Vendor? = of(Build.MANUFACTURER, Build.BRAND)

        /**
         * Redmi и POCO — прошивка Xiaomi. Ранние Honor выпускала Huawei, но
         * их всё равно ведём в HONOR: там пробуются оба экрана.
         */
        internal fun of(manufacturer: String?, brand: String?): Vendor? {
            val names = listOf(manufacturer, brand).mapNotNull { it?.lowercase()?.trim() }
            return when {
                names.any { it == "honor" } -> HONOR
                names.any { it == "huawei" } -> HUAWEI
                names.any { it in setOf("xiaomi", "redmi", "poco") } -> XIAOMI
                names.any { it == "samsung" } -> SAMSUNG
                // У Transsion производитель — «TECNO MOBILE LIMITED» и т. п.
                names.any { n -> listOf("tecno", "infinix", "itel").any { n.startsWith(it) } } -> TRANSSION
                // realme — 13 % продаж в России за 2025 год, вровень с Tecno;
                // ColorOS и Funtouch гасят приложения в фоне (dontkillmyapp:
                // 3 из 5). Раньше у них не было ни шага.
                names.any { it in setOf("oppo", "realme", "oneplus") } -> OPPO
                names.any { it in setOf("vivo", "iqoo") } -> VIVO
                else -> null
            }
        }
    }
}

/**
 * Экран настроек телефона, куда надо сходить: что на нём сделать и как до него
 * дойти. Путей несколько — прошивки переименовывают свои экраны от версии к
 * версии, — пробуются по порядку.
 */
data class BackgroundStep(
    /** Строка в настройках приложения. */
    val label: String,
    /** Что сделать на открывшемся экране. */
    val hint: String,
    val targets: List<SettingsTarget>,
    /**
     * Шаг снимает стандартную экономию батареи, и приложение видит, снята ли
     * она. У фирменных переключателей состояние приложению не видно.
     */
    val battery: Boolean = false,
)

/** Куда ведёт шаг: фирменный экран по имени или действие Android. */
sealed class SettingsTarget {
    data class Screen(
        val pkg: String,
        val cls: String,
        /** Дописать своё имя пакета и название — экран сразу откроется на приложении. */
        val withApp: Boolean = false,
    ) : SettingsTarget()

    data class Action(
        val action: String,
        /** Чей экран: без него действие может открыть выбор из нескольких. */
        val pkg: String? = null,
        /** Адрес `package:` своего приложения. */
        val withPackage: Boolean = false,
        val extras: Map<String, Int> = emptyMap(),
    ) : SettingsTarget()
}

object Background {

    /** Свойства приложения — если нужный экран не открылся: оттуда до него шаг-два. */
    private val APP_DETAILS = SettingsTarget.Action(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true)

    /**
     * Системное окно «Разрешить работу в фоне?», за ним — общий список
     * приложений с экономией. На HyperOS 3 то же окно открывает «Контроль
     * активности» самой Xiaomi.
     */
    private val IGNORE_OPTIMIZATIONS = listOf(
        SettingsTarget.Action(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true),
        SettingsTarget.Action(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    private fun startupManager(vararg pkgs: String) = pkgs.map { pkg ->
        SettingsTarget.Screen(pkg, "$pkg.startupmgr.ui.StartupNormalAppListActivity")
    }

    internal fun steps(vendor: Vendor): List<BackgroundStep> = when (vendor) {
        Vendor.XIAOMI -> listOf(
            BackgroundStep(
                label = "Автозапуск",
                hint = "Включите переключатель у «Когда пара?».",
                targets = listOf(
                    SettingsTarget.Screen(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    ),
                    SettingsTarget.Action("miui.intent.action.OP_AUTO_START"),
                    APP_DETAILS,
                ),
            ),
            BackgroundStep(
                label = "Контроль активности",
                hint = "Выберите «Нет ограничений».",
                targets = listOf(
                    // До HyperOS 2; в HyperOS 3 экрана нет, и его открывает
                    // стандартное окно ниже.
                    SettingsTarget.Screen(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                        withApp = true,
                    ),
                ) + IGNORE_OPTIMIZATIONS + APP_DETAILS,
            ),
        )
        Vendor.HUAWEI, Vendor.HONOR -> listOf(
            BackgroundStep(
                label = "Запуск приложений",
                hint = "Выключите «Управлять автоматически» у «Когда пара?» и включите " +
                    "все три переключателя.",
                targets = (
                    if (vendor == Vendor.HONOR) {
                        startupManager("com.hihonor.systemmanager", "com.huawei.systemmanager")
                    } else {
                        startupManager("com.huawei.systemmanager")
                    }
                    ) + SettingsTarget.Screen(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ) + APP_DETAILS,
            ),
            BackgroundStep(
                label = "Оптимизация батареи",
                hint = "Нажмите «Разрешить».",
                targets = IGNORE_OPTIMIZATIONS + APP_DETAILS,
                battery = true,
            ),
        )
        Vendor.SAMSUNG -> listOf(
            BackgroundStep(
                label = "Батарея без ограничений",
                hint = "Нажмите «Разрешить».",
                targets = IGNORE_OPTIMIZATIONS + APP_DETAILS,
                battery = true,
            ),
            BackgroundStep(
                // «Спящие» и «глубоко спящие» — отдельно от батареи: туда
                // приложение попадает само, если его несколько дней не открывать.
                label = "Никогда не усыплять",
                hint = "Добавьте «Когда пара?» в список.",
                targets = listOf(
                    SettingsTarget.Action(
                        "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY",
                        pkg = "com.samsung.android.lool",
                        extras = mapOf("activity_type" to 2),
                    ),
                    SettingsTarget.Action("com.samsung.android.sm.ACTION_BATTERY", pkg = "com.samsung.android.lool"),
                    APP_DETAILS,
                ),
            ),
        )
        Vendor.OPPO -> listOf(
            BackgroundStep(
                label = "Автозапуск",
                hint = "Включите «Разрешить автозапуск» у «Когда пара?». На новых " +
                    "прошивках — в свойствах приложения, «Использование батареи».",
                targets = listOf(
                    SettingsTarget.Screen(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    ),
                    SettingsTarget.Screen(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity",
                    ),
                    SettingsTarget.Screen(
                        "com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity",
                    ),
                    SettingsTarget.Screen(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                    ),
                    APP_DETAILS,
                ),
            ),
            BackgroundStep(
                label = "Работа в фоне",
                hint = "Нажмите «Разрешить», а в свойствах приложения, «Использование " +
                    "батареи», — «Разрешить работу в фоне».",
                targets = IGNORE_OPTIMIZATIONS + APP_DETAILS,
                battery = true,
            ),
        )
        Vendor.VIVO -> listOf(
            BackgroundStep(
                label = "Автозапуск",
                hint = "Разрешите «Когда пара?».",
                targets = listOf(
                    SettingsTarget.Screen(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    ),
                    SettingsTarget.Screen(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                    ),
                    APP_DETAILS,
                ),
            ),
            BackgroundStep(
                label = "Работа в фоне",
                hint = "Нажмите «Разрешить», а в «Батарее» разрешите высокое " +
                    "энергопотребление в фоне.",
                targets = IGNORE_OPTIMIZATIONS + APP_DETAILS,
                battery = true,
            ),
        )
        Vendor.TRANSSION -> listOf(
            BackgroundStep(
                label = "Автозапуск",
                hint = "Разрешите «Когда пара?».",
                targets = listOf(
                    SettingsTarget.Screen(
                        "com.transsion.phonemaster",
                        "com.cyin.himgr.autostart.AutoStartActivity",
                    ),
                    APP_DETAILS,
                ),
            ),
            BackgroundStep(
                label = "Оптимизация батареи",
                hint = "Нажмите «Разрешить».",
                targets = IGNORE_OPTIMIZATIONS + APP_DETAILS,
                battery = true,
            ),
        )
    }

    /** Система не экономит на приложении батарею: будильники и работа в фоне идут без отсрочки. */
    fun unrestricted(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /**
     * Открыть экран шага. Возвращает false, если открылись только свойства
     * приложения — тогда человеку надо сказать, где искать дальше.
     */
    fun open(context: Context, step: BackgroundStep): Boolean {
        for (target in step.targets) {
            if (start(context, intent(context, target))) return target != APP_DETAILS
        }
        return false
    }

    private fun intent(context: Context, target: SettingsTarget): Intent = when (target) {
        is SettingsTarget.Screen -> Intent().setComponent(ComponentName(target.pkg, target.cls)).apply {
            if (target.withApp) {
                putExtra("package_name", context.packageName)
                putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString())
            }
        }
        is SettingsTarget.Action -> Intent(target.action).apply {
            target.pkg?.let { setPackage(it) }
            if (target.withPackage) data = Uri.parse("package:${context.packageName}")
            target.extras.forEach { (key, value) -> putExtra(key, value) }
        }
    }

    /**
     * Не проверяем заранее через resolveActivity: без объявленных <queries> на
     * Android 11+ он не видит чужие экраны. Пробуем открыть — прошивка без
     * такого экрана бросает ActivityNotFoundException, закрытый экран —
     * SecurityException.
     */
    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
