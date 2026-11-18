package ru.whensclass.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.whensclass.BuildConfig
import ru.whensclass.data.ReleaseDto
import ru.whensclass.notify.Vendor

/** Одна строка списка групп: добавленная группа («Убрать») или подгруппа своей («Добавить»). */
private data class GroupLine(
    val id: String,
    val name: String,
    val action: String,
    val onAction: () -> Unit,
    val mark: String? = null,
)

/** Раздел «Группы и подгруппы» или «Преподаватель»: своя, другие группы и подгруппы. */
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
    Section(
        if (teacherMode) "Преподаватель" else "Группы и подгруппы",
        // Строки добавляются и пропадают по одной: высота карточки едет за ними, а не прыгает.
        modifier = Modifier.animateContentSize(),
    ) {
        // Название и кнопка одной строкой; кнопки раздела одной ширины. С другими группами у каждой
        // её значок, как под временем пар.
        val marks = if (teacherMode || extraGroups.isEmpty()) null
        else if (groupsByName) shortLabels(listOf(groupName.orEmpty()) + extraGroups.map { it.name })
        else (1..extraGroups.size + 1).map { it.toString() }
        // Значок, повторяющий название целиком, только сжимает его; чтецу он не нужен.
        val ownMark = marks?.getOrNull(0)?.takeIf { it != groupName }
        // Слот под значок — по самому широкому значку списка и один на все строки: названия стоят
        // на одной линии, а на узком экране место под них не тратится зря.
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val markSlot = remember(marks, density) {
            val widest = marks?.maxOfOrNull { label ->
                measurer.measure(
                    AnnotatedString(label),
                    TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                ).size.width
            }
            if (widest == null) 0.dp else with(density) {
                maxOf(widest.toDp() + 8.dp, 18.dp * fontScale.coerceAtLeast(1f))
                    .coerceAtMost(MARK_IN_ROW) + 10.dp
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (marks != null) {
                Box(Modifier.width(markSlot)) {
                    if (ownMark != null) {
                        GroupMark(ownMark, own = true, modifier = Modifier.widthIn(max = MARK_IN_ROW).clearAndSetSemantics {})
                    }
                }
            }
            // Одна строка: длинное имя не переносится и не двигает строки под собой.
            Text(
                groupName ?: "не выбрано",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            // Группа из таблицы: не нашлась, переименовали — здесь и выбирают заново.
            ActionButton(
                label = if (teacherMode) "Выбрать заново" else "Сменить",
                onClick = onChangeGroup,
                modifier = Modifier.width(ROW_BUTTON),
                top = 0.dp,
                side = 8.dp,
            )
        }

        if (!teacherMode) {
            // Остальные группы и подгруппы — одним списком с ключом по id: добавленная подгруппа
            // остаётся в своей строке и меняет «Добавить» на «Убрать», а не пропадает и не
            // появляется заново. Ключ нужен и после «Убрать»: иначе следующая строка забирает
            // подсветку нажатия ушедшей.
            val lines = buildList {
                // До [MAX_GROUPS] вместе со своей.
                extraGroups.forEachIndexed { index, group ->
                    add(
                        GroupLine(
                            id = group.id,
                            name = group.name + if (group.gone) " — нет в таблице" else "",
                            mark = marks?.getOrNull(index + 1)?.takeIf { it != group.name },
                            action = "Убрать",
                            onAction = { onRemoveGroup(group.id) },
                        ),
                    )
                }
                subgroups.forEach { group ->
                    add(
                        GroupLine(
                            id = group.id,
                            name = group.name,
                            action = "Добавить",
                            onAction = { onAddSubgroup(group) },
                        ),
                    )
                }
            }
            lines.forEach { line ->
                key(line.id) {
                    Appearing {
                        GroupRow(
                            name = line.name,
                            mark = line.mark,
                            action = line.action,
                            onAction = line.onAction,
                            slot = markSlot,
                        )
                    }
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
        // Друг о друге узнают только так: магазина нет.
        ShareLink(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Строка группы появляется не рывком: карточка выросла бы сразу на её высоту, а соседние названия
 * сдвинулись бы одним кадром.
 */
@Composable
private fun Appearing(content: @Composable () -> Unit) {
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn(tween(180)) + expandVertically(tween(220)),
    ) { content() }
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
    /** Вечерняя сводка: минута дня, 0 — выключена. */
    digestMinutes: Int = 0,
    onDigest: (Boolean) -> Unit = {},
    onAskDigest: () -> Unit = {},
) {
    Section("Уведомления") {
        NotificationsDenied(notifications)
        // Напоминаем не о каждой паре — сказать заранее, чтобы это не приняли за поломку. Пояснение
        // видно и у выключенного: иначе непонятно, что включает тумблер.
        SwitchRow(
            "Напоминать о паре", notifyEnabled, onNotifyEnabled,
            hint = "Напоминание о первой паре дня или между парами.",
        )
        if (notifyEnabled && notifications && phone.lessonChannelOff) {
            ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_LESSON)
        }
        // Строка с кнопкой, выбор в своём окне: фишки в карточке занимали бы полэкрана. Появляется
        // вместе с тумблером раскрытием, а не рывком.
        AnimatedVisibility(
            visible = notifyEnabled,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(220)),
        ) {
            MinutesRow(current = notifyBefore, onOpen = onAskMinutes)
        }

        // У каждого вида уведомлений — свой выключатель и свой канал. Порядок — от частого к редкому.
        // Вечерняя сводка: первая пара завтрашнего дня, по умолчанию выключена.
        SwitchRow(
            "Вечерняя сводка", digestMinutes > 0, onDigest,
            hint = "Первая пара завтрашнего дня.",
        )
        if (digestMinutes > 0 && notifications && phone.digestChannelOff) {
            ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_DIGEST)
        }
        AnimatedVisibility(
            visible = digestMinutes > 0,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(220)),
        ) {
            DigestRow(current = digestMinutes, onOpen = onAskDigest)
        }

        SwitchRow(
            "Сообщать об изменениях", notifyChanges, onNotifyChanges,
            hint = "Отмены и замены на сегодня и завтра.",
        )
        if (notifyChanges && notifications && phone.changesChannelOff) {
            ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_CHANGES)
        }

        // Только студенту с другими выбранными группами: остальным его не бывает.
        if (!teacherMode && extraGroups.isNotEmpty()) {
            SwitchRow(
                "Сообщать о пропаже других групп", notifyGroupsGone, onNotifyGroupsGone,
                hint = "Если одной из выбранных групп не стало в таблице.",
            )
            if (notifyGroupsGone && notifications && phone.subgroupChannelOff) {
                ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_SUBGROUP)
            }
        }

        // Магазина нет, обновление никто не принесёт.
        SwitchRow(
            "Сообщать о новых версиях", notifyUpdates, onNotifyUpdates,
            hint = "Приложение проверяет наличие обновлений раз в час.",
        )
        if (notifyUpdates && notifications && phone.updateChannelOff) {
            ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_UPDATE)
        }

        SwitchRow(
            "Сообщать о сбоях сервера", notifyServer, onNotifyServer,
            hint = "Если расписание не обновляется дольше двух часов.",
        )
        if (notifyServer && notifications && phone.serverChannelOff) {
            ChannelOff(ru.whensclass.notify.Notifications.CHANNEL_SERVER)
        }
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
        // Установленная версия — сам заголовок блока.
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
                // Заранее: иначе системный запрос на установку из этого источника выглядит
                // подозрительно, и осторожный человек отказывает. Имя — как у этого приложения: у
                // tested своё.
                val name = androidx.compose.ui.res.stringResource(ru.whensclass.R.string.app_name)
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
                // С One UI 6.1.1 «Автоблокировка» молча запрещает установку из файлов.
                Text(
                    "Samsung может не дать поставить: тогда выключите на время " +
                        "«Настройки» → «Безопасность и конфиденциальность» → «Автоблокировка».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ActionButton(
                label = if (installing) "Обновляется…" else "Обновить приложение",
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
            // Кнопка отвечает всегда, и «не дозвонился» отдельно от «новее нет».
            if (updateChecked && !checkingUpdate) {
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
            // У преподавателя на сервер уходит не группа, а он сам.
            if (teacherMode) {
                "На сервер уходит выбранное имя, как оно записано в таблице " +
                "колледжа, и номер версии приложения. Больше ничего: " +
                    "ни номера телефона, ни местоположения. Учётной записи нет, " +
                    "аналитики и рекламы нет. Когда смотрите чужое расписание, " +
                    "серверу уходит, чьё именно: иначе его неоткуда взять. " +
                    "Всё для вашего удобства."
            } else {
                "На сервер уходят только названия вашей группы и других, " +
                    "если вы их выбрали, и номер версии приложения. Больше ничего: ни имени, " +
                    "ни номера телефона, ни местоположения. Учётной записи нет, " +
                    "аналитики и рекламы нет. Когда смотрите чужое расписание, " +
                    "серверу уходит, чьё именно: иначе его неоткуда взять. " +
                    "Всё для вашего удобства."
            } + "\n\n" +
                // GitHub — запасной путь к новой версии, когда сервер не отвечает: он видит адрес телефона.
                "Когда сервер не отвечает, приложение спрашивает у GitHub, не вышла ли новая " +
                "версия, и берёт её оттуда: GitHub видит адрес телефона.\n\n" +
                "Что написано в таблице колледжа, то и покажет приложение: за " +
                "ошибки, замены и опоздавшие обновления автор не отвечает.\n\n" +
                "Если однажды что-то сломается, автор постарается починить, но " +
                "сроков не обещает. Пропущенная пара остаётся на вашей совести, " +
                "даже если приложение в этот момент показывало неправильно. " +
                "Сверяйтесь с таблицей, когда это важно.",
            // Тем же приглушённым, что и остальные пояснения.
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

        // Ссылки — в том же порядке, что в «О сайте».
        Link("Сайт", "https://kogda-para-nsk.ru")
        Link("Новости в Telegram", CHANNEL_URL)
        Link("Новости во ВКонтакте", VK_URL)
        Link("Нашли ошибку? Напишите автору в Telegram", "https://t.me/toomonn")
        ReportLink(loadDiagnostics, modifier = Modifier.fillMaxWidth(), asLink = true)
        Link("Исходный код", "https://github.com/tomon-one/kogda-para")
        Link("GitHub автора", "https://github.com/tomon-one")
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
