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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.BuildConfig
import ru.whensclass.data.ReleaseDto
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.launch
import ru.whensclass.widget.NextLessonWidgetReceiver
import ru.whensclass.widget.ScheduleWidgetReceiver
import ru.whensclass.widget.ThemeChoice
import ru.whensclass.widget.WeekWidgetReceiver
import ru.whensclass.widget.formatDurationShort

/**
 * Настройки и короткий честный рассказ о данных.
 *
 * Тексты намеренно немногословны: это приложение для одногруппников, а не
 * пользовательское соглашение, которое всё равно никто не читает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    groupName: String?,
    secondGroupName: String?,
    teacherMode: Boolean,
    onSwitchRole: () -> Unit,
    theme: ThemeChoice,
    update: ReleaseDto?,
    installing: Boolean,
    notifyBefore: Int,
    notifyEnabled: Boolean,
    notifyChanges: Boolean,
    notifyUpdates: Boolean,
    notifyServer: Boolean = true,
    notifySubgroup: Boolean = true,
    exactAlarms: Boolean,
    notifications: Boolean,
    /** Что телефон делает с приложением помимо его настроек. */
    phone: ru.whensclass.notify.PhoneState = ru.whensclass.notify.PhoneState(),
    onNotifyBefore: (Int) -> Unit,
    onNotifyEnabled: (Boolean) -> Unit,
    onNotifyChanges: (Boolean) -> Unit,
    onNotifyUpdates: (Boolean) -> Unit,
    onNotifyServer: (Boolean) -> Unit = {},
    onNotifySubgroup: (Boolean) -> Unit = {},
    focusUpdate: Boolean,
    checkingUpdate: Boolean,
    updateChecked: Boolean,
    updateFailed: Boolean,
    updateError: String?,
    onCheckUpdate: () -> Unit,
    onTheme: (ThemeChoice) -> Unit,
    onChangeGroup: () -> Unit,
    onPickSecondGroup: () -> Unit,
    onClearSecondGroup: () -> Unit,
    onUpdate: () -> Unit,
    loadDiagnostics: suspend () -> String,
    /** Таблица колледжа — к своей колонке на сегодня. Null, пока сервер не назвал адрес. */
    sheetUrl: () -> String?,
    onBack: () -> Unit,
) {
    var askOwnTime by rememberSaveable { mutableStateOf(false) }
    if (askOwnTime) {
        OwnTimeDialog(
            current = notifyBefore,
            onDismiss = { askOwnTime = false },
            onPick = {
                onNotifyBefore(it)
                askOwnTime = false
            },
        )
    }

    val scroll = rememberScrollState()
    var updateOffset by remember { mutableIntStateOf(0) }

    // Пришли по значку обновления — сразу прокручиваем к нему: раздел стоит
    // внизу, и искать его глазами не надо.
    LaunchedEffect(focusUpdate, updateOffset) {
        if (focusUpdate && updateOffset > 0) scroll.animateScrollTo(updateOffset)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Настройки",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Section(if (teacherMode) "Преподаватель" else "Группа") {
            Text(groupName ?: "не выбрано", style = MaterialTheme.typography.bodyLarge)
            ActionButton(
                label = if (teacherMode) "Выбрать заново" else "Сменить группу",
                onClick = onChangeGroup,
            )

            if (!teacherMode) {
                Text(
                    // «Соседняя»: просто «Подгруппа» читали как свою первую или
                    // вторую и выбирали себя же (разбор текстов 27.09).
                    "Соседняя подгруппа: " + (secondGroupName ?: "нет"),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        label = if (secondGroupName == null) "Добавить" else "Заменить",
                        onClick = onPickSecondGroup,
                    )
                    if (secondGroupName != null) {
                        ActionButton(label = "Убрать", onClick = onClearSecondGroup)
                    }
                }
            }
        }

        PinWidgets()

        Section("Оформление") {
            ThemeOption("Как в системе", ThemeChoice.SYSTEM, theme, onTheme)
            ThemeOption("Тёмная", ThemeChoice.DARK, theme, onTheme)
            ThemeOption("Светлая", ThemeChoice.LIGHT, theme, onTheme)
        }

        Section("Уведомления") {
            NotificationsDenied(notifications)
            PhoneLimits(phone)
            SwitchRow("Напоминать о паре", notifyEnabled, onNotifyEnabled)
            if (notifyEnabled && notifications && phone.lessonChannelOff) {
                ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_LESSON, "Напоминания о паре")
            }
            if (notifyEnabled) {
                // Обещание должно совпадать с поведением: напоминаем не о
                // каждой паре, и человек вправе знать об этом до того, как
                // решит, что напоминания сломались.
                Text(
                    "О первой паре дня — всегда. О следующих — только если " +
                        "напоминание приходится на перемену, а не на пару.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                // Одной строкой с выпадающим списком: одиннадцать фишек в три
                // ряда занимали полэкрана ради одной настройки (просьба Tomon
                // 27.09). Разброс по-прежнему большой: кому-то хватит десяти
                // минут, кому-то ехать через весь город, а кому-то ровно сорок
                // семь — для того «Своё время…».
                MinutesPicker(
                    current = notifyBefore,
                    onPick = onNotifyBefore,
                    onOwn = { askOwnTime = true },
                )
            }

            // У каждого вида уведомлений — свой выключатель и свой канал:
            // сбой сервера и пропажа подгруппы раньше шли под «изменениями»
            // (просьба Tomon 27.09).
            SwitchRow("Сообщать об изменениях", notifyChanges, onNotifyChanges)
            if (notifyChanges && notifications && phone.changesChannelOff) {
                ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_CHANGES, "Сообщения об изменениях")
            }
            Hint("Отмены и замены на сегодня и завтра.")

            SwitchRow("Сообщать о сбоях сервера", notifyServer, onNotifyServer)
            if (notifyServer && notifications && phone.serverChannelOff) {
                ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_SERVER, "Сообщения о сбоях")
            }
            Hint("Если расписание не обновляется дольше двух часов — один раз за сбой; " +
                "уведомление уберётся само, когда сервер починится.")

            // Только студенту с выбранной соседней подгруппой: остальным
            // этого уведомления не бывает.
            if (!teacherMode && secondGroupName != null) {
                SwitchRow("Сообщать о пропаже подгруппы", notifySubgroup, onNotifySubgroup)
                if (notifySubgroup && notifications && phone.subgroupChannelOff) {
                    ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_SUBGROUP, "Сообщения о подгруппе")
                }
                Hint("Если соседней подгруппы не стало в таблице — один раз.")
            }

            SwitchRow("Сообщать о новых версиях", notifyUpdates, onNotifyUpdates)
            if (notifyUpdates && notifications && phone.updateChannelOff) {
                ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_UPDATE, "Сообщения о новых версиях")
            }
            // Магазина нет, обновление никто не принесёт.
            Hint("Приложение проверяет это раз в час вместе с расписанием.")

            // В конце блока: это разрешение телефона, а не выключатель, и нужно
            // оно не только напоминаниям — звонок для виджетов, подсветка
            // идущей пары, ждёт того же, а на Android 14+ его по умолчанию нет.
            // Посреди выключателей оно рвало их
            // ряд (просьба Tomon 27.09).
            ExactAlarms(exactAlarms, notifyEnabled)
        }

        Section("Расписание") {
            Text(
                // Четыре срока и две машины, из которых человеку нечего выбрать:
                // в разделе настроек это рассказ мимо дела.
                // «Перед парой чаще» — это про сервер: телефон ходит за
                // расписанием раз в час и при открытии.
                // У преподавателя своей колонки нет: берётся колонка группы
                // первой пары (SheetLink).
                "Телефон забирает его раз в час и при каждом открытии, сервер " +
                    "читает таблицу колледжа чаще и перед каждой парой. Кнопка ниже " +
                    (if (teacherMode) "откроет таблицу на сегодняшнем дне, у колонки " +
                        "группы первой пары."
                    else "откроет таблицу на вашей колонке и сегодняшнем дне."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Постоянное место для ссылки. Раньше она всплывала только при
            // сбое или пустых днях — а «на всякий случай» её хотят и в
            // обычный день: сверить, показать, посмотреть соседей.
            val context = LocalContext.current
            ActionButton(
                label = "Открыть таблицу колледжа",
                onClick = {
                    val url = sheetUrl()
                    if (url == null) {
                        Toast.makeText(context, "Адрес таблицы ещё не получен от сервера", Toast.LENGTH_SHORT).show()
                    } else {
                        openLink(context, url)
                    }
                },
            )
        }

        Section("Данные") {
            Text(
                // У преподавателя на сервер уходит не группа, а он сам —
                // писать про группу было бы неправдой.
                if (teacherMode) {
                    "На сервер уходит выбранное имя, как оно записано в таблице " +
                    "колледжа. Больше ничего: " +
                        "ни номера телефона, ни местоположения. Учётной записи нет, " +
                        "аналитики и рекламы нет. Когда смотрите чужое расписание, " +
                        "серверу уходит, чьё именно: иначе его неоткуда взять. " +
                        "Всё для вашего удобства."
                } else {
                    "На сервер уходит только название вашей группы — и подгруппы, " +
                        "если вы её выбрали. Больше ничего: ни имени, " +
                        "ни номера телефона, ни местоположения. Учётной записи нет, " +
                        "аналитики и рекламы нет. Когда смотрите чужое расписание, " +
                        "серверу уходит, чьё именно: иначе его неоткуда взять. " +
                        "Всё для вашего удобства."
                },
                // Тем же приглушённым, что и остальные пояснения: белый текст
                // в одном блоке из шести читался как что-то важнее прочего.
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Ответственность") {
            Text(
                "Что написано в таблице колледжа, то и покажет приложение: за " +
                    "ошибки, замены и опоздавшие обновления автор не отвечает.\n\n" +
                    "Если однажды что-то сломается, автор постарается починить, но " +
                    "сроков не обещает. Пропущенная пара остаётся на вашей совести, " +
                    "даже если приложение в этот момент показывало неправильно. " +
                    "Сверяйтесь с таблицей, когда это важно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(
            // Установленная версия — сам заголовок блока: строкой ниже она
            // повторяла то, что и так написано сверху.
            "Версия ${BuildConfig.VERSION_NAME}",
            modifier = Modifier.onGloballyPositioned { updateOffset = it.positionInParent().y.toInt() },
        ) {
            if (update != null) {
                Text(
                    "Вышла ${update.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (update.notes.isNotBlank()) {
                    Text(
                        update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!phone.canInstall) {
                    // Заранее: иначе системный запрет «установка из этого
                    // источника» для самой «Когда пара?» выглядел подозрительно,
                    // и осторожный человек отказывал.
                    Text(
                        "В первый раз Android попросит разрешить «Когда пара?» установку " +
                            "приложений: так она ставит обновление самой себе. Другие " +
                            "приложения она не ставит.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                ActionButton(
                    label = if (installing) "Скачивается…" else "Обновить приложение",
                    onClick = onUpdate,
                    enabled = !installing,
                )
                updateError?.let { why ->
                    Text(
                        "Не удалось: $why",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                // Раньше кнопка молчала, когда обновления не было, и выглядела
                // сломанной. Теперь всегда отвечает.
                if (updateChecked && !checkingUpdate) {
                    // «Не дозвонился» и «новее нет» раньше выглядели одинаково,
                    // и человек уходил уверенным, что у него свежая сборка.
                    Text(
                        if (updateFailed) {
                            "Проверить не вышло: сервер не ответил"
                        } else {
                            "Установлена последняя версия"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                ActionButton(
                    label = when {
                        checkingUpdate -> "Проверка…"
                        updateChecked -> "Проверить ещё раз"
                        else -> "Проверить обновления"
                    },
                    onClick = onCheckUpdate,
                    enabled = !checkingUpdate,
                )
            }
        }

        Section("О приложении") {
            Text(
                "Неофициальное приложение для студентов и преподавателей НГОК.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Link("Нашли ошибку? Напишите автору в Telegram", "https://t.me/toomonn")
            ReportLink(loadDiagnostics, modifier = Modifier.fillMaxWidth())
            Link("GitHub автора", "https://github.com/tomon-one")
            Text(
                if (teacherMode) "Я студент" else "Я преподаватель",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSwitchRole)
                    .padding(top = 14.dp, bottom = 4.dp),
            )
            Text(
                "Создано Tomon",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
    }
}

/** Строка-ссылка: открывает адрес в браузере или в приложении Telegram. */
@Composable
private fun Link(text: String, url: String) {
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

/** Насколько заранее можно попросить напоминание. */
private val NOTIFY_OPTIONS = listOf(10, 15, 20, 30, 45, 60, 90, 120, 180, 240)
private const val MIN_NOTIFY = 10
private const val MAX_NOTIFY = 240

/**
 * Своё время напоминания.
 *
 * Границы взяты из здравого смысла: меньше десяти минут предупреждать поздно,
 * дольше четырёх часов — уже не про эту пару.
 */
@Composable
private fun OwnTimeDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var value by remember { mutableStateOf(current.takeIf { it > 0 }?.toString().orEmpty()) }
    val minutes = value.toIntOrNull()
    val valid = minutes != null && minutes in MIN_NOTIFY..MAX_NOTIFY

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("За сколько предупредить") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { new -> value = new.filter { it.isDigit() }.take(3) },
                    label = { Text("Минут") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    // Поле принимает минуты, ими и говорим: «до четырёх часов»
                    // заставляло считать в уме.
                    "От $MIN_NOTIFY до $MAX_NOTIFY минут (${MAX_NOTIFY / 60} часа)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { minutes?.let(onPick) }, enabled = valid) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Виджет на домашний экран одной кнопкой, без поиска в списке лончера: ради
 * виджетов приложение и ставят. Лончер спрашивает, куда поставить. Не умеет
 * он закреплять по просьбе приложения — раздела нет, виджет добавляют из
 * списка, как написано в инструкции.
 */
@Composable
private fun PinWidgets() {
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
                    "Лончер не дал добавить виджет — добавьте его из списка виджетов",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    Section("Виджеты") {
        Hint("Добавить на домашний экран — лончер спросит, куда поставить.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(label = "День", onClick = { pin(ScheduleWidgetReceiver::class.java) })
            ActionButton(label = "Неделя", onClick = { pin(WeekWidgetReceiver::class.java) })
        }
        ActionButton(label = "Ближайшая пара", onClick = { pin(NextLessonWidgetReceiver::class.java) })
    }
}

/** Пояснение под выключателем. */
@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * «За сколько предупредить · 20 мин ▾» — одной строкой. Выбранное в списке
 * отмечено галочкой и для экранного чтеца.
 */
@Composable
private fun MinutesPicker(current: Int, onPick: (Int) -> Unit, onOwn: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Выбрать время") { open = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("За сколько предупредить", style = MaterialTheme.typography.bodyLarge)
        Box {
            Text(
                formatDurationShort(current) + " ▾",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                NOTIFY_OPTIONS.forEach { minutes ->
                    MinutesItem(formatDurationShort(minutes), selected = minutes == current) {
                        open = false
                        onPick(minutes)
                    }
                }
                val own = current !in NOTIFY_OPTIONS
                MinutesItem(
                    if (own) "Своё: ${formatDurationShort(current)}…" else "Своё время…",
                    selected = own,
                ) {
                    open = false
                    onOwn()
                }
            }
        }
    }
}

@Composable
private fun MinutesItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null) }
        } else {
            null
        },
        modifier = Modifier.semantics { this.selected = selected },
    )
}

@Composable
private fun Section(
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

@Composable
private fun ThemeOption(
    label: String,
    value: ThemeChoice,
    current: ThemeChoice,
    onPick: (ThemeChoice) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Не ниже 48dp: попасть пальцем в строку списка иначе трудно.
            .heightIn(min = 48.dp)
            .selectable(selected = value == current, onClick = { onPick(value) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinimalCheck(selected = value == current, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
 * До Android 12 разрешения не существовало — там раздел просто не нужен.
 */
@Composable
private fun ExactAlarms(allowed: Boolean, reminders: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    // runCatching, как у остальных переходов: на части прошивок такого экрана
    // нет, и голый вызов ронял приложение.
    val open = {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Unit
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .heightIn(min = 48.dp)
            .clickable(onClick = open),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Точное время", style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MinimalCheck(selected = allowed, modifier = Modifier.padding(end = 10.dp))
            ExternalMark(color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val what = if (reminders) "напоминание и подсветку на виджетах" else "подсветку на виджетах"
    Text(
        if (allowed) {
            if (reminders) "Напоминание придёт минута в минуту, подсветка на виджетах сменится со звонком."
            else "Подсветка идущей пары на виджетах сменится со звонком."
        } else {
            "Без него система может сдвинуть $what. Нажмите — разрешение " +
                "выдаётся в настройках телефона."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Подсказка, когда уведомления запрещены системой.
 *
 * Без разрешения не приходит ни одно уведомление, а переключатели ниже при этом
 * выглядят рабочими: человек включает напоминание о паре и не понимает, почему
 * его нет. Спрашиваем разрешение при первом запуске, но отказ надо пережить —
 * значит нужен путь назад.
 */
/**
 * Канал выключен в настройках телефона — отдельно от приложения целиком:
 * «Больше не показывать» на уведомлении гасит только его, и выключатель
 * здесь стоял «вкл» при молчащих уведомлениях.
 */
@Composable
private fun ChannelOff(channel: String, what: String) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            "$what выключены в настройках телефона — не придёт ни одно.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        ActionButton(
            label = "Включить",
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

@Composable
private fun NotificationsDenied(allowed: Boolean) {
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
