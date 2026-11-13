package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.selection.toggleable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.data.GroupDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.widget.plural

/**
 * Расписание преподавателя. Сервер собирает его, переворачивая расписание групп, поэтому у каждой
 * пары написано, каким группам она читается.
 */
@Composable
fun TeacherScreen(
    teachers: List<GroupDto>?,
    loadSchedule: suspend (String) -> ru.whensclass.data.ScheduleAnswer,
    pinned: List<String> = emptyList(),
    onTogglePin: (String) -> Unit = {},
    reloadKey: Int = 0,
    ownSchedule: ScheduleDto? = null,
    /** Часы экрана по звонкам [ownSchedule]. */
    now: java.time.LocalDateTime = rememberNow(),
    searchLabel: String = "Поиск по фамилии",
    /** Что за список — для «не загрузился»: экран служит и списком групп. */
    listName: String = "Список преподавателей",
    selfId: String? = null,
    startDay: String? = null,
    startKey: Int = 0,
    ownScheduleTitle: String = "Посмотреть других преподавателей",
    /** Открыть этого преподавателя ([openKey] — новое нажатие, а не поворот). */
    openId: String? = null,
    openKey: Int = 0,
    /** Вкладку открыли нажатием на неё: открытый из «Пересдач» уступает тому, что было на ней. */
    enterKey: Int = 0,
    /** Открытого из «Пересдач» закрыли («К списку», «назад») — туда и вернуться, как на сайте. */
    onLeaveOpened: () -> Unit = {},
    /** Сервер больше не обслуживает эту сборку — «проверьте интернет» было бы неправдой. */
    unsupported: Boolean = false,
    /**
     * Плашки сбоя, когда шапке тесно (альбомная): строкой списка дней над днём открытия, а без
     * списка — над экраном. Над вкладками они отняли бы у списка всё место.
     */
    plates: (@Composable () -> Unit)? = null,
    othersTitle: String = "Другие преподаватели",
    // Дно списка — без утверждений про колледж: листы преподавателей в книге есть, скрытые и с
    // именами длиннее 31 символа, и сервер их не читает.
    endNote: (Int) -> String = { n ->
        "Всё. " +
            plural(n, "преподаватель", "преподавателя", "преподавателей") + "."
    },
) {
    // В роли преподавателя своё расписание уже на телефоне — сразу, без сети; список остальных — по
    // кнопке. Выбор и поиск переживают поворот.
    var browsing by rememberSaveable { mutableStateOf(false) }
    // Нажали на свой день виджета или своё уведомление — к своему расписанию, а не к открытому
    // коллеге. Только на новое нажатие.
    var handledKey by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(startKey) }
    LaunchedEffect(startKey) {
        if (startKey != handledKey) {
            handledKey = startKey
            if (ownSchedule != null) browsing = false
        }
    }
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedName by rememberSaveable { mutableStateOf("") }
    // С нуля, а не с нынешнего [openKey]: вкладку открывает само нажатие на имя, и ключ при первом
    // входе уже новый — его надо исполнить.
    var handledOpen by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    // Открытый из «Пересдач» — не память вкладки: нажатие на саму вкладку возвращает то, что было
    // на ней (как на сайте).
    var viaOpen by rememberSaveable { mutableStateOf(false) }
    var beforeId by rememberSaveable { mutableStateOf<String?>(null) }
    var beforeName by rememberSaveable { mutableStateOf("") }
    var beforeBrowsing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openKey) {
        if (openKey != handledOpen && openId != null) {
            handledOpen = openKey
            if (!viaOpen) {
                beforeId = pickedId
                beforeName = pickedName
                beforeBrowsing = browsing
            }
            viaOpen = true
            browsing = true
            pickedId = openId
            pickedName = teachers?.firstOrNull { it.id == openId }?.name.orEmpty()
        }
    }
    var handledEnter by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(enterKey) }
    LaunchedEffect(enterKey) {
        if (enterKey != handledEnter) {
            handledEnter = enterKey
            if (viaOpen) {
                viaOpen = false
                pickedId = beforeId
                pickedName = beforeName
                browsing = beforeBrowsing
            }
        }
    }
    if (ownSchedule != null && !browsing) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScheduleDays(
                schedule = ownSchedule,
                today = now.toLocalDate(),
                clock = now,
                modifier = Modifier.weight(1f),
                startDay = startDay,
                startKey = startKey,
                weekStrip = true,
                underHead = true,
                header = plates,
            )
            TextButton(
                onClick = { browsing = true },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(ownScheduleTitle, maxLines = 1, autoSize = FIT)
            }
        }
        return
    }

    val picked = pickedId?.let { GroupDto(it, pickedName) }
    fun pick(teacher: GroupDto?) {
        viaOpen = false
        pickedId = teacher?.id
        pickedName = teacher?.name.orEmpty()
    }
    // Прочитанное переживает поворот и смену вкладки: без сети иначе вместо него «Расписание не загрузилось».
    var schedule by rememberSaveable(stateSaver = ScheduleSaver) { mutableStateOf<ScheduleDto?>(null) }
    // Чьё расписание в [schedule]: у другого открытого — не показывать.
    var loadedFor by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // Чем ответил сервер на последнюю неудачу; null — не ответил.
    var failedCode by remember { mutableStateOf<Int?>(null) }
    // ⟳ не дошёл до сервера — на экране прежнее, как на сайте.
    var reloadFailed by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // Открытый из «Пересдач» закрывают — вкладка возвращает своё, а человек — в «Пересдачи».
    fun close() {
        if (viaOpen) {
            viaOpen = false
            pickedId = beforeId
            pickedName = beforeName
            browsing = beforeBrowsing
            schedule = null
            onLeaveOpened()
        } else {
            pick(null)
            schedule = null
        }
    }

    // Системное «назад» — к списку и к своему расписанию, как кнопки на экране.
    BackHandler(enabled = picked != null) { close() }
    BackHandler(enabled = picked == null && ownSchedule != null && browsing) { browsing = false }

    // reloadKey меняется по обновлению: перечитываем расписание открытого сейчас преподавателя.
    LaunchedEffect(pickedId, reloadKey) {
        val teacher = picked ?: return@LaunchedEffect
        loading = true
        val answer = loadSchedule(teacher.id)
        val fresh = answer.body
        failedCode = answer.code
        // Не загрузилось у того же — прежнее остаётся: человек его читал.
        val same = loadedFor == teacher.id && schedule != null
        reloadFailed = fresh == null && same
        if (fresh != null || !same) {
            schedule = fresh
            loadedFor = teacher.id
        }
        loading = false
    }

    val chosen = picked
    if (chosen != null) {
        ChosenTeacher(
            teacher = chosen,
            schedule = schedule.takeIf { loadedFor == chosen.id },
            loading = loading,
            reloadFailed = reloadFailed && !loading,
            failedCode = failedCode,
            unsupported = unsupported,
            teacherList = listName == "Список преподавателей",
            plates = plates,
            today = now.toLocalDate(),
            onBack = { close() },
        )
        return
    }

    if (ownSchedule != null) {
        TextButton(onClick = { browsing = false }, modifier = Modifier.padding(start = 8.dp)) {
            Text("Вернуться к своему расписанию", maxLines = 1, autoSize = FIT)
        }
    }

    // Список отступает от клавиатуры.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        plates?.invoke()
        SearchField(query, { query = it }, searchLabel, Modifier.padding(horizontal = 16.dp))

        val list = teachers
        when {
            list == null -> Centered { CircularProgressIndicator() }

            list.isEmpty() -> Centered {
                Text(
                    "$listName не загрузился. Проверьте интернет и нажмите ⟳ вверху.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> {
                val found = remember(list, query) {
                    if (query.isBlank()) list
                    else list.filter { matchesQuery(it.name, query) }
                }
                // Закреплённые — отдельной группой сверху: видно, что закреплены, и при снятии не
                // перелетают через весь список. Сам человек — отдельной строкой над всеми.
                val self = remember(found, selfId) { found.firstOrNull { it.id == selfId } }
                val favourites = remember(found, pinned, selfId) {
                    found.filter { it.id in pinned && it.id != selfId }
                }
                val others = remember(found, pinned, selfId) {
                    found.filter { it.id !in pinned && it.id != selfId }
                }

                // Без общего зазора: строки одной буквы — одна карточка ([lettered]); отдельные сверху отступают сами.
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                ) {
                    self?.let { own ->
                        item(key = "self-title") { SectionTitle("Ваше расписание") }
                        item(key = "self") {
                            TeacherRow(
                                modifier = Modifier.padding(bottom = 6.dp),
                                teacher = own,
                                pinned = own.id in pinned,
                                isSelf = true,
                                onOpen = { pick(own) },
                                onTogglePin = { onTogglePin(own.id) },
                            )
                        }
                    }
                    if (favourites.isNotEmpty()) {
                        item(key = "pinned") { SectionTitle("Закреплённые") }
                        items(
                            favourites,
                            key = { "p-${it.id}" },
                            contentType = { "teacher" },
                        ) { teacher ->
                            TeacherRow(
                                modifier = Modifier.padding(bottom = 6.dp),
                                teacher = teacher,
                                pinned = true,
                                isSelf = teacher.id == selfId,
                                onOpen = { pick(teacher) },
                                onTogglePin = { onTogglePin(teacher.id) },
                            )
                        }
                    }
                    if (found.isEmpty()) {
                        // Пустой экран после поиска читается как поломка.
                        item(key = "пусто") {
                            Text(
                                "Ничего не нашлось",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    if (others.isNotEmpty() && (self != null || favourites.isNotEmpty())) {
                        item(key = "others") { SectionTitle(othersTitle) }
                    }
                    // По буквам, как в «Найдите себя»: одна карточка на букву.
                    lettered(others, name = { it.name }, key = { it.id }, onPick = { pick(it) }) { teacher ->
                        TeacherRow(
                            teacher = teacher,
                            pinned = false,
                            isSelf = teacher.id == selfId,
                            onOpen = { pick(teacher) },
                            onTogglePin = { onTogglePin(teacher.id) },
                            inCard = true,
                        )
                    }
                    if (query.isBlank()) {
                        item(key = "конец") { ListEnd(endNote(list.size)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChosenTeacher(
    teacher: GroupDto,
    schedule: ScheduleDto?,
    loading: Boolean,
    reloadFailed: Boolean,
    failedCode: Int?,
    unsupported: Boolean,
    teacherList: Boolean,
    plates: (@Composable () -> Unit)?,
    today: java.time.LocalDate,
    onBack: () -> Unit,
) {
    // Палец на строке с именем листает список, как с полоски: в альбомной строка и полоска — почти
    // весь экран над парами.
    val dragSink = remember { mutableStateOf<((Float) -> Unit)?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        if (schedule == null || schedule.days.isEmpty()) plates?.invoke()
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp)
                .draggable(
                    androidx.compose.foundation.gestures.rememberDraggableState { dragSink.value?.invoke(it) },
                    androidx.compose.foundation.gestures.Orientation.Vertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Открыт из «Пересдач», а списка преподавателей ещё нет — имя из ответа, как на сайте.
                teacher.name.ifEmpty { schedule?.groupName.orEmpty() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("К списку", maxLines = 1, softWrap = false) }
        }

        if (reloadFailed && schedule != null) {
            Text(
                "Не обновилось — на экране прежнее.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        when {
            // Обновление открытого — без крутилки на месте пар: прокрутка и прочитанное остаются, пока не пришло новое.
            loading && schedule == null -> Centered { CircularProgressIndicator() }
            schedule == null -> Centered {
                Text(
                    when {
                        // Сервер ответил: такого нет — ссылка или имя устарели.
                        failedCode == 404 && teacherList ->
                            "Такого преподавателя нет в таблице. Найдите его в списке."
                        failedCode == 404 -> "Такой группы нет в таблице. Найдите её в списке."
                        unsupported || failedCode != null ->
                            "Расписание не загрузилось. " + failureText(true, unsupported, "его")
                        else -> "Расписание не загрузилось. Проверьте интернет и нажмите ⟳ вверху."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Дней нет — не пустой экран, а объяснение с выходом к таблице, как у своего расписания.
            else -> if (!explainMissing(schedule, today, null)) {
                ScheduleDays(
                    schedule = schedule,
                    today = today,
                    weekStrip = true,
                    header = plates,
                    dragSink = dragSink,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun TeacherRow(
    teacher: GroupDto,
    pinned: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
    isSelf: Boolean = false,
    /** Строка внутри общей карточки буквы ([lettered]): своей подложки нет. */
    inCard: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (inCard) {
            modifier.fillMaxWidth()
        } else {
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
        },
    ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(teacher.name, style = MaterialTheme.typography.bodyLarge)
                // Себя человек ищет в списке первым делом — отмечаем.
                if (isSelf) {
                    Text(
                        "это вы",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Экранный чтец: что делает звёздочка и в каком она положении — символ «★» не объясняет.
            Text(
                if (pinned) "★" else "☆",
                style = MaterialTheme.typography.titleMedium,
                color = if (pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .toggleable(value = pinned, role = Role.Checkbox, onValueChange = { onTogglePin() })
                    .semantics {
                        contentDescription = "Закрепить наверху списка"
                        stateDescription = if (pinned) "закреплён" else "не закреплён"
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

private val savedJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Открытое расписание — в сохраняемом состоянии экрана строкой JSON. */
private val ScheduleSaver = androidx.compose.runtime.saveable.Saver<ScheduleDto?, String>(
    save = { it?.let { schedule -> savedJson.encodeToString(ScheduleDto.serializer(), schedule) } },
    restore = { runCatching { savedJson.decodeFromString(ScheduleDto.serializer(), it) }.getOrNull() },
)
