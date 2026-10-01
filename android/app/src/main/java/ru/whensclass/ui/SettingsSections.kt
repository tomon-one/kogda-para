package ru.whensclass.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.whensclass.BuildConfig
import ru.whensclass.data.ReleaseDto
import ru.whensclass.notify.Vendor

/** Раздел «Группа» или «Преподаватель»: своя, другие группы и подгруппы. */
@Composable
internal fun GroupSection(
    teacherMode: Boolean,
    groupName: String?,
    extraGroups: List<ru.whensclass.data.ExtraGroup>,
    subgroups: List<ru.whensclass.data.GroupDto>,
    groupsByName: Boolean,
    onGroupsByName: (Boolean) -> Unit,
    onChangeGroup: () -> Unit,
    onAddGroup: () -> Unit,
    onAddSubgroup: (ru.whensclass.data.GroupDto) -> Unit,
    onRemoveGroup: (String) -> Unit,
) {
    Section(if (teacherMode) "Преподаватель" else "Группа") {
        // Название и кнопка одной строкой: столбиком раздел выходил
        // вдвое выше, а читается так же. Кнопки раздела — одной ширины.
        // С другими группами у каждой — её значок, как под временем пар:
        // номер или короткое название.
        val marks = if (teacherMode || extraGroups.isEmpty()) null
        else if (groupsByName) shortLabels(listOf(groupName.orEmpty()) + extraGroups.map { it.name })
        else (1..extraGroups.size + 1).map { it.toString() }
        // Значок, повторяющий название целиком, только сжимал его, а чтецу он
        // не нужен — как у строк других групп.
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
            // Остальные группы — любые, до шести вместе со своей. Значок —
            // тот же, что под временем пар.
            // С ключом: без него после «Убрать» или «Добавить» строка
            // следующей группы вставала на место ушедшей и забирала её
            // подсветку нажатия.
            extraGroups.forEachIndexed { index, group ->
                key(group.id) {
                    GroupRow(
                        name = group.name + if (group.gone) " — нет в таблице" else "",
                        // Значок, повторяющий название целиком (не подгруппа
                        // своей), только сжимал само название в столбик.
                        mark = marks?.getOrNull(index + 1)?.takeIf { it != group.name },
                        action = "Убрать",
                        onAction = { onRemoveGroup(group.id) },
                    )
                }
            }
            // Подгруппы своей группы — строками с «Добавить», как у
            // добавленных «Убрать»: две кнопки рядом выходили разной
            // высоты.
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
                // Названия или номера под временем пар.
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
                // Что значат значки — здесь, где их выбирают.
                Hint(
                    "Под часами пары — группы, у которых она есть: ваша закрашена, " +
                        "остальные более бледные. Пары, которых у вашей группы нет, — на сером фоне. " +
                        "Виджеты, напоминания и уведомления об изменениях — только о вашей группе.",
                )
            }
        }
    }
}

/** Раздел «Уведомления»: выключатель и канал у каждого вида. */
@Composable
internal fun NotificationsSection(
    teacherMode: Boolean,
    extraGroups: List<ru.whensclass.data.ExtraGroup>,
    notifications: Boolean,
    phone: ru.whensclass.notify.PhoneState,
    notifyBefore: Int,
    onAskMinutes: () -> Unit,
    notifyEnabled: Boolean,
    onNotifyEnabled: (Boolean) -> Unit,
    notifyChanges: Boolean,
    onNotifyChanges: (Boolean) -> Unit,
    notifyServer: Boolean,
    onNotifyServer: (Boolean) -> Unit,
    notifyGroupsGone: Boolean,
    onNotifyGroupsGone: (Boolean) -> Unit,
    notifyUpdates: Boolean,
    onNotifyUpdates: (Boolean) -> Unit,
) {
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
            // Строка с кнопкой, выбор — в своём окне: одиннадцать
            // фишек прямо в карточке занимали полэкрана, а
            // стандартное выпадающее меню выглядело чужим. Разброс большой:
            // кому-то хватит десяти минут, кому-то ехать через весь город,
            // а кому-то ровно сорок семь — для того своё время.
            MinutesRow(current = notifyBefore, onOpen = onAskMinutes)
        }

        // У каждого вида уведомлений — свой выключатель и свой канал:
        // сбой сервера и пропажа другой группы раньше шли под
        // «изменениями».
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
}

/** Раздел «Версия»: установленная, вышедшая и проверка обновлений. */
@Composable
internal fun VersionSection(
    update: ReleaseDto?,
    phone: ru.whensclass.notify.PhoneState,
    installing: Boolean,
    updateError: String?,
    onUpdate: () -> Unit,
    checkingUpdate: Boolean,
    updateChecked: Boolean,
    updateFailed: Boolean,
    onCheckUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Section(
        // Установленная версия — сам заголовок блока: строкой ниже она
        // повторяла то, что и так написано сверху.
        "Версия ${BuildConfig.VERSION_NAME}",
        modifier = modifier,
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
                // Имя — как у этого приложения: у tested оно своё.
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
                // файлов, и обновление молча не вставало.
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
}

/** Разделы «Данные и ответственность» и «О приложении». */
@Composable
internal fun AboutSections(teacherMode: Boolean, loadDiagnostics: suspend () -> String) {
    // Два текста, которые читают один раз, — одной карточкой.
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
                // отвечает: он видит адрес телефона.
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
        // ведёт «Сменить».
        Link("Сайт для айфона и браузера", "https://kogda-para-nsk.ru")
        Link("Нашли ошибку? Напишите автору в Telegram", "https://t.me/toomonn")
        ReportLink(loadDiagnostics, modifier = Modifier.fillMaxWidth(), asLink = true)
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
