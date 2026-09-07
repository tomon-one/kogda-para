package ru.whensclass.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
private enum class Screen { TODAY, GROUPS, SETTINGS }

/** Красный колледжа — из его же логотипа. */
private val BRAND = Color(0xFFD60403)

/** Тёмная схема в тон виджету: чистый чёрный не светится на OLED. */
private val DarkScheme = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF141414),
    surfaceVariant = Color(0xFF1C1C1C),
    primary = Color(0xFFFF6B70),
    error = Color(0xFFFF6B70),
)

private val LightScheme = lightColorScheme(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF4F5F7),
    surfaceVariant = Color(0xFFEDEEF0),
    primary = BRAND,
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
    val theme = ThemeChoice.from(storedTheme)

    var screen by remember { mutableStateOf(Screen.TODAY) }

    val dark = when (theme) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when {
                        groupName == null || screen == Screen.GROUPS -> GroupPickerScreen(
                            loadGroups = { container.repository.groups() },
                            onPick = { group: GroupDto ->
                                scope.launch {
                                    container.repository.selectGroup(group)
                                    screen = Screen.TODAY
                                }
                            },
                        )

                        screen == Screen.SETTINGS -> SettingsScreen(
                            groupName = groupName,
                            theme = theme,
                            onTheme = { choice ->
                                scope.launch {
                                    container.store.setTheme(ThemeChoice.toStored(choice))
                                    // Виджет обязан перекраситься сразу, а не
                                    // через час при очередном обновлении.
                                    ScheduleWidget().updateAll(context)
                                }
                            },
                            onChangeGroup = { screen = Screen.GROUPS },
                            onRefresh = { SyncWorker.now(context) },
                            onBack = { screen = Screen.TODAY },
                        )

                        else -> TodayScreen(
                            groupName = groupName.orEmpty(),
                            schedule = schedule,
                            fetchedAt = fetchedAt,
                            onSettings = { screen = Screen.SETTINGS },
                            onRefresh = { SyncWorker.now(context) },
                        )
                    }
                }
            }
        }
    }
}
