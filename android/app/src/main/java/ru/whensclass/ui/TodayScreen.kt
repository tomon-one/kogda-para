package ru.whensclass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    teachers: List<GroupDto>?,
    loadTeacherSchedule: suspend (String) -> ScheduleDto?,
    pinnedTeachers: List<String> = emptyList(),
    onTogglePinnedTeacher: (String) -> Unit = {},
    schedule: ScheduleDto?,
    fetchedAt: Long,
    hasUpdate: Boolean,
    refreshing: Boolean,
    teacherMode: Boolean = false,
    reloadKey: Int = 0,
    groups: List<GroupDto>? = null,
    loadGroupSchedule: suspend (String) -> ScheduleDto? = { null },
    pinnedGroups: List<String> = emptyList(),
    onTogglePinnedGroup: (String) -> Unit = {},
    selfTeacherId: String? = null,
    onSettings: () -> Unit,
    onUpdateBadge: () -> Unit,
    onRefresh: () -> Unit,
    refreshError: String? = null,
    onErrorShown: () -> Unit = {},
) {
    val today = remember { LocalDate.now() }
    val listState = rememberLazyListState()

    // Преподаватель открывает приложение на своём разделе.
    var tab by remember(teacherMode) {
        mutableStateOf(if (teacherMode) Tab.TEACHERS else Tab.STUDENTS)
    }
    val snackbar = remember { SnackbarHostState() }
    // Неудачу показываем плашкой: галочка «Расписание обновлено» загоралась и
    // тогда, когда связи не было, и человек уходил уверенным в свежих данных.
    LaunchedEffect(refreshError) {
        refreshError?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                // Своя плашка: у Material по умолчанию она светлая в любой теме.
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
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
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                            if (hasUpdate) {
                                // Кружок со стрелкой вниз: обычная точка не
                                // говорит, что именно случилось, а буква «↓»
                                // в бейдже выглядела кривовато.
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp).offset(x = 5.dp, y = (-4).dp),
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Есть обновление",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
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
                teacherMode = teacherMode,
                onPick = { picked ->
                    if (picked.ready) {
                        tab = picked
                    } else {
                        // Раздел откроется, когда колледж опубликует эти листы.
                        scope.launch { snackbar.showSnackbar(picked.emptyMessage) }
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

            when (tab) {
                Tab.STUDENTS -> if (teacherMode) {
                    // У преподавателя своей группы нет: раздел студентов —
                    // это список групп, чьё расписание можно посмотреть.
                    TeacherScreen(
                        teachers = groups,
                        loadSchedule = loadGroupSchedule,
                        pinned = pinnedGroups,
                        onTogglePin = onTogglePinnedGroup,
                        reloadKey = reloadKey,
                        searchLabel = "Поиск по названию группы",
                        showGroups = false,
                        othersTitle = "Другие группы",
                    )
                    return@Column
                }

                Tab.TEACHERS -> {
                    TeacherScreen(
                        teachers = teachers,
                        loadSchedule = loadTeacherSchedule,
                        pinned = pinnedTeachers,
                        onTogglePin = onTogglePinnedTeacher,
                        reloadKey = reloadKey,
                        // В роли преподавателя своё расписание уже загружено.
                        ownSchedule = if (teacherMode) schedule else null,
                        // День, на который нажали в виджете, терялся: вкладка
                        // преподавателя открывалась всегда на сегодня.
                        startDay = if (teacherMode) startDay else null,
                        // Себя отмечаем, только когда человек и правда
                        // преподаватель: у студента это просто чужая фамилия.
                        selfId = if (teacherMode) selfTeacherId else null,
                    )
                    return@Column
                }

                else -> Unit
            }

        if (schedule == null) {
            Explanation(
                title = "Расписание ещё не загружено",
                text = "Проверьте интернет и нажмите обновление вверху. " +
                    "Если не помогает, напишите @toomonn.",
            )
            return@Column
        }

        if (schedule.days.isEmpty()) {
            // Сервер ответил, но дней в ответе нет: так бывает в воскресенье,
            // когда следующий лист ещё не выложен. Раньше проверка была только
            // на «расписания нет вовсе», и экран оставался пустым без слов.
            Explanation(
                title = "На эти дни расписания нет",
                text = "Колледж выкладывает его на неделю-полторы вперёд. " +
                    "Загляните позже или проверьте обновление вверху.",
            )
            return@Column
        }

        ScheduleDays(
            schedule = schedule,
            today = today,
            listState = listState,
            startDay = startDay,
        )
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
    var spinning by remember { mutableStateOf(false) }
    val busy by rememberUpdatedState(refreshing)

    LaunchedEffect(refreshing) {
        if (refreshing) spinning = true
    }

    // Ключ — «крутимся», а не «идёт запрос»: иначе окончание запроса отменяло
    // бы эту же корутину, и значок замирал бы, едва тронувшись.
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        done = false
        do {
            angle.animateTo(angle.value + 360f, tween(450, easing = LinearEasing))
        } while (busy)
        angle.snapTo(0f)
        done = true
        delay(900)
        done = false
        spinning = false
    }

    IconButton(onClick = onRefresh, enabled = !refreshing) {
        // Без Crossfade: он держит в дереве оба значка и заводит вторую анимацию
        // поверх первой. На первых запусках, пока код ещё не прогрет, это и
        // давало рывки у самой заметной анимации приложения.
        if (done) {
            Icon(Icons.Default.Check, contentDescription = "Расписание обновлено")
        } else {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Обновить расписание",
                // Поворот в фазе отрисовки. Через Modifier.rotate(angle.value)
                // значение читалось при сборке дерева, и каждый кадр пересобирал
                // экран целиком.
                modifier = Modifier.graphicsLayer { rotationZ = angle.value },
            )
        }
    }
}

/** Разделы расписания. Пересдачи и экзамены колледж публикует отдельными листами. */
enum class Tab(val title: String, val ready: Boolean, val emptyMessage: String = "") {
    STUDENTS("Студентам", true),
    TEACHERS("Преподавателям", true),
    RETAKES("Пересдачи", false, "Сейчас пересдач нет"),
    EXAMS("Экзамены", false, "Сейчас экзаменов нет"),
}

/**
 * Порядок вкладок в верхнем ряду.
 *
 * Преподавателю первым нужен раздел преподавателей: он открывает приложение
 * ради своих пар, а не чужих.
 */
private fun topTabs(teacherMode: Boolean): List<Tab> =
    if (teacherMode) listOf(Tab.TEACHERS, Tab.STUDENTS) else listOf(Tab.STUDENTS, Tab.TEACHERS)

@Composable
private fun ScheduleTabs(current: Tab, teacherMode: Boolean, onPick: (Tab) -> Unit) {
    val top = topTabs(teacherMode)
    Column {
        // Полоска рисуется только в том ряду, где выбранная вкладка: иначе
        // подчёркнутыми оказываются сразу две.
        TabRow(
            selectedTabIndex = top.indexOf(current).coerceAtLeast(0),
            containerColor = MaterialTheme.colorScheme.background,
            divider = {},
            indicator = { positions ->
                val index = top.indexOf(current)
                if (index >= 0) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[index]),
                    )
                }
            },
        ) {
            top.forEach { TabButton(it, current, onPick) }
        }
        TabRow(
            selectedTabIndex = (current.ordinal - 2).coerceIn(0, 1),
            containerColor = MaterialTheme.colorScheme.background,
            divider = {},
            indicator = { positions ->
                // Полоску под нижним рядом рисуем, только когда там и правда
                // выбран раздел: иначе она подчёркивает пустое место.
                if (current.ordinal >= 2) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[current.ordinal - 2]),
                    )
                }
            },
        ) {
            TabButton(Tab.RETAKES, current, onPick)
            TabButton(Tab.EXAMS, current, onPick)
        }
    }
}

