package ru.whensclass.ui

import android.os.Bundle
import android.os.Build
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
import ru.whensclass.AppContainer
import ru.whensclass.data.sheetLink
import ru.whensclass.data.AppUpdate
import ru.whensclass.data.DEFAULT_NOTIFY_BEFORE
import ru.whensclass.data.GroupDto
import ru.whensclass.data.RefreshResult
import ru.whensclass.data.ReleaseDto
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.WeekWidget
import ru.whensclass.widget.ThemeChoice
import ru.whensclass.work.SyncWorker

class MainActivity : ComponentActivity() {

    // Разрешение на точное время напоминаний выдаётся в настройках телефона,
    // за пределами приложения. Перечитываем его при каждом возвращении, иначе
    // переключатель остаётся выключенным сразу после того, как его включили.
    private val exactAlarms = mutableStateOf(false)

    // Разрешение на сами уведомления. Его тоже могут выдать и отобрать в
    // настройках телефона, поэтому перечитываем при каждом возвращении.
    private val notifications = mutableStateOf(true)

    // Что телефон делает с приложением помимо его настроек: каналы, фон,
    // пояс, разрешение на установку. Тоже меняется снаружи.
    private val phone = mutableStateOf(ru.whensclass.notify.PhoneState())

    // Первый onResume идёт сразу за onCreate, где расписание уже запрошено:
    // второй запрос подряд там ни к чему.
    private var started = false

    /**
     * С чем открыли: день из виджета, настройки из уведомления о версии.
     * [seq] растёт на каждое нажатие по живому экрану: виджет и уведомления
     * зовут с SINGLE_TOP, и экран не пересоздаётся, а получает onNewIntent.
     * Раньше каждое нажатие уничтожало и создавало экран заново — пустой
     * кадр, лишние запросы, сброшенные вкладка, поиск и прокрутка.
     */
    private data class Opened(val day: String?, val update: Boolean, val seq: Int)

    private val opened = mutableStateOf(Opened(null, false, 0))

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opened.value = Opened(
            intent.getStringExtra(EXTRA_DAY),
            intent.getBooleanExtra(EXTRA_UPDATE, false),
            opened.value.seq + 1,
        )
        // Ответ — тот же, что при открытии заново: счёт ответов не должен
        // потерять нажатия оттого, что экран теперь не пересоздаётся.
        lifecycleScope.launch { AppContainer.get(applicationContext).store.countOpen() }
    }

    override fun onResume() {
        super.onResume()
        // Часы экрана пересчитываются при каждом возвращении: см. rememberNow.
        ScreenClock.resumes++
        // «Приложение забирает расписание при каждом открытии» — обещание из
        // настроек. Оба захода за расписанием висели на onCreate, поэтому
        // возврат из фона (список недавних, значок на экране) ничего не
        // запрашивал: Activity жива, onCreate не зовётся.
        if (started) SyncWorker.now(this)
        started = true
        notifications.value = Notifications.allowed(this)
        phone.value = ru.whensclass.notify.PhoneState.read(this)
        val allowed = LessonAlarms.exactAllowed(this)
        if (allowed != exactAlarms.value) {
            exactAlarms.value = allowed
            // Разрешение появилось — переставить будильники уже точными: и
            // напоминания, и звонок для виджетов — подсветка идущей пары ждёт
            // его же.
            LessonAlarms.reschedule(this)
            lifecycleScope.launch { ru.whensclass.work.MidnightUpdater.schedule(applicationContext) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // За свежим расписанием сходит сам экран (LaunchedEffect ниже): он же
        // и покажет результат. Раньше отсюда вдобавок ставился SyncWorker, и
        // на каждое открытие приложение дважды дёргало сервер — за одним и
        // тем же, без всякой блокировки между заходами.
        // Счёт ответов: ещё один раз таблицу открывать не пришлось. Только на
        // настоящем открытии: поворот экрана пересоздаёт Activity, и открытие
        // засчитывалось заново — счётчик обещает ответы, а не перевороты.
        if (savedInstanceState == null) {
            lifecycleScope.launch { AppContainer.get(applicationContext).store.countOpen() }
        }

        // Виджет мог попросить открыть конкретный день, а уведомление о
        // новой версии — сразу настройки с кнопкой установки.
        opened.value = Opened(
            intent?.getStringExtra(EXTRA_DAY),
            intent?.getBooleanExtra(EXTRA_UPDATE, false) == true,
            0,
        )
        setContent {
            val open = opened.value
            App(
                startDay = open.day,
                openUpdate = open.update,
                openSeq = open.seq,
                exactAlarms = exactAlarms.value,
                notifications = notifications.value,
                phone = phone.value,
            )
        }
    }

    companion object {
        const val EXTRA_DAY = "day"
        const val EXTRA_UPDATE = "update"
    }
}

/** Экраны приложения. Их четыре, поэтому обходимся без библиотеки навигации. */
// Порядок важен: по нему считается, куда «едет» экран при переходе.
private enum class Screen { WELCOME, GROUPS, TODAY, SETTINGS }

/** Красный колледжа — из его же логотипа. */
private val BRAND = Color(0xFFD60403)
private val BRAND_LIGHT = Color(0xFFFF6B70)

/** Тёмная схема в тон виджету: чистый чёрный не светится на OLED. */
private val DarkScheme = darkColorScheme(
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF9AA0A6),
    primary = BRAND_LIGHT,
    onPrimary = Color(0xFF000000),
    error = BRAND_LIGHT,
)

