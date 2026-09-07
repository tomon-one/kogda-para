package ru.whensclass.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import ru.whensclass.AppContainer
import ru.whensclass.data.GroupDto
import ru.whensclass.data.ReleaseDto
import ru.whensclass.widget.NextLessonWidget
import ru.whensclass.widget.ScheduleWidget
import ru.whensclass.widget.ThemeChoice
import ru.whensclass.work.SyncWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Человек открыл приложение — самое время сходить за свежим расписанием.
        SyncWorker.now(this)

        setContent { App() }
    }
}

/** Экраны приложения. Их три, поэтому обходимся без библиотеки навигации. */
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

private val LightScheme = lightColorScheme(
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF16181B),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF16181B),
    surfaceVariant = Color(0xFFECEEF1),
    onSurfaceVariant = Color(0xFF5C6672),
    primary = BRAND,
    onPrimary = Color(0xFFFFFFFF),
    error = BRAND,
)

@Composable
private fun App() {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val scope = rememberCoroutineScope()

    val groupName by container.store.groupName.collectAsState(initial = null)
    val schedule by container.repository.schedule.collectAsState(initial = null)
    val fetchedAt by container.repository.fetchedAt.collectAsState(initial = 0L)
    val storedTheme by container.store.theme.collectAsState(initial = "system")
    val welcomeSeen by container.store.welcomeSeen.collectAsState(initial = true)
    val theme = ThemeChoice.from(storedTheme)

    var screen by remember { mutableStateOf(Screen.TODAY) }
    var update by remember { mutableStateOf<ReleaseDto?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }

    // Проверяем обновление один раз при запуске: чаще незачем, сборки выходят
    // не по расписанию.
    LaunchedEffect(Unit) { update = container.updates.check() }
    val current = when {
        !welcomeSeen -> Screen.WELCOME
        groupName == null -> Screen.GROUPS
        else -> screen
    }

    val dark = when (theme) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
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
                        onContinue = { scope.launch { container.store.markWelcomeSeen() } },
                    )

                    Screen.GROUPS -> GroupPickerScreen(
                        loadGroups = { container.repository.groups() },
                        canGoBack = groupName != null,
                        onBack = { screen = Screen.SETTINGS },
                        onPick = { group: GroupDto ->
                            scope.launch {
                                container.repository.selectGroup(group)
                                screen = Screen.TODAY
                            }
                        },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        groupName = groupName,
                        theme = theme,
                        update = update,
                        updateReady = update?.let { container.updates.downloaded(it) } != null,
                        checkingUpdate = checkingUpdate,
                        onCheckUpdate = {
                            scope.launch {
                                checkingUpdate = true
                                update = container.updates.check()
                                checkingUpdate = false
                            }
                        },
                        onUpdate = {
                            val release = update ?: return@SettingsScreen
                            val ready = container.updates.downloaded(release)
                            if (ready != null) {
                                container.updates.install(ready)
                            } else {
                                container.updates.download(release)
                            }
                        },
                        onTheme = { choice ->
                            scope.launch {
                                container.store.setTheme(ThemeChoice.toStored(choice))
                                // Виджет обязан перекраситься сразу, а не через
                                // час при очередном обновлении.
                                ScheduleWidget().updateAll(context)
                                NextLessonWidget().updateAll(context)
                            }
                        },
                        onChangeGroup = { screen = Screen.GROUPS },
                        onRefresh = { SyncWorker.now(context) },
                        onBack = { screen = Screen.TODAY },
                    )

                    Screen.TODAY -> TodayScreen(
                        groupName = groupName.orEmpty(),
                        schedule = schedule,
                        fetchedAt = fetchedAt,
                        hasUpdate = update != null,
                        onSettings = { screen = Screen.SETTINGS },
                        onRefresh = { SyncWorker.now(context) },
                    )
                }
            }
        }
    }
}
