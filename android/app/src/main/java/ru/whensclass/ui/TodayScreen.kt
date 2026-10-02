package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.whensclass.data.GroupDto
import ru.whensclass.data.ScheduleDto
import ru.whensclass.data.ScheduleStore
import ru.whensclass.data.sheetLink
import ru.whensclass.widget.formatFetchedAt
import ru.whensclass.widget.plural


/**
 * Главный экран: расписание на неделю, вкладки студентов и преподавателей,
 * плашки сбоя и пропажи группы.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    startDay: String? = null,
    /** Растёт на каждое открытие с днём по живому экрану (onNewIntent). */
    startKey: Int = 0,
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
    /** Последнее ручное обновление не удалось — крестик вместо галочки. */
    refreshFailed: Boolean = false,
    refreshError: String? = null,
    onErrorShown: () -> Unit = {},
    loadTally: suspend () -> ScheduleStore.Tally = { ScheduleStore.Tally(0, 0, 0) },
    serverBroken: Boolean = false,
    /** Сервер не отвечает телефону — не то же, что «не прочитал таблицу». */
    unreachable: Boolean = false,
    /** Сервер больше не обслуживает эту сборку — нужно обновить приложение. */
    unsupported: Boolean = false,
    sourceUrl: String? = null,
    /** С какого момента сервер лежит (ISO, UTC) — для давности на плашке. */
    serverSince: String? = null,
    /** Группы (преподавателя) в таблице больше нет — сервер отвечает 404. */
    gone: Boolean = false,
    onRepick: () -> Unit = {},
    /** Подписи выбранных групп у пар: названиями или номерами (настройки). */
    groupsByName: Boolean = true,
) {
    // Часы со звонками: «сегодня» и давность сбоя на плашке пересчитываются сами.
    val now = rememberNow(schedule?.bells.orEmpty())
    val today = now.toLocalDate()

    // Преподаватель открывает приложение на своём разделе. Вкладка переживает
    // поворот экрана.
    var tab by rememberSaveable(teacherMode) {
        mutableStateOf(if (teacherMode) Tab.TEACHERS else Tab.STUDENTS)
    }
    // Нажали на день в виджете по живому экрану — к своему расписанию, а не к
    // открытой чужой вкладке.
    LaunchedEffect(startKey) {
        if (startKey > 0) tab = if (teacherMode) Tab.TEACHERS else Tab.STUDENTS
    }
    val snackbar = remember { SnackbarHostState() }
    // Неудачу показываем плашкой, чтобы человек не ушёл уверенным в свежих
    // данных.
    LaunchedEffect(refreshError) {
        refreshError?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }
    val scope = rememberCoroutineScope()

    var showTally by remember { mutableStateOf(false) }
    if (showTally) {
        TallyDialog(loadTally = loadTally, onDismiss = { showTally = false })
    }

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
                            // У преподавателя здесь ФИО целиком: без
                            // ограничения оно расползается на три строки.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Долгое нажатие по названию группы — счёт ответов,
                            // нарочно без подсказки.
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = { showTally = true })
                            },
                        )
                        Text(
                            formatFetchedAt(fetchedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    RefreshButton(
                        refreshing = refreshing,
                        // Почему крестик — у каждой причины своё.
                        brokenWhy = when {
                            refreshFailed -> "Не удалось обновить расписание"
                            gone -> if (teacherMode) "Вас нет в таблице" else "Группы нет в таблице"
                            unsupported -> "Нужно обновить приложение"
                            unreachable -> "Сервер расписания не отвечает"
                            serverBroken -> "Сервер не смог обновить расписание"
                            else -> null
                        },
                        onRefresh = onRefresh,
                    )
                    IconButton(onClick = if (hasUpdate) onUpdateBadge else onSettings) {
                        // Значок над шестерёнкой: вышла новая сборка. Нажатие
                        // открывает настройки сразу на разделе обновления.
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                            if (hasUpdate) {
                                // Кружок со стрелкой вниз: обычная точка не
                                // говорит, что именно случилось.
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
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
        // В таблицу — к своей колонке и сегодняшнему дню, если сервер
        // рассказал, где они; иначе просто в книгу.
        val plates: @Composable () -> Unit = {
            if (gone) Gone(groupName, teacherMode, onRepick)
            if (unsupported) {
                Unsupported(onUpdateBadge)
            } else if (serverBroken) {
                ServerBroken(
                    sheetLink(schedule, today, sourceUrl), serverSince, now, unreachable,
                )
            }
        }
        // Мало высоты (альбомная ориентация, половина экрана): плашки едут
        // вместе со списком, иначе неподвижная шапка отнимает у него всё место.
        val platesInList = maxHeight < 480.dp && tab == Tab.STUDENTS && !teacherMode &&
            !schedule?.days.isNullOrEmpty()
        Column(modifier = Modifier.fillMaxSize()) {
            if (!platesInList) plates()
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
            ) {
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
                            listName = "Список групп",
                            othersTitle = "Другие группы",
                            endNote = { n ->
                                "Всё. " +
                                    plural(n, "группа", "группы", "групп") +
                                    "."
                            },
                        )
                        return@ScheduleTabs
                    }

                    Tab.TEACHERS -> {
                        // Отсутствие своего расписания преподавателя объясняется так
                        // же, как у студента, — до списка чужих фамилий.
                        if (teacherMode && explainMissing(schedule, today, sourceUrl, loading = refreshing)) {
                            return@ScheduleTabs
                        }
                        TeacherScreen(
                            teachers = teachers,
                            loadSchedule = loadTeacherSchedule,
                            pinned = pinnedTeachers,
                            onTogglePin = onTogglePinnedTeacher,
                            reloadKey = reloadKey,
                            // В роли преподавателя своё расписание уже загружено.
                            ownSchedule = if (teacherMode) schedule else null,
                            // День, на который нажали в виджете.
                            startDay = if (teacherMode) startDay else null,
                            startKey = startKey,
                            // Себя отмечаем, только когда человек и правда
                            // преподаватель: у студента это просто чужая фамилия.
                            selfId = if (teacherMode) selfTeacherId else null,
                        )
                        return@ScheduleTabs
                    }

                    else -> Unit
                }

                if (explainMissing(schedule, today, sourceUrl, loading = refreshing)) return@ScheduleTabs
                schedule ?: return@ScheduleTabs

                ScheduleDays(
                    schedule = schedule,
                    today = today,
                    startDay = startDay,
                    startKey = startKey,
                    header = if (platesInList) plates else null,
                    groupsByName = groupsByName,
                )
            }
        }
        }
    }
}

/**
 * Кнопка обновления. Крутится, пока идёт запрос, и доводит оборот до конца:
 * замерший на полпути значок выглядит зависшим. Закончив, показывает галочку
 * — иначе «готово» не видно, когда расписание не изменилось.
 */
@Composable
private fun RefreshButton(refreshing: Boolean, brokenWhy: String?, onRefresh: () -> Unit) {
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
        // поверх первой — на непрогретом коде это рывки.
        if (done && brokenWhy != null) {
            // Запрос прошёл, но расписание прежнее: галочка обещала бы
            // свежесть, которой нет. Подробности — на плашке под шапкой.
            Icon(Icons.Default.Close, contentDescription = brokenWhy)
        } else if (done) {
            Icon(Icons.Default.Check, contentDescription = "Расписание обновлено")
        } else {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Обновить расписание",
                // Поворот в фазе отрисовки: Modifier.rotate(angle.value) читает
                // значение при сборке и пересобирает экран каждый кадр.
                modifier = Modifier.graphicsLayer { rotationZ = angle.value },
            )
        }
    }
}