/**
 * Объяснение вместо пустого экрана.
 *
 * Пустой раздел без единого слова читается как поломка, поэтому везде, где
 * показывать нечего, приложение говорит почему.
 */
@Composable
private fun Explanation(title: String, text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TabButton(tab: Tab, current: Tab, onPick: (Tab) -> Unit) {
    Tab(
        selected = tab == current,
        onClick = { onPick(tab) },
        text = {
            Text(
                tab.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                // Неготовые разделы видно, но они приглушены.
                color = if (tab.ready) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
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
    startDay: String? = null,
) {
    // Открываемся на сегодняшнем дне: неделя показывается с понедельника,
    // и без этого расписание начинается с прожитых дней.
    LaunchedEffect(schedule, startDay) {
        val target = startDay ?: today.toString()
        val index = schedule.days.indexOfFirst { it.date >= target }
        if (index > 0) listState.scrollToItem(index)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(schedule.days, key = { it.date }) { day ->
            DayCard(day, schedule.bells, today)
        }
    }
}

@Composable
private fun DayCard(
    day: DayDto,
    bells: Map<String, List<String>>,
    today: LocalDate,
) {
    val date = remember(day.date) { runCatching { LocalDate.parse(day.date) }.getOrNull() }
    val isToday = date == today
    val past = date != null && date.isBefore(today)
    val current = if (isToday) currentLessonNumber(bells, today) else null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        // Прошедший день не выбрасываем — иногда нужно вспомнить, что было
        // в начале недели, — но приглушаем, чтобы он не спорил с сегодняшним.
        Column(modifier = if (past) Modifier.alpha(0.45f) else Modifier) {
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
                    LessonRow(lesson, bells, isNow = lesson.number == current)
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
                    // Ни кабинета, ни ссылки — так и говорим: пустая строка
                    // читается как «не загрузилось», хотя в таблице там пусто.
                    val room = roomLabel(lesson.room)
                    Place(room ?: "Не указано", muted = room == null)
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

            // Подпись группы стоит вместо преподавателя: у преподавателя в
            // своём расписании важно, кому читается пара, а у пары из второй
            // подгруппы — чья она. В остальных случаях там преподаватель.
            val group = lesson.groups
            if (group != null) {
                Text(
                    group,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
private fun Place(text: String, muted: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        // Отсутствие места — не то, что нужно подсвечивать цветом.
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.padding(end = 8.dp),
    )
}


/**
 * Ссылка на онлайн-занятие: две кнопки и ничего больше.
 *
 * Сама ссылка строкой не показывается. Она всё равно не влезала и обрывалась
 * после домена, а строка и ряд кнопок под ней делали идущую пару выше соседних
 * почти в полтора раза. Что занятие онлайн, сказано строкой выше.
 *
 * Открыть нужно чаще, чем скопировать, поэтому «Открыть» стоит первой. Но обе
 * на виду: ссылку иногда надо переслать, а не открыть.
 */
@Composable
private fun OnlineLink(url: String) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        // Куда ведёт кнопка. Полный адрес занимал строку и всё равно обрывался
        // после домена, но совсем без него проверить ссылку нечем: она приходит
        // из таблицы, которую заполняют руками.
        Text(
            host(url),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            LinkButton("Открыть") { openLink(context, url) }
            LinkButton("Копировать") { copyLink(context, url) }
        }
    }
}

/** «https://my.mts-link.ru/j/144...» -> «my.mts-link.ru». */
private fun host(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        // Кнопки идут парой внутри карточки, поэтому и поля, и высота у них
        // меньше обычных: иначе пара с вебинаром распухает.
        modifier = Modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        // Тонкая рамка: без неё текст кнопки неотличим от подписи рядом, и
        // непонятно, куда именно нажимать.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun openLink(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "Нечем открыть ссылку", Toast.LENGTH_SHORT).show()
    }
}

private fun copyLink(context: android.content.Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Ссылка на занятие", url))
    // С Android 13 система показывает это сама.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
    }
}
