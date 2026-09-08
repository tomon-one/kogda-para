package ru.whensclass.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.BuildConfig
import ru.whensclass.data.DEFAULT_NOTIFY_BEFORE
import ru.whensclass.data.ReleaseDto
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.widget.ThemeChoice
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
    exactAlarms: Boolean,
    notifications: Boolean,
    onNotifyBefore: (Int) -> Unit,
    onNotifyEnabled: (Boolean) -> Unit,
    onNotifyChanges: (Boolean) -> Unit,
    onNotifyUpdates: (Boolean) -> Unit,
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
    onBack: () -> Unit,
) {
    var askOwnTime by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    if (showReport) ReportDialog(loadDiagnostics) { showReport = false }
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
                label = if (teacherMode) "Сменить преподавателя" else "Сменить группу",
                onClick = onChangeGroup,
            )

            if (!teacherMode) {
                Text(
                    "Подгруппа: " + (secondGroupName ?: "нет"),
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

        Section("Оформление") {
            ThemeOption("Как в системе", ThemeChoice.SYSTEM, theme, onTheme)
            ThemeOption("Тёмная", ThemeChoice.DARK, theme, onTheme)
            ThemeOption("Светлая", ThemeChoice.LIGHT, theme, onTheme)
        }

        Section("Уведомления") {
            NotificationsDenied(notifications)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Напоминать о паре", style = MaterialTheme.typography.bodyLarge)
                MinimalSwitch(checked = notifyEnabled, onCheckedChange = onNotifyEnabled)
            }
            if (notifyEnabled) {
                Text(
                    "За сколько предупредить",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Разброс большой: кому-то хватит десяти минут, а кому-то ехать
                // через весь город.
                FlowRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    NOTIFY_OPTIONS.forEach { minutes ->
                        MinutesChip(
                            minutes = minutes,
                            selected = minutes == notifyBefore,
                            onPick = { onNotifyBefore(minutes) },
                        )
                    }
                    // Готовых значений хватает не всем: кто-то едет ровно сорок
                    // семь минут и хочет именно столько.
                    MinutesChip(
                        label = if (notifyBefore !in NOTIFY_OPTIONS) formatDurationShort(notifyBefore)
                        else "Своё",
                        selected = notifyBefore !in NOTIFY_OPTIONS,
                        onPick = { askOwnTime = true },
                    )
                }
                ExactAlarms(exactAlarms)
            }

            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Сообщать об изменениях", style = MaterialTheme.typography.bodyLarge)
                MinimalSwitch(checked = notifyChanges, onCheckedChange = onNotifyChanges)
            }
            Text(
                "Отмены и замены на сегодня и завтра.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Сообщать о новых версиях", style = MaterialTheme.typography.bodyLarge)
                MinimalSwitch(checked = notifyUpdates, onCheckedChange = onNotifyUpdates)
            }
            Text(
                // Магазина нет, обновление никто не принесёт.
                "Приложение проверяет это раз в час вместе с расписанием.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Расписание") {
            Text(
                "Приложение забирает расписание каждый час и при каждом открытии. " +
                    "Сервер перечитывает таблицу колледжа каждые 20 минут с 6 утра " +
                    "до 10 вечера и ещё раз " +
                    "перед каждой парой.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Данные") {
            Text(
                // У преподавателя на сервер уходит не группа, а он сам —
                // писать про группу было бы неправдой.
                if (teacherMode) {
                    "На сервер уходит выбранное имя, как оно записано в таблице " +
                    "колледжа. Больше ничего: " +
                        "ни номера, ни местоположения. Учётной записи нет, " +
                        "аналитики и рекламы нет. Когда смотрите чужое расписание, " +
                        "серверу уходит, чьё именно: иначе его неоткуда взять."
                } else {
                    "На сервер уходит только название вашей группы — и подгруппы, " +
                        "если вы её выбрали. Больше ничего: ни имени, " +
                        "ни номера, ни местоположения. Учётной записи нет, " +
                        "аналитики и рекламы нет. Когда смотрите чужое расписание, " +
                        "серверу уходит, чьё именно: иначе его неоткуда взять."
                },
                // Тем же приглушённым, что и остальные пояснения: белый текст
                // в одном блоке из шести читался как что-то важнее прочего.
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Ответственность") {
            Text(
                "Расписание берётся из общей таблицы колледжа. Что написано там, " +
                    "то и покажет приложение: за ошибки, замены и опоздавшие " +
                    "обновления я не отвечаю.\n\n" +
                    "Если однажды что-то сломается, я постараюсь починить, но сроков " +
                    "не обещаю. Пропущенная пара остаётся на вашей совести, даже " +
                    "если приложение в этот момент показывало ерунду. Сверяйтесь " +
                    "с таблицей, когда это важно.",
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
                Text(
                    "Скачается с сервера приложения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionButton(
                    label = if (installing) "Скачиваю…" else "Обновить приложение",
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
                "Неофициальное приложение для студентов НГОК.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Link("Нашли ошибку? Напишите мне в Telegram", "https://t.me/toomonn")
            Text(
                "Сведения для отчёта",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showReport = true }
                    .padding(top = 10.dp, bottom = 2.dp),
            )
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
/**
 * Отчёт об ошибке готовым текстом.
 *
 * Скриншот показывает, что человек видит, но не показывает, какая у него
 * сборка и что она в последний раз получила от сервера. Отсюда это уезжает
 * одной кнопкой — и разговор начинается не с десяти вопросов.
 */
@Composable
private fun ReportDialog(load: suspend () -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    // Сведения нужны ровно тогда, когда что-то не так. Уронить
    // приложение на сборе сведений о поломке было бы издевательством.
    LaunchedEffect(Unit) {
        report = runCatching { load() }
            .getOrElse { "Собрать не вышло: ${it.javaClass.simpleName}" }
    }
    val text = report

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сведения для отчёта") },
        text = {
            // Список короткий, но на маленьком экране вместе с заголовком и
            // кнопками он всё же упирается в край.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Пришлите это вместе с жалобой. Личного здесь нет: " +
                        "версия, группа и то, что приложению ответил сервер.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(
                        text ?: "Собираю…",
                        style = MaterialTheme.typography.bodySmall,
                        // Ровными столбцами: так видно, что это выписка, а не
                        // рассказ, и её надо переслать целиком.
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    text?.let { copyToClipboard(context, "Отчёт «Когда пара?»", it) }
                    onDismiss()
                },
                enabled = text != null,
            ) { Text("Скопировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

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

@Composable
private fun MinutesChip(
    minutes: Int = 0,
    label: String = formatDurationShort(minutes),
    selected: Boolean,
    onPick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp).clickable(onClick = onPick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
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
private fun ExactAlarms(allowed: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    val open = {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
        Text("Точные уведомления", style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MinimalCheck(selected = allowed, modifier = Modifier.padding(end = 10.dp))
            ExternalMark(color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(
        if (allowed) {
            "Напоминание придёт минута в минуту."
        } else {
            "Система вправе отложить напоминание, экономя батарею. Нажмите — " +
                "откроются настройки телефона, разрешение выдаётся там."
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
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
}
