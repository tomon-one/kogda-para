package ru.whensclass.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import ru.whensclass.AppContainer
import ru.whensclass.data.GroupDto
import ru.whensclass.work.SyncWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Человек открыл приложение — самое время сходить за свежим расписанием.
        SyncWorker.now(this)

        setContent {
            MaterialTheme(colorScheme = DarkScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

/** Тёмная схема в тон виджету: чистый чёрный не светится на OLED. */
private val DarkScheme = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF121212),
    primary = Color(0xFF5FD3A8),
    error = Color(0xFFFF7A7A),
)

@Composable
private fun App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember { AppContainer.get(context) }
    val scope = rememberCoroutineScope()

    val groupName by container.store.groupName.collectAsState(initial = null)
    val schedule by container.repository.schedule.collectAsState(initial = null)
    val fetchedAt by container.repository.fetchedAt.collectAsState(initial = 0L)

    var picking by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (groupName == null || picking) {
                GroupPickerScreen(
                    loadGroups = { container.repository.groups() },
                    onPick = { group: GroupDto ->
                        scope.launch {
                            container.repository.selectGroup(group)
                            picking = false
                        }
                    },
                )
            } else {
                TodayScreen(
                    groupName = groupName.orEmpty(),
                    schedule = schedule,
                    fetchedAt = fetchedAt,
                    onChangeGroup = { picking = true },
                    onRefresh = { SyncWorker.now(context) },
                )
            }
        }
    }
}
