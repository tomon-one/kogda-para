package ru.whensclass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.whensclass.data.DayDto
import ru.whensclass.data.GroupDto
import ru.whensclass.data.LessonDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.widget.currentLessonNumber
import ru.whensclass.widget.formatDayTitle
import ru.whensclass.widget.formatFetchedAt
import ru.whensclass.widget.kindName
import ru.whensclass.widget.roomLabel
import ru.whensclass.widget.lessonTime
import ru.whensclass.widget.shortenName

/** Ширина колонки времени: «09:00–10:30» должно помещаться в одну строку. */
private val TIME_COLUMN = 92.dp

/**
 * Расписание на неделю вперёд.
 *
 * Пары выстроены таблицей: колонка времени одной ширины на все строки, иначе
 * взгляд не находит, когда начинается следующая. Строки разделены линиями во
 * всю ширину карточки — так день читается как расписание, а не как набор
 * отдельных плиток.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    startDay: String? = null,
    groupName: String,
    loadTeachers: suspend () -> List<GroupDto>,
    loadTeacherSchedule: suspend (String) -> ScheduleDto?,
    pinnedTeachers: List<String> = emptyList(),
    onTogglePinnedTeacher: (String) -> Unit = {},
    schedule: ScheduleDto?,
    fetchedAt: Long,
    hasUpdate: Boolean,
    refreshing: Boolean,
    onSettings: () -> Unit,
    onUpdateBadge: () -> Unit,
    onRefresh: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val listState = rememberLazyListState()

    // Открываемся на сегодняшнем дне — или на том, который попросил виджет.
    LaunchedEffect(schedule, startDay) {
        val target = startDay ?: today.toString()
        val index = schedule?.days?.indexOfFirst { it.date >= target } ?: -1
        if (index > 0) listState.scrollToItem(index)
    }

    var tab by remember { mutableStateOf(Tab.STUDENTS) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            groupName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            formatFetchedAt(fetchedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    RefreshButton(refreshing = refreshing, onRefresh = onRefresh)
                    IconButton(onClick = if (hasUpdate) onUpdateBadge else onSettings) {
                        // Точка над шестерёнкой: вышла новая сборка. Нажатие
                        // открывает настройки сразу на разделе обновления.
                        BadgedBox(
                            badge = {
                                if (hasUpdate) {
                                    Badge { Text("↓", style = MaterialTheme.typography.labelSmall) }
                                }
                            },
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScheduleTabs(
                current = tab,
                onPick = { picked ->
                    if (picked.ready) {
                        tab = picked
                    } else {
                        // Листы с пересдачами и экзаменами колледж публикует
                        // в свой срок; вкладки стоят, чтобы их ждали здесь.
                        scope.launch {
                            snackbar.showSnackbar("${picked.title}: скоро")
                        }
                    }
                },
            )

            when (tab) {
                Tab.TEACHERS -> {
                    TeacherScreen(
                        loadTeachers = loadTeachers,
                        loadSchedule = loadTeacherSchedule,
                        pinned = pinnedTeachers,
                        onTogglePin = onTogglePinnedTeacher,
                    )
                    return@Column
                }
                else -> Unit
            }

        if (schedule == null) {
            Column(modifier = Modifier.padding(padding).padding(24.dp)) {
                Text("Расписание ещё не загружено", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Проверьте интернет или напишите @toomonn",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        ScheduleDays(schedule = schedule, today = today, listState = listState)
        }
    }
}

/**
 * Кнопка обновления.
 *
 * Крутится, пока идёт запрос, и обязательно доводит оборот до конца: значок,
 * замерший на половине поворота, выглядит как зависший. Закончив, показывает
 * галочку — короткое «готово», которого иначе не видно, когда расписание не
 * изменилось.
 */
@Composable
private fun RefreshButton(refreshing: Boolean, onRefresh: () -> Unit) {
    val angle = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }
    val busy by rememberUpdatedState(refreshing)

    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        done = false
        do {
            angle.animateTo(angle.value + 360f, tween(700, easing = LinearEasing))
        } while (busy)
        angle.snapTo(0f)
        done = true
        delay(1200)
        done = false
    }

    IconButton(onClick = onRefresh, enabled = !refreshing) {
        Crossfade(targetState = done, label = "refresh") { showDone ->
            if (showDone) {
                Icon(Icons.Default.Check, contentDescription = "Расписание обновлено")
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Обновить расписание",
                    modifier = Modifier.rotate(angle.value),
                )
            }
        }
    }
}

