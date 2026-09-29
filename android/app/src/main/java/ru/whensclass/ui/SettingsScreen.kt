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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.BuildConfig
import ru.whensclass.data.ReleaseDto
import ru.whensclass.notify.Background
import ru.whensclass.notify.Vendor
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.launch
import ru.whensclass.widget.NextLessonWidgetReceiver
import ru.whensclass.widget.ScheduleWidgetReceiver
import ru.whensclass.widget.ThemeChoice
import ru.whensclass.widget.WeekWidgetReceiver

/**
 * Настройки и короткий честный рассказ о данных.
 *
 * Разделы — от частого к разовому: группа, виджеты, уведомления и всё, что
 * им мешает в телефоне; дальше таблица, оформление, версия, данные и «О
 * приложении» (Tomon 28.09). Тексты намеренно немногословны: это не
 * пользовательское соглашение, которое всё равно никто не читает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    groupName: String?,
    /** Остальные выбранные группы по порядку — их пары на экране рядом со своими. */
    extraGroups: List<ru.whensclass.data.ExtraGroup> = emptyList(),
    /** Подгруппы своей группы, которых ещё нет среди выбранных и которые влезают. */
    subgroups: List<ru.whensclass.data.GroupDto> = emptyList(),
    /** Подписи групп у пар: названиями или номерами. */
    groupsByName: Boolean = true,
    onGroupsByName: (Boolean) -> Unit = {},
    teacherMode: Boolean,
    theme: ThemeChoice,
    update: ReleaseDto?,
    installing: Boolean,
    notifyBefore: Int,
    notifyEnabled: Boolean,
    notifyChanges: Boolean,
    notifyUpdates: Boolean,
    notifyServer: Boolean = true,
    notifyGroupsGone: Boolean = true,
    exactAlarms: Boolean,
    notifications: Boolean,
    /** Что телефон делает с приложением помимо его настроек. */
    phone: ru.whensclass.notify.PhoneState = ru.whensclass.notify.PhoneState(),
    onNotifyBefore: (Int) -> Unit,
    onNotifyEnabled: (Boolean) -> Unit,
    onNotifyChanges: (Boolean) -> Unit,
    onNotifyUpdates: (Boolean) -> Unit,
    onNotifyServer: (Boolean) -> Unit = {},
    onNotifyGroupsGone: (Boolean) -> Unit = {},
    focusUpdate: Boolean,
    /** Прокрутили к «Версии» — больше не нужно (М63). */
    onUpdateFocused: () -> Unit = {},
    checkingUpdate: Boolean,
    updateChecked: Boolean,
    updateFailed: Boolean,
    updateError: String?,
    onCheckUpdate: () -> Unit,
    onTheme: (ThemeChoice) -> Unit,
    onChangeGroup: () -> Unit,
    onAddGroup: () -> Unit = {},
    onAddSubgroup: (ru.whensclass.data.GroupDto) -> Unit = {},
    onRemoveGroup: (String) -> Unit = {},
    onUpdate: () -> Unit,
    loadDiagnostics: suspend () -> String,
    /** Таблица колледжа — к своей колонке на сегодня. Null, пока сервер не назвал адрес. */
    sheetUrl: () -> String?,
    onBack: () -> Unit,
) {
    var askMinutes by rememberSaveable { mutableStateOf(false) }
    if (askMinutes) {
        ReminderDialog(
            current = notifyBefore,
            onDismiss = { askMinutes = false },
            onPick = {
                onNotifyBefore(it)
                askMinutes = false
            },
        )
    }

    val scroll = rememberScrollState()
    var updateOffset by remember { mutableIntStateOf(0) }

    // Пришли по значку обновления — сразу прокручиваем к нему: раздел стоит
    // внизу, и искать его глазами не надо.
    // Один раз за приход: раньше любая правка выше «Версии» (добавить
    // группу, включить напоминания, вернуться из выбора) снова уносила экран
    // вниз (четвёртый аудит, М63 прогона 1).
    LaunchedEffect(focusUpdate, updateOffset) {
        if (focusUpdate && updateOffset > 0) {
            scroll.animateScrollTo(updateOffset)
            onUpdateFocused()
        }
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
            // Название и кнопка одной строкой: столбиком раздел выходил
            // вдвое выше, а читается так же. Кнопки раздела — одной ширины
            // (Tomon 28.09).
            // С другими группами у каждой — её значок, как под временем пар:
            // номер или короткое название (Tomon 28.09).
            val marks = if (teacherMode || extraGroups.isEmpty()) null
            else if (groupsByName) shortLabels(listOf(groupName.orEmpty()) + extraGroups.map { it.name })
            else (1..extraGroups.size + 1).map { it.toString() }
            // Значок, повторяющий название целиком, только сжимал его, а чтецу он
            // не нужен — как у строк других групп (М16, М20, прогон 2).
            val ownMark = marks?.getOrNull(0)?.takeIf { it != groupName }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ownMark != null) {
                    GroupMark(ownMark, own = true, modifier = Modifier.widthIn(max = MARK_IN_ROW).clearAndSetSemantics {})
                }
                Text(
                    groupName ?: "не выбрано",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(start = if (ownMark != null) 10.dp else 0.dp, end = 8.dp),
                )
                ActionButton(
                    label = if (teacherMode) "Выбрать заново" else "Сменить",
                    onClick = onChangeGroup,
                    modifier = Modifier.width(ROW_BUTTON),
                    top = 0.dp,
                    side = 8.dp,
                )
            }

            if (!teacherMode) {
                // Остальные группы — любые, до шести вместе со своей (Tomon
                // 28.09). Значок — тот же, что под временем пар.
                // С ключом: без него после «Убрать» или «Добавить» строка
                // следующей группы вставала на место ушедшей и забирала её
                // подсветку нажатия (Tomon 28.09).
                extraGroups.forEachIndexed { index, group ->
                    key(group.id) {
                        GroupRow(
                            name = group.name + if (group.gone) " — нет в таблице" else "",
                            // Значок, повторяющий название целиком (не подгруппа
                            // своей), только сжимал само название в столбик (М16).
                            mark = marks?.getOrNull(index + 1)?.takeIf { it != group.name },
                            action = "Убрать",
                            onAction = { onRemoveGroup(group.id) },
                        )
                    }
                }
                // Подгруппы своей группы — строками с «Добавить», как у
                // добавленных «Убрать»: две кнопки рядом выходили разной
                // высоты (Tomon 28.09).
                subgroups.forEach { group ->
                    key(group.id) {
                        GroupRow(
                            name = group.name,
                            note = "подгруппа вашей группы",
                            action = "Добавить",
                            onAction = { onAddSubgroup(group) },
                        )
                    }
                }
                if (extraGroups.size < ru.whensclass.data.MAX_GROUPS - 1) {
                    ActionButton(
                        label = if (extraGroups.isEmpty() && subgroups.isEmpty()) "Добавить группу"
                        else "Добавить другую группу",
                        onClick = onAddGroup,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                if (extraGroups.isEmpty()) {
                    Hint("Пары других групп можно видеть в расписании рядом со своими.")
                } else {
                    // Названия или номера под временем пар (Tomon 28.09).
                    Text(
                        "Подписи у пар",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                    Segmented(
                        options = listOf("Названия" to true, "Номера" to false),
                        selected = groupsByName,
                        onPick = onGroupsByName,
                    )
                    // Что значат значки — здесь, где их выбирают (Tomon 28.09).
                    Hint(
                        "Под часами пары — группы, у которых она есть: ваша закрашена, " +
                            "остальные более бледные. Пары, которых у вашей группы нет, — на сером фоне. " +
                            "Виджеты, напоминания и уведомления об изменениях — только о вашей группе.",
                    )
                }
            }
        }

        PinWidgets()

        Section("Уведомления") {
            NotificationsDenied(notifications)
            SwitchRow("Напоминать о паре", notifyEnabled, onNotifyEnabled)
            if (notifyEnabled && notifications && phone.lessonChannelOff) {
                ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_LESSON)
            }
            if (notifyEnabled) {
                // Обещание должно совпадать с поведением: напоминаем не о
                // каждой паре, и человек вправе знать об этом до того, как
                // решит, что напоминания сломались.
                Text(
                    "Напоминание о первой паре дня или между парами.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                // Строка с кнопкой, выбор — в своём окне (Tomon 29.09): одиннадцать
                // фишек прямо в карточке занимали полэкрана (27.09), а
                // стандартное выпадающее меню выглядело чужим. Разброс большой:
                // кому-то хватит десяти минут, кому-то ехать через весь город,
                // а кому-то ровно сорок семь — для того своё время.
                MinutesRow(current = notifyBefore, onOpen = { askMinutes = true })
            }

            // У каждого вида уведомлений — свой выключатель и свой канал:
            // сбой сервера и пропажа другой группы раньше шли под
            // «изменениями» (просьба Tomon 27.09).
            SwitchRow("Сообщать об изменениях", notifyChanges, onNotifyChanges)
            if (notifyChanges && notifications && phone.changesChannelOff) {
                ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_CHANGES)
            }
            Hint("Отмены и замены на сегодня и завтра.")

            SwitchRow("Сообщать о сбоях сервера", notifyServer, onNotifyServer)
            if (notifyServer && notifications && phone.serverChannelOff) {
                ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_SERVER)
            }
            Hint("Если расписание не обновляется дольше двух часов.")

            // Только студенту с другими выбранными группами: остальным
            // этого уведомления не бывает.
            if (!teacherMode && extraGroups.isNotEmpty()) {
                SwitchRow("Сообщать о пропаже других групп", notifyGroupsGone, onNotifyGroupsGone)
                if (notifyGroupsGone && notifications && phone.subgroupChannelOff) {
                    ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_SUBGROUP)
                }
                Hint("Если одной из выбранных групп не стало в таблице.")
            }

            SwitchRow("Сообщать о новых версиях", notifyUpdates, onNotifyUpdates)
            if (notifyUpdates && notifications && phone.updateChannelOff) {
                ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_UPDATE)
            }
            // Магазина нет, обновление никто не принесёт.
            Hint("Приложение проверяет это раз в час вместе с расписанием.")
        }

        // Всё, что упирается в настройки телефона, — одним разделом сразу под
        // тем, чему оно мешает: ограничения фона и пояс стояли в
        // «Уведомлениях», хотя режут и виджеты, а шаги для марки — выше
        // выключателей, которые трогают чаще (разбор 28.09).
        BackgroundWork(phone, exactAlarms, notifyEnabled)

        Section("Расписание") {
            Text(
                // Четыре срока и две машины, из которых человеку нечего выбрать:
                // в разделе настроек это рассказ мимо дела.
                // «Перед парой чаще» — это про сервер: телефон ходит за
                // расписанием раз в час и при открытии.
                // Дня нет в ответе (воскресенье, каникулы) — ссылка ведёт на
                // ближайший следующий; у преподавателя своей колонки нет, берётся
                // колонка группы первой пары, а без пар — только строка дня
                // (SheetLink).
                "Телефон забирает его раз в час и при каждом открытии, сервер " +
                    "читает таблицу колледжа чаще и перед каждой парой. Кнопка ниже " +
                    (if (teacherMode) "откроет таблицу на сегодняшнем дне, а если его в таблице " +
                        "нет — на ближайшем следующем; в день с парами — у колонки группы первой из них."
                    else "откроет таблицу на вашей колонке и сегодняшнем дне, а если его в таблице " +
                        "нет — на ближайшем следующем."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Постоянное место для ссылки. Раньше она всплывала только при
            // сбое или пустых днях — а «на всякий случай» её хотят и в
            // обычный день: сверить, показать, посмотреть соседей.
            val context = LocalContext.current
            ActionButton(
                label = "Открыть таблицу колледжа",
                modifier = Modifier.fillMaxWidth(),
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

        Section("Оформление") {
            // «Как в системе» в треть строки не влезало.
            Segmented(
                options = listOf(
                    "Системная" to ThemeChoice.SYSTEM,
                    "Тёмная" to ThemeChoice.DARK,
                    "Светлая" to ThemeChoice.LIGHT,
                ),
                selected = theme,
                onPick = onTheme,
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
                    // Имя — как у этого приложения: у tested оно своё (М64).
                    val name = LocalContext.current.getString(ru.whensclass.R.string.app_name)
                    Text(
                        "В первый раз Android попросит разрешить «$name» установку " +
                            "приложений: так она ставит обновление самой себе. Другие " +
                            "приложения она не ставит.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (remember { Vendor.current() } == Vendor.SAMSUNG) {
                    // С One UI 6.1.1 «Автоблокировка» запрещает установку из
                    // файлов, и обновление молча не вставало (четвёртый аудит,
                    // В24 прогона 1).
                    Text(
                        "Samsung может не дать поставить: тогда выключите на время " +
                            "«Настройки» → «Безопасность и конфиденциальность» → «Автоблокировка».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                ActionButton(
                    label = if (installing) "Скачивается…" else "Обновить приложение",
                    onClick = onUpdate,
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Два текста, которые читают один раз, — одной карточкой (Tomon 28.09).
        Section("Данные и ответственность") {
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
                    "На сервер уходят только названия вашей группы и других, " +
                        "если вы их выбрали. Больше ничего: ни имени, " +
                        "ни номера телефона, ни местоположения. Учётной записи нет, " +
                        "аналитики и рекламы нет. Когда смотрите чужое расписание, " +
                        "серверу уходит, чьё именно: иначе его неоткуда взять. " +
                        "Всё для вашего удобства."
                } + "\n\n" +
                    // GitHub — запасной путь к новой версии, когда сервер не
                    // отвечает: он видит адрес телефона (четвёртый аудит, М81).
                    "Когда сервер не отвечает, приложение спрашивает у GitHub, не вышла ли новая " +
                    "версия, и берёт её оттуда: GitHub видит адрес телефона.\n\n" +
                    "Что написано в таблице колледжа, то и покажет приложение: за " +
                    "ошибки, замены и опоздавшие обновления автор не отвечает.\n\n" +
                    "Если однажды что-то сломается, автор постарается починить, но " +
                    "сроков не обещает. Пропущенная пара остаётся на вашей совести, " +
                    "даже если приложение в этот момент показывало неправильно. " +
                    "Сверяйтесь с таблицей, когда это важно.",
                // Тем же приглушённым, что и остальные пояснения: белый текст
                // читался как что-то важнее прочего.
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("О приложении") {
            Text(
                "Неофициальное приложение для студентов и преподавателей НГОК.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Ссылки — в том же порядке, что в «О сайте». «Я преподаватель»
            // отсюда убрано: тот же переход — первой строкой списка, куда
            // ведёт «Сменить» (Tomon 28.09).
            Link("Сайт для айфона и браузера", "https://kogda-para-nsk.ru")
            Link("Нашли ошибку? Напишите автору в Telegram", "https://t.me/toomonn")
            ReportLink(loadDiagnostics, modifier = Modifier.fillMaxWidth())
            Link("Исходный код", "https://github.com/tomon-one/kogda-para")
            Link("GitHub автора", "https://github.com/tomon-one")
            ShareLink(modifier = Modifier.fillMaxWidth())
            Text(
                "Создано Tomon",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Собрано задолго до рассвета",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Самый широкий значок группы у названия в разделе «Группа». */
private val MARK_IN_ROW = 88.dp

/** Строка группы в разделе «Группа»: название, пометка и одна кнопка. */
@Composable
private fun GroupRow(
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
        // а значок во всю строку сжимал его до столбика (Tomon 28.09).
        // Чтецу значок не нужен: название рядом, а «слэш два» перед ним — шум (М20).
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
            // чтец не говорил (М20).
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
                    "Лаунчер не дал добавить виджет — добавьте его из списка виджетов",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    Section("Виджеты") {
        Hint("Добавить на домашний экран — лаунчер спросит, куда поставить.")
        // Три кнопки одним рядом, одной ширины и высоты (Tomon 28.09, 29.09):
        // «Ближайшая пара» на узком экране мельчает, а не растягивает ряд.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tile = Modifier.weight(1f)
            ActionButton(label = "День", onClick = { pin(ScheduleWidgetReceiver::class.java) }, modifier = tile, side = 6.dp)
            ActionButton(label = "Неделя", onClick = { pin(WeekWidgetReceiver::class.java) }, modifier = tile, side = 6.dp)
            ActionButton(
                label = "Ближайшая пара",
                onClick = { pin(NextLessonWidgetReceiver::class.java) },
                modifier = tile,
                side = 6.dp,
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
private fun BackgroundWork(phone: ru.whensclass.notify.PhoneState, exactAlarms: Boolean, reminders: Boolean) {
    val vendor = remember { Vendor.current() }
    val steps = remember(vendor) { vendor?.let { Background.steps(it) }.orEmpty() }
    // Строка «Точное время» — только там, где его выдаёт человек: на Android 12.
    // С 13-го приложение получает его при установке (USE_EXACT_ALARM, В22), и
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
                // марки с двумя приложениями человек включал основное (М64).
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
        // выключателей уведомлений оно рвало их ряд (просьба Tomon 27.09).
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
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
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
    // свойства приложения, как у шагов марки: раньше нажатие молчало (В22).
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
private fun ChannelOff(channel: String) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            // Названием канала, как его покажет телефон: «Другие группы
            // выключены» читалось как «выключены группы» (разбор 28.09).
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
