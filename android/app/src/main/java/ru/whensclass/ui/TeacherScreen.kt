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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import java.time.LocalDate
import ru.whensclass.data.GroupDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.widget.plural

/**
 * Расписание преподавателя. Сервер собирает его, переворачивая расписание
 * групп, поэтому у каждой пары написано, каким группам она читается.
 */
@Composable
fun TeacherScreen(
    teachers: List<GroupDto>?,
    loadSchedule: suspend (String) -> ScheduleDto?,
    pinned: List<String> = emptyList(),
    onTogglePin: (String) -> Unit = {},
    reloadKey: Int = 0,
    ownSchedule: ScheduleDto? = null,
    searchLabel: String = "Поиск по фамилии",
    /** Что за список — для «не загрузился»: экран служит и списком групп. */
    listName: String = "Список преподавателей",
    selfId: String? = null,
    startDay: String? = null,
    startKey: Int = 0,
    ownScheduleTitle: String = "Посмотреть других преподавателей",
    othersTitle: String = "Другие преподаватели",
    // Дно списка — без утверждений про колледж: листы преподавателей в книге
    // есть, скрытые и с именами длиннее 31 символа, и сервер их не читает.
    endNote: (Int) -> String = { n ->
        "Всё. " +
            plural(n, "преподаватель", "преподавателя", "преподавателей") + "."
    },
) {
    // В роли преподавателя своё расписание уже на телефоне — сразу, без сети;
    // список остальных — по кнопке. Выбор и поиск переживают поворот экрана.
    var browsing by rememberSaveable { mutableStateOf(false) }
    // Нажали на свой день виджета или своё уведомление — к своему расписанию,
    // а не к открытому коллеге. Только на новое нажатие, не на поворот.
    var handledKey by rememberSaveable { mutableStateOf(startKey) }
    LaunchedEffect(startKey) {
        if (startKey != handledKey) {
            handledKey = startKey
            if (ownSchedule != null) browsing = false
        }
    }
    if (ownSchedule != null && !browsing) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScheduleDays(
                schedule = ownSchedule,
                today = rememberToday(),
                modifier = Modifier.weight(1f),
                startDay = startDay,
                startKey = startKey,
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

    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedName by rememberSaveable { mutableStateOf("") }
    val picked = pickedId?.let { GroupDto(it, pickedName) }
    fun pick(teacher: GroupDto?) {
        pickedId = teacher?.id
        pickedName = teacher?.name.orEmpty()
    }
    var schedule by remember { mutableStateOf<ScheduleDto?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // Системное «назад» — к списку и к своему расписанию, как кнопки на экране.
    BackHandler(enabled = picked != null) {
        pick(null)
        schedule = null
    }
    BackHandler(enabled = picked == null && ownSchedule != null && browsing) { browsing = false }

    // reloadKey меняется по нажатию на обновление: перечитываем расписание
    // того преподавателя, который сейчас открыт.
    LaunchedEffect(pickedId, reloadKey) {
        val teacher = picked ?: return@LaunchedEffect
        loading = true
        schedule = loadSchedule(teacher.id)
        loading = false
    }

    val chosen = picked
    if (chosen != null) {
        ChosenTeacher(
            teacher = chosen,
            schedule = schedule,
            loading = loading,
            onBack = {
                pick(null)
                schedule = null
            },
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
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(searchLabel) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

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
                // Закреплённые — отдельной группой сверху: так видно, что они
                // закреплённые, и при снятии они не перелетают через весь список.
                // Сам человек — отдельной строкой над всеми.
                val self = remember(found, selfId) { found.firstOrNull { it.id == selfId } }
                val favourites = remember(found, pinned, selfId) {
                    found.filter { it.id in pinned && it.id != selfId }
                }
                val others = remember(found, pinned, selfId) {
                    found.filter { it.id !in pinned && it.id != selfId }
                }

                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    self?.let { own ->
                        item(key = "self-title") { SectionTitle("Ваше расписание") }
                        item(key = "self") {
                            TeacherRow(
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
                    items(others, key = { it.id }, contentType = { "teacher" }) { teacher ->
                        TeacherRow(
                            teacher = teacher,
                            pinned = false,
                            isSelf = teacher.id == selfId,
                            onOpen = { pick(teacher) },
                            onTogglePin = { onTogglePin(teacher.id) },
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
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                teacher.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) { Text("К списку", maxLines = 1, softWrap = false) }
        }

        when {
            loading -> Centered { CircularProgressIndicator() }
            schedule == null -> Centered {
                Text(
                    "Расписание не загрузилось. Проверьте интернет и нажмите ⟳ вверху.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Дней нет — не пустой экран, а объяснение с выходом к таблице,
            // как у своего расписания.
            else -> if (!explainMissing(schedule, rememberToday(), null)) {
                ScheduleDays(
                    schedule = schedule,
                    today = rememberToday(),
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
    isSelf: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
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
            // Экранный чтец: что делает звёздочка и в каком она положении —
            // символ «★» он не объясняет.
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