/** Разделы расписания. Пересдачи и экзамены колледж публикует отдельными листами. */
enum class Tab(val title: String, val ready: Boolean) {
    STUDENTS("Студентам", true),
    TEACHERS("Преподавателям", true),
    RETAKES("Пересдачи", false),
    EXAMS("Экзамены", false),
}

@Composable
private fun ScheduleTabs(current: Tab, onPick: (Tab) -> Unit) {
    TabRow(
        selectedTabIndex = current.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
        divider = {},
    ) {
        Tab.entries.forEach { tab ->
            Tab(
                selected = tab == current,
                onClick = { onPick(tab) },
                text = {
                    Text(
                        tab.title,
                        // «Преподавателям» — длинное слово, а вкладок четыре:
                        // шрифт мельче, перенос запрещён.
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        // Неготовые разделы видно, но они приглушены.
                        color = if (tab.ready) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

/**
 * Дни расписания списком. Общий для вкладок «Студентам» и «Преподавателям»:
 * различие лишь в том, что у преподавателя вместо его имени стоит группа.
 */
@Composable
fun ScheduleDays(
    schedule: ScheduleDto,
    today: LocalDate,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    showGroups: Boolean = false,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(schedule.days, key = { it.date }) { day ->
            DayCard(day, schedule.bells, today, showGroups)
        }
    }
}

@Composable
private fun DayCard(
    day: DayDto,
    bells: Map<String, List<String>>,
    today: LocalDate,
    showGroups: Boolean = false,
) {
    val date = remember(day.date) { runCatching { LocalDate.parse(day.date) }.getOrNull() }
    val isToday = date == today
    val current = if (isToday) currentLessonNumber(bells, today) else null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            DayHeader(date?.let(::formatDayTitle) ?: day.date, isToday)

            if (day.lessons.isEmpty()) {
                Text(
                    "Пар нет",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            } else {
                day.lessons.forEachIndexed { index, lesson ->
                    // Линия во всю ширину карточки — расписание, а не плитки.
                    if (index > 0) HorizontalDivider()
                    LessonRow(lesson, bells, isNow = lesson.number == current, showGroups)
                }
            }
        }
    }
}

@Composable
private fun DayHeader(title: String, isToday: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isToday) {
            // Красная метка слева: сегодняшний день находится взглядом сразу.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text(
            title.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isToday) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                start = if (isToday) 12.dp else 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
        )
    }
}

@Composable
private fun LessonRow(
    lesson: LessonDto,
    bells: Map<String, List<String>>,
    isNow: Boolean,
    showGroups: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                else MaterialTheme.colorScheme.surface
            )
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(TIME_COLUMN)) {
            Text(
                "${lesson.number} пара",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            lessonTime(bells, lesson.number)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isNow) FontWeight.Bold else FontWeight.Medium,
                    color = if (isNow) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            if (isNow) {
                Text(
                    "идёт сейчас",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        VerticalDivider(
            modifier = Modifier.fillMaxHeight().padding(start = 12.dp, end = 12.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                lesson.subject,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else null,
            )

            Spacer(Modifier.height(4.dp))
            // Тип занятия и аудитория — то, ради чего сюда и заглядывают,
            // поэтому они идут сразу под названием и заметно, а не подписью
            // мелким шрифтом.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (lesson.url != null) {
                    Place("Онлайн")
                } else {
                    roomLabel(lesson.room)?.let { Place(it) }
                }
                kindName(lesson.kind)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            lesson.url?.let { OnlineLink(it) }

            // В расписании преподавателя его имя очевидно, зато важно, каким
            // группам читается пара.
            if (showGroups) {
                lesson.groups?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                lesson.teachers.forEach {
                    Text(
                        shortenName(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (lesson.isCancelled) {
                Text(
                    lesson.note?.let { "Отменена — $it" } ?: "Отменена",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }

        }
    }
}

/**
 * Место занятия — единственная выделенная пометка в строке.
 *
 * Раньше рядом стояла вторая такая же, для типа занятия, и две капсулы подряд
 * выбивались из спокойного вида остальных строк.
 */
@Composable
private fun Place(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.padding(end = 8.dp),
    )
}


/**
 * Ссылка на онлайн-занятие под названием предмета.
 *
 * Показываем её целиком: так видно, куда она ведёт, и понятно, что именно
 * скопируется. Нажатие кладёт ссылку в буфер обмена.
 */
@Composable
private fun OnlineLink(url: String) {
    val context = LocalContext.current
    Text(
        url,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Ссылка на занятие", url))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(vertical = 3.dp),
    )
}