/**
 * Светлая схема: серый лист, белые карточки.
 *
 * Было наоборот — белый фон и сероватые карточки, да ещё с синевой в сером.
 * Карточка почти сливалась с фоном, а холодный серый читался как грязь.
 * Здесь серые нейтральные, без примеси, и карточка отделена от листа.
 */
private val LightScheme = lightColorScheme(
    background = Color(0xFFF1F2F4),
    onBackground = Color(0xFF15171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15171A),
    surfaceVariant = Color(0xFFE7E8EA),
    onSurfaceVariant = Color(0xFF5E6266),
    primary = BRAND,
    onPrimary = Color(0xFFFFFFFF),
    error = BRAND,
)

@Composable
private fun App(
    startDay: String? = null,
    openUpdate: Boolean = false,
    openSeq: Int = 0,
    exactAlarms: Boolean = false,
    notifications: Boolean = true,
    phone: ru.whensclass.notify.PhoneState = ru.whensclass.notify.PhoneState(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val container = remember { AppContainer.get(context) }
    val scope = rememberCoroutineScope()

    // Разрешение на уведомления спрашиваем сами. С Android 13 оно не выдаётся
    // по умолчанию, а targetSdk 37 означает, что система и не спросит: раньше
    // спрашивать было некому, и на свежем телефоне молчали разом напоминания о
    // паре, сообщения об отменах и о новых версиях. Отказ ничего не ломает —
    // в настройках останется подсказка, как выдать разрешение позже.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    // Один раз, а не на каждое пересоздание экрана: поворот заново
    // показывал системный запрос.
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (askedNotifications) return@LaunchedEffect
        askedNotifications = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifications) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val groupName by container.store.groupName.collectAsState(initial = null)
    val secondGroupName by container.store.secondGroupName.collectAsState(initial = null)
    val secondGone by container.store.secondGone.collectAsState(initial = false)
    val schedule by container.repository.schedule.collectAsState(initial = null)
    val fetchedAt by container.repository.fetchedAt.collectAsState(initial = 0L)
    val storedTheme by container.store.theme.collectAsState(initial = "system")
    val welcomeSeen by container.store.welcomeSeen.collectAsState(initial = true)
    val loaded by container.store.loaded.collectAsState(initial = false)
    val notifyBefore by container.store.notifyBefore
        .collectAsState(initial = DEFAULT_NOTIFY_BEFORE)
    val notifyEnabled by container.store.notifyEnabled.collectAsState(initial = false)
    val notifyChanges by container.store.notifyChanges.collectAsState(initial = true)
    val notifyUpdates by container.store.notifyUpdates.collectAsState(initial = true)
    val notifyServer by container.store.notifyServer.collectAsState(initial = true)
    val notifySubgroup by container.store.notifySubgroup.collectAsState(initial = true)
    val pinnedTeachers by container.store.pinnedTeachers.collectAsState(initial = emptyList())
    val teacherMode by container.store.isTeacher.collectAsState(initial = false)
    val serverStatus by container.store.serverStatus.collectAsState(initial = "ok")
    val tableUrl by container.store.sourceUrl.collectAsState(initial = null)
    val serverSince by container.store.serverSince.collectAsState(initial = null)
    val gone by container.store.gone.collectAsState(initial = false)
    val teacherName by container.store.teacherName.collectAsState(initial = null)
    val teacherId by container.store.teacherId.collectAsState(initial = null)
    val pinnedGroups by container.store.pinnedGroups.collectAsState(initial = emptyList())
    var groups by remember { mutableStateOf<List<GroupDto>?>(null) }
    // Выбранная тема применяется сразу, не дожидаясь записи на диск и обратной
    // волны из хранилища. Из-за этого круга смена выглядела рваной: экран ждал
    // ответа хранилища, а перерисовка виджетов, идущая там же, его задерживала.
    var chosenTheme by remember { mutableStateOf<ThemeChoice?>(null) }
    val theme = chosenTheme ?: ThemeChoice.from(storedTheme)

    // Экран, выбор и загрузка — через rememberSaveable: поворот, разделение
    // экрана и смена темы пересоздают Activity, и человек из настроек или
    // поиска группы оказывался на «Сегодня».
    var screen by rememberSaveable { mutableStateOf(if (openUpdate) Screen.SETTINGS else Screen.TODAY) }
    // Откуда пришли к выбору группы: туда и «назад» — и стрелкой, и жестом.
    // Стрелка вела в настройки, а жест — на главный.
    var groupsFrom by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    var update by remember { mutableStateOf<ReleaseDto?>(null) }
    // Отдельно от update: «сервер сказал, что новее ничего нет» и «до сервера
    // не достучались» — разные вещи, и человеку об этом надо говорить разное.
    var updateFailed by remember { mutableStateOf(false) }
    // Почему не удалось скачать. Раньше провал был молчаливым: кнопка
    // возвращалась из «Скачиваю…» в исходное, и всё.
    var updateError by rememberSaveable { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateChecked by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    // Почему обновление не вышло. Раньше кнопка ставила галочку «Расписание
    // обновлено» в любом случае, даже когда связи не было и данные прежние.
    var refreshError by remember { mutableStateOf<String?>(null) }
    // Последнее ручное обновление не удалось: кнопка покажет крестик, а не
    // галочку. Раньше результат refresh() здесь выбрасывался, refreshError не
    // присваивался нигде, и при отвалившейся сети загоралась «Расписание
    // обновлено».
    var refreshFailed by remember { mutableStateOf(false) }
    val download by container.updates.download.collectAsState()
    val installing = download == AppUpdate.Download.Running
    var focusUpdate by rememberSaveable { mutableStateOf(openUpdate) }
    // Списки держим здесь, а не во вкладке: во вкладке они перезагружались
    // бы на каждое переключение. Но и одного захода за запуск мало —
    // не вышло с первого раза (метро, спящий вайфай), и список оставался
    // пустым до перезапуска приложения, сколько бы человек ни возвращался
    // на вкладку. Поэтому повтор при каждом заходе, пока пусто.
    var teachers by remember { mutableStateOf<List<GroupDto>?>(null) }
    // Свежие списки за этот заход уже пришли — дальше хватит их.
    var listsFresh by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    // Какой список показывать на экране выбора: null — по текущей роли.
    var pickTeacher by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Тот же экран, но выбирают не свою группу, а соседнюю подгруппу.
    var pickSecond by rememberSaveable { mutableStateOf(false) }

    // Итог загрузки обновления. Установщик открывается, когда человек в
    // приложении: из фона Android 10+ его молча не пускал.
    LaunchedEffect(download) {
        val done = download as? AppUpdate.Download.Done ?: return@LaunchedEffect
        when (val result = done.result) {
            is AppUpdate.Result.Failed -> updateError = result.why
            is AppUpdate.Result.Ready -> lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                runCatching { context.startActivity(result.intent) }
                    .onFailure { updateError = "установщик Android не найден" }
            }
        }
        container.updates.taken()
    }

    // Новое нажатие по живому экрану: к дню из виджета или к обновлению.
    // Уведомление без дня («Расписание изменилось») — тоже к расписанию:
    // пересоздание экрана раньше так и делало.
    LaunchedEffect(openSeq) {
        if (openSeq == 0) return@LaunchedEffect
        if (openUpdate) {
            focusUpdate = true
            screen = Screen.SETTINGS
        } else {
            pickSecond = false
            pickTeacher = null
            screen = Screen.TODAY
        }
    }

    // И на каждое возвращение в приложение, пока свежих списков нет: на
    // первом запуске без связи экран выбора сам не менялся, и список не
    // перечитывался до перезапуска.
    LaunchedEffect(screen, reloadKey, ScreenClock.resumes) {
        val repository = container.repository
        // Сохранённые — сразу, без сети: экран открывается и в метро.
        // Свежие — следом и оба разом, а не по очереди.
        if (groups.isNullOrEmpty()) repository.cachedGroups().takeIf { it.isNotEmpty() }?.let { groups = it }
        if (teachers.isNullOrEmpty()) repository.cachedTeachers().takeIf { it.isNotEmpty() }?.let { teachers = it }
        if (listsFresh) return@LaunchedEffect
        val (freshGroups, freshTeachers) = coroutineScope {
            val g = async { repository.freshGroups() }
            val t = async { repository.freshTeachers() }
            g.await() to t.await()
        }
        freshGroups?.let { groups = it }
        freshTeachers?.let { teachers = it }
        // Ни свежего, ни сохранённого — «не загрузился», а не вечный спиннер.
        if (groups == null) groups = emptyList()
        if (teachers == null) teachers = emptyList()
        if (freshGroups != null && freshTeachers != null) {
            listsFresh = true
            repository.followRenamedPins(freshGroups, freshTeachers)
        }
    }

    // Системная кнопка и жест «назад» закрывали приложение с любого экрана:
    // навигация тут своя, а системе о ней никто не сказал. Из настроек и
    // выбора группы вернуться можно было только стрелкой в шапке.
    BackHandler(enabled = screen != Screen.TODAY) {
        val back = if (screen == Screen.GROUPS) groupsFrom else Screen.TODAY
        pickSecond = false
        pickTeacher = null
        screen = back
    }

    // «Повторить» у списков — заново и сеть, даже если свежие уже приходили.
    val retryLists: () -> Unit = {
        listsFresh = false
        reloadKey++
    }

    // Выбрали группу или себя: сразу на экран расписания, и ⟳ крутится, пока
    // идёт сеть. Раньше на первом запуске экран тут же писал «Проверьте
    // интернет», а из настроек список полминуты не реагировал.
    val afterPick: (suspend () -> Unit) -> Unit = { select ->
        scope.launch {
            refreshing = true
            screen = Screen.TODAY
            select()
            // После записи выбора: на первом запуске до неё виден экран выбора,
            // и сброс роли раньше мелькал бы списком групп вместо своих.
            pickTeacher = null
            refreshing = false
        }
    }

    val refreshNow: () -> Unit = {
        scope.launch {
            refreshing = true
            listsFresh = false
            reloadKey++
            // Напрямую, без WorkManager: он вправе отложить задачу на минуты,
            // а человек только что нажал кнопку и ждёт ответа сейчас.
            val result = container.repository.refresh(force = true)
            refreshFailed = result is RefreshResult.Failed
            when (result) {
                is RefreshResult.Failed -> refreshError = refreshFailure(result.error)
                // Сервер здоров, группы нет: сказать об этом, а не молча
                // погасить ⟳ крестиком «сервер не смог».
                RefreshResult.Gone -> refreshError =
                    if (teacherMode) "Вас больше нет в таблице — выберите себя заново"
                    else "Группы больше нет в таблице — выберите заново"
                else -> Unit
            }
            refreshing = false
        }
    }

    // Проверяем обновление один раз при запуске: чаще незачем, сборки выходят
    // не по расписанию.
    LaunchedEffect(Unit) {
        (container.updates.check() as? AppUpdate.Check.Available)?.let { update = it.release }
    }

    // И сразу забираем свежее расписание: после установки новой версии старые
    // данные на экране выглядят как поломка.
    LaunchedEffect(Unit) { container.repository.refresh() }
    // Кого показываем — зависит от роли: группу или самого преподавателя.
    val chosenName = if (teacherMode) teacherName else groupName
    val current = when {
        !welcomeSeen -> Screen.WELCOME
        chosenName == null -> Screen.GROUPS
        else -> screen
    }

    val dark = when (theme) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }

    // Тема выбирается внутри приложения, а значки системной полосы — снаружи.
    // Без этой связки светлая тема при тёмной системе оставляла белые часы и
    // заряд на белом фоне: полоса выглядела пустой.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as android.app.Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        // До Android 10 полосу навигации красит enableEdgeToEdge — по теме
        // системы, а не приложения: тёмная тема приложения на светлой системе
        // давала белые кнопки на почти белой полосе.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = (if (dark) DarkScheme else LightScheme).background.toArgb()
        }
    }

    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // Пустой фон, пока настройки читаются с диска: это доли секунды,
            // но без него успевает мелькнуть чужой экран.
            if (!loaded) return@Surface

            // Экраны сменяются со сдвигом: вглубь — справа налево, назад —
            // наоборот. Резкая подмена читалась как подвисание.
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val shift = if (forward) 1 else -1
                    (slideInHorizontally(tween(220)) { (it * shift) / 6 } + fadeIn(tween(180)))
                        .togetherWith(
                            slideOutHorizontally(tween(220)) { (-it * shift) / 6 } +
                                fadeOut(tween(140))
                        )
                },
                label = "screen",
            ) { target ->
                when (target) {
                    Screen.WELCOME -> WelcomeScreen(
                        onContinue = {
                            scope.launch { container.store.markWelcomeSeen() }
                        },
                    )

                    Screen.GROUPS -> if (pickSecond) {
                        GroupPickerScreen(
                            groups = groups,
                            onRetry = retryLists,
                            title = "Выберите соседнюю подгруппу",
                            canGoBack = true,
                            onBack = {
                                pickSecond = false
                                screen = groupsFrom
                            },
                            onPick = { group: GroupDto ->
                                scope.launch {
                                    // Свои пары остаются, пары прежней соседки
                                    // уходят сразу — и без сети.
                                    container.repository.selectSecondGroup(group)
                                    pickSecond = false
                                    screen = Screen.SETTINGS
                                }
                            },
                        )
                    } else if (pickTeacher ?: teacherMode) {
                        // Список преподавателей: человек выбирает себя. Роль
                        // меняется вместе с выбором, а не до него — иначе на
                        // мгновение показывается чужое расписание.
                        SelfPickerScreen(
                            teachers = teachers,
                            onRetry = retryLists,
                            loadDiagnostics = { container.repository.diagnostics() },
                            canGoBack = chosenName != null,
                            onBack = {
                                pickTeacher = null
                                screen = groupsFrom
                            },
                            onStudentMode = { pickTeacher = false },
                            onPick = { teacher ->
                                afterPick { container.repository.selectSelfAsTeacher(teacher) }
                            },
                        )
                    } else {
                        GroupPickerScreen(
                            groups = groups,
                            onRetry = retryLists,
                            loadDiagnostics = { container.repository.diagnostics() },
                            canGoBack = chosenName != null,
                            onBack = {
                                pickTeacher = null
                                screen = groupsFrom
                            },
                            onTeacherMode = { pickTeacher = true },
                            onPick = { group: GroupDto ->
                                afterPick { container.repository.selectGroup(group) }
                            },
                        )
                    }

                    Screen.SETTINGS -> SettingsScreen(
                        groupName = chosenName,
                        teacherMode = teacherMode,
                        onSwitchRole = {
                            pickTeacher = !teacherMode
                            groupsFrom = Screen.SETTINGS
                            screen = Screen.GROUPS
                        },
                        theme = theme,
                        update = update,
                        installing = installing,
                        notifyBefore = notifyBefore,
                        notifyEnabled = notifyEnabled,
                        notifyChanges = notifyChanges,
                        notifyUpdates = notifyUpdates,
                        notifyServer = notifyServer,
                        notifySubgroup = notifySubgroup,
                        exactAlarms = exactAlarms,
                        notifications = notifications,
                        phone = phone,
                        onNotifyBefore = { minutes ->
                            scope.launch {
                                container.store.setNotifyBefore(minutes)
                                LessonAlarms.reschedule(context)
                            }
                        },
                        onNotifyEnabled = { on ->
                            scope.launch {
                                container.store.setNotifyEnabled(on)
                                LessonAlarms.reschedule(context)
                            }
                        },
                        onNotifyChanges = { on ->
                            scope.launch { container.store.setNotifyChanges(on) }
                        },
                        onNotifyUpdates = { on ->
                            scope.launch { container.store.setNotifyUpdates(on) }
                        },
                        onNotifyServer = { on ->
                            scope.launch { container.store.setNotifyServer(on) }
                        },
                        onNotifySubgroup = { on ->
                            scope.launch { container.store.setNotifySubgroup(on) }
                        },
                        focusUpdate = focusUpdate,
                        checkingUpdate = checkingUpdate,
                        updateChecked = updateChecked,
                        updateFailed = updateFailed,
                        updateError = updateError,
                        loadDiagnostics = { container.repository.diagnostics() },
                        sheetUrl = { sheetLink(schedule, ru.whensclass.widget.collegeToday(), tableUrl) },
                        onCheckUpdate = {
                            scope.launch {
                                checkingUpdate = true
                                updateError = null
                                when (val result = container.updates.check()) {
                                    is AppUpdate.Check.Available -> {
                                        update = result.release
                                        updateFailed = false
                                    }
                                    AppUpdate.Check.UpToDate -> {
                                        update = null
                                        updateFailed = false
                                    }
                                    AppUpdate.Check.Failed -> updateFailed = true
                                }
                                checkingUpdate = false
                                updateChecked = true
                            }
                        },
                        onUpdate = {
                            update?.let {
                                updateError = null
                                container.updates.start(it)
                            }
                        },
                        onTheme = { choice ->
                            chosenTheme = choice
                            scope.launch {
                                container.store.setTheme(ThemeChoice.toStored(choice))
                                // Виджет обязан перекраситься сразу, а не через
                                // час при очередном обновлении. Через репозиторий,
                                // а не тремя вызовами руками: счёт ответов вшит
                                // именно туда, и обход мимо него терял отрисовки.
                                container.repository.redrawWidgets()
                            }
                        },
                        onChangeGroup = {
                            pickSecond = false
                            groupsFrom = Screen.SETTINGS
                            screen = Screen.GROUPS
                        },
                        secondGroupName = secondGroupName?.let { if (secondGone) "$it — нет в таблице" else it },
                        onPickSecondGroup = {
                            pickSecond = true
                            groupsFrom = Screen.SETTINGS
                            screen = Screen.GROUPS
                        },
                        onClearSecondGroup = {
                            scope.launch { container.repository.selectSecondGroup(null) }
                        },
                        onBack = { screen = Screen.TODAY },
                    )

                    Screen.TODAY -> TodayScreen(
                        startDay = startDay,
                        startKey = openSeq,
                        groupName = chosenName.orEmpty(),
                        teacherMode = teacherMode,
                        teachers = teachers,
                        loadTeacherSchedule = { container.repository.teacherSchedule(it) },
                        loadGroupSchedule = { container.repository.groupSchedule(it) },
                        groups = groups,
                        pinnedGroups = pinnedGroups,
                        onTogglePinnedGroup = { id ->
                            scope.launch { container.store.togglePinnedGroup(id) }
                        },
                        selfTeacherId = teacherId,
                        pinnedTeachers = pinnedTeachers,
                        onTogglePinnedTeacher = { id ->
                            scope.launch { container.store.togglePinnedTeacher(id) }
                        },
                        schedule = schedule,
                        fetchedAt = fetchedAt,
                        hasUpdate = update != null,
                        refreshing = refreshing,
                        refreshFailed = refreshFailed,
                        refreshError = refreshError,
                        onErrorShown = { refreshError = null },
                        loadTally = { container.store.tally() },
                        serverBroken = serverStatus != "ok",
                        unreachable = serverStatus == ru.whensclass.data.STATUS_UNREACHABLE,
                        sourceUrl = tableUrl,
                        serverSince = serverSince,
                        gone = gone,
                        onRepick = {
                            pickTeacher = teacherMode
                            pickSecond = false
                            groupsFrom = Screen.TODAY
                            screen = Screen.GROUPS
                        },
                        reloadKey = reloadKey,
                        onSettings = {
                            focusUpdate = false
                            screen = Screen.SETTINGS
                        },
                        onUpdateBadge = {
                            focusUpdate = true
                            screen = Screen.SETTINGS
                        },
                        onRefresh = refreshNow,
                    )
                }
            }
        }
    }
}


/**
 * Почему ручное обновление не удалось — словами для плашки. 503 у нас — не
 * «занят», а «расписания на сервере ещё нет» (api.md); занятость nginx
 * отвечает 429.
 */
internal fun refreshFailure(error: Throwable): String = when (error) {
    is ru.whensclass.data.HttpFailure -> when (error.code) {
        429 -> "Сервер занят, попробуйте через минуту"
        // 503 — когда снимка нет вовсе (новый сервер, потерянные данные) и до
        // 40 минут, пока сервер подтверждает переименование группы: «нет
        // расписания» соврало бы всей переименованной группе (разбор текстов 27.09).
        503 -> "Сервер сейчас не отдаёт это расписание, на экране прежнее. Не пройдёт за час — " +
            "напишите автору в Telegram: @toomonn"
        else -> "Не удалось обновить: сервер ответил ${error.code}"
    }
    else -> "Не удалось обновить: нет связи с сервером"
}
