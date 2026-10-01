package ru.whensclass.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.Toast
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.notify.Background
import ru.whensclass.notify.Vendor
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.launch
import ru.whensclass.widget.NextLessonWidgetReceiver
import ru.whensclass.widget.ScheduleWidgetReceiver
import ru.whensclass.widget.WeekWidgetReceiver

/** Строка-ссылка: открывает адрес в браузере или в приложении Telegram. */
@Composable
internal fun Link(text: String, url: String) {
    val context = LocalContext.current
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .padding(top = 10.dp, bottom = 2.dp),
    )
}

/** Самый широкий значок группы у названия в разделе «Группа». */
internal val MARK_IN_ROW = 88.dp

/** Строка группы в разделе «Группа»: название, пометка и одна кнопка. */
@Composable
internal fun GroupRow(
    name: String,
    action: String,
    onAction: () -> Unit,
    mark: String? = null,
    note: String? = null,
) {
    Row(
        modifier = Modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Длинное название в значке — многоточием: целиком оно и так рядом,
        // а значок во всю строку сжимал его до столбика.
        // Чтецу значок не нужен: название рядом, а «слэш два» перед ним — шум.
        if (mark != null) {
            GroupMark(mark, own = false, modifier = Modifier.widthIn(max = MARK_IN_ROW).clearAndSetSemantics {})
        }
        Column(modifier = Modifier.weight(1f).padding(start = if (mark != null) 10.dp else 0.dp, end = 8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ActionButton(
            label = action,
            onClick = onAction,
            modifier = Modifier.width(ROW_BUTTON),
            top = 0.dp,
            side = 8.dp,
            // Несколько одинаковых «Убрать» — какую группу уберёт нажатие,
            // чтец не говорил.
            spoken = "$action $name",
        )
    }
}

/**
 * Виджет на домашний экран одной кнопкой, без поиска в списке лаунчера: ради
 * виджетов приложение и ставят. Лаунчер спрашивает, куда поставить. Не умеет
 * он закреплять по просьбе приложения — раздела нет, виджет добавляют из
 * списка, как написано в инструкции.
 */
@Composable
internal fun PinWidgets() {
    val context = LocalContext.current
    val supported = remember {
        AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
    }
    if (!supported) return
    val scope = rememberCoroutineScope()
    fun pin(receiver: Class<out GlanceAppWidgetReceiver>) {
        scope.launch {
            val asked = runCatching {
                GlanceAppWidgetManager(context).requestPinGlanceAppWidget(receiver)
            }.getOrDefault(false)
            if (!asked) {
                Toast.makeText(
                    context,
                    "Лаунчер не дал добавить виджет — добавьте его из списка виджетов",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    Section("Виджеты") {
        Hint("Добавить на домашний экран — лаунчер спросит, куда поставить.")
        // Три кнопки одним рядом, одной ширины и высоты:
        // «Ближайшая пара» на узком экране мельчает, а не растягивает ряд.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tile = Modifier.weight(1f)
            ActionButton(label = "День", onClick = { pin(ScheduleWidgetReceiver::class.java) }, modifier = tile, side = 4.dp)
            ActionButton(label = "Неделя", onClick = { pin(WeekWidgetReceiver::class.java) }, modifier = tile, side = 4.dp)
            ActionButton(
                label = "Ближайшая пара",
                onClick = { pin(NextLessonWidgetReceiver::class.java) },
                modifier = tile,
                side = 4.dp,
            )
        }
    }
}

/**
 * Работа в фоне — всё, что упирается в настройки телефона: ограничения фона
 * и пояс, шаги для марок, которые режут фон сверх обычного Android, и точное
 * время. Раздела нет, когда показать нечего: до Android 12, на марке не из
 * списка и без ограничений.
 *
 * Включить это может только человек, в настройках телефона; приложение лишь
 * открывает нужный экран. Галочка у шага — только у стандартной экономии
 * батареи: фирменные переключатели прошивки приложению не видны.
 */
@Composable
internal fun BackgroundWork(phone: ru.whensclass.notify.PhoneState, exactAlarms: Boolean, reminders: Boolean) {
    val vendor = remember { Vendor.current() }
    val steps = remember(vendor) { vendor?.let { Background.steps(it) }.orEmpty() }
    // Строка «Точное время» — только там, где его выдаёт человек: на Android 12.
    // С 13-го приложение получает его при установке (USE_EXACT_ALARM), и
    // строка с вечной галочкой только занимала бы место; не выдано вдруг —
    // показать.
    val exact = Build.VERSION.SDK_INT in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2 ||
        (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2 && !exactAlarms)
    if (vendor == null && !exact && phone.backgroundLimits.isEmpty() && phone.zoneWarning == null) return
    val context = LocalContext.current
    Section("Работа в фоне") {
        PhoneLimits(phone)
        if (vendor != null) {
            Hint(
                "${vendor.title()} не будит приложения в фоне: виджет не обновится, а " +
                    "напоминание не придёт, пока приложение не открыть. Нажмите пункты по очереди.",
            )
            steps.forEach { step ->
                ExternalRow(step.label, checked = if (step.battery) phone.unrestricted else null) {
                    if (!Background.open(context, step)) {
                        Toast.makeText(
                            context,
                            "Экран «${step.label}» не открылся — ищите его в свойствах приложения",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                // Имя — как у этого приложения: у tested оно своё, и на экране
                // марки с двумя приложениями человек включал основное.
                Hint(step.hint.replace("«Когда пара?»", "«${context.getString(ru.whensclass.R.string.app_name)}»"))
            }
            if (vendor.pinInRecents) {
                Hint(
                    "Ещё закрепите приложение в недавних (замок на карточке): иначе очистка " +
                        "памяти выгружает его вместе с остальными.",
                )
            }
        }
        // После шагов марки: это разрешение, а не выключатель, и нужно оно не
        // только напоминаниям — звонок для виджетов, подсветка идущей пары,
        // ждёт того же. Посреди
        // выключателей уведомлений оно рвало их ряд.
        if (exact) ExactAlarms(exactAlarms, reminders)
        if (vendor != null) {
            Hint(
                "Не помогло — напишите автору, ссылка в «О приложении», и приложите " +
                    "«Сведения для отчёта».",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Строка, которая уводит в настройки телефона: значок справа говорит, что
 * нажатие откроет чужой экран. [checked] — что там сейчас, если это видно.
 */
@Composable
private fun ExternalRow(label: String, checked: Boolean? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checked != null) MinimalCheck(selected = checked, modifier = Modifier.padding(end = 10.dp))
            ExternalMark(color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/** Пояснение под выключателем. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
internal fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            content()
        }
    }
}

/**
 * Точное время напоминаний.
 *
 * Обычный будильник система вправе отложить, экономя батарею, — на десятки
 * минут, если телефон спит. Для расписания это значит «предупредили, когда
 * пара уже идёт». Точное время требует отдельного разрешения, и выдаётся оно
 * не здесь, а в настройках телефона.
 *
 * Поэтому строка не тумблер, а ссылка: тумблер обещает, что переключится сам,
 * и нажатие на него выглядело поломкой — бегунок не двигался, а вместо этого
 * открывался чужой экран. Галочка слева от значка говорит, что там сейчас,
 * значок — что нажатие уводит наружу.
 *
 * До Android 12 разрешения не существовало, с 13-го оно выдаётся при
 * установке (USE_EXACT_ALARM) — строка нужна только Android 12.
 */
@Composable
private fun ExactAlarms(allowed: Boolean, reminders: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    // runCatching, как у остальных переходов: на части прошивок такого экрана
    // нет, и голый вызов ронял приложение. Не открылся — сказать и открыть
    // свойства приложения, как у шагов марки: раньше нажатие молчало.
    val open = {
        val opened = runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!opened) {
            Toast.makeText(
                context,
                "Экран «Будильники и напоминания» не открылся — ищите его в свойствах приложения",
                Toast.LENGTH_LONG,
            ).show()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        Unit
    }
    ExternalRow("Точное время", checked = allowed, onClick = open)
    val what = if (reminders) "напоминание и выделение на виджетах" else "выделение на виджетах"
    Text(
        if (allowed) {
            if (reminders) "Напоминание придёт правильно, выделение на виджетах сменится со звонком."
            else "Выделение идущей пары на виджетах сменится со звонком."
        } else {
            "Без него система может сдвинуть $what. Нажмите — разрешение " +
                "выдаётся в настройках телефона."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Канал выключен в настройках телефона — отдельно от приложения целиком:
 * «Больше не показывать» на уведомлении гасит только его, и выключатель
 * здесь стоял «вкл» при молчащих уведомлениях.
 */
@Composable
internal fun ChannelOff(channel: String) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            // Названием канала, как его покажет телефон: «Другие группы
            // выключены» читалось как «выключены группы».
            "Уведомления «${ru.whensclass.notify.Notifications.channelName(channel)}» выключены " +
                "в настройках телефона.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        ActionButton(
            label = "Включить",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

/**
 * Ограничения телефона, которых приложение не выбирало: фон, экономия
 * трафика, часовой пояс. Раньше о них молчали, и при «всё включено»
 * напоминания и отмены не приходили.
 */
@Composable
private fun PhoneLimits(phone: ru.whensclass.notify.PhoneState) {
    val context = LocalContext.current
    if (phone.backgroundLimits.isNotEmpty()) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Text(
                "Телефон ограничивает приложение",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                phone.backgroundLimits.joinToString("; ").replaceFirstChar { it.uppercase() } + ". " +
                    "Напоминания, часовое обновление и сообщения об отменах могут не приходить.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionButton(
                label = "Открыть настройки приложения",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
    }
    phone.zoneWarning?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

/**
 * Подсказка, когда уведомления запрещены системой.
 *
 * Без разрешения не приходит ни одно уведомление, а переключатели ниже при этом
 * выглядят рабочими: человек включает напоминание о паре и не понимает, почему
 * его нет. Спрашиваем разрешение при первом запуске, но отказ надо пережить —
 * значит нужен путь назад.
 */
@Composable
internal fun NotificationsDenied(allowed: Boolean) {
    if (allowed) return
    val context = LocalContext.current
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            "Уведомления запрещены",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "Пока разрешение не выдано, не придёт ни одно из уведомлений ниже.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionButton(
            label = "Разрешить",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}
