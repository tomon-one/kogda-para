package ru.whensclass.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.data.ReleaseDto
import ru.whensclass.widget.ThemeChoice

/**
 * Настройки и короткий рассказ о данных. Разделы — от частого к разовому:
 * группа, виджеты, уведомления и всё, что им мешает в телефоне; дальше
 * таблица, оформление, версия, данные и «О приложении».
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
    /** Прокрутили к «Версии» — больше не нужно. */
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

    // Пришли по значку обновления — сразу прокручиваем к разделу «Версия»
    // внизу. Один раз за приход: иначе любая правка выше снова уносила бы
    // экран вниз.
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
        GroupSection(
            teacherMode = teacherMode,
            groupName = groupName,
            extraGroups = extraGroups,
            subgroups = subgroups,
            groupsByName = groupsByName,
            onGroupsByName = onGroupsByName,
            onChangeGroup = onChangeGroup,
            onAddGroup = onAddGroup,
            onAddSubgroup = onAddSubgroup,
            onRemoveGroup = onRemoveGroup,
        )

        PinWidgets()

        NotificationsSection(
            teacherMode = teacherMode,
            extraGroups = extraGroups,
            notifications = notifications,
            phone = phone,
            notifyBefore = notifyBefore,
            onAskMinutes = { askMinutes = true },
            notifyEnabled = notifyEnabled,
            onNotifyEnabled = onNotifyEnabled,
            notifyChanges = notifyChanges,
            onNotifyChanges = onNotifyChanges,
            notifyServer = notifyServer,
            onNotifyServer = onNotifyServer,
            notifyGroupsGone = notifyGroupsGone,
            onNotifyGroupsGone = onNotifyGroupsGone,
            notifyUpdates = notifyUpdates,
            onNotifyUpdates = onNotifyUpdates,
        )

        // Всё, что упирается в настройки телефона, — одним разделом сразу под
        // уведомлениями: ограничения режут и их, и виджеты.
        BackgroundWork(phone, exactAlarms, notifyEnabled)

        Section("Расписание") {
            Text(
                // «Чаще и перед каждой парой» — про сервер; телефон ходит за
                // расписанием раз в час и при открытии. Куда ведёт ссылка — см.
                // sheetLink.
                "Телефон забирает его раз в час и при каждом открытии, сервер " +
                    "читает таблицу колледжа чаще и перед каждой парой. Кнопка ниже " +
                    (if (teacherMode) "откроет таблицу на сегодняшнем дне, а если его в таблице " +
                        "нет — на ближайшем следующем; в день с парами — у колонки группы первой из них."
                    else "откроет таблицу на вашей колонке и сегодняшнем дне, а если его в таблице " +
                        "нет — на ближайшем следующем."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Постоянное место для ссылки: её хотят и в обычный день —
            // сверить, показать, посмотреть соседей.
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
            // «Как в системе» в треть строки не влезает.
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

        VersionSection(
            update = update,
            phone = phone,
            installing = installing,
            updateError = updateError,
            onUpdate = onUpdate,
            checkingUpdate = checkingUpdate,
            updateChecked = updateChecked,
            updateFailed = updateFailed,
            onCheckUpdate = onCheckUpdate,
            modifier = Modifier.onGloballyPositioned { updateOffset = it.positionInParent().y.toInt() },
        )

        AboutSections(teacherMode, loadDiagnostics)
    }
    }
}
