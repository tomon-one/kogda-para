package ru.whensclass.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import ru.whensclass.AppContainer
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import ru.whensclass.work.SyncWorker

class MainActivity : ComponentActivity() {

    // Разрешения и состояние телефона меняются в его настройках, за пределами
    // приложения, — поэтому перечитываются при каждом возвращении (onResume).
    // Точное время напоминаний.
    private val exactAlarms = mutableStateOf(false)

    // Уведомления.
    private val notifications = mutableStateOf(true)

    // Каналы, фон, пояс, разрешение на установку.
    private val phone = mutableStateOf(ru.whensclass.notify.PhoneState())

    // Первый onResume идёт сразу за onCreate, где расписание уже запрошено:
    // второй запрос подряд там ни к чему.
    private var started = false

    /**
     * С чем открыли: день из виджета, настройки из уведомления о версии.
     * [seq] растёт на каждое нажатие по живому экрану: виджет и уведомления
     * зовут с SINGLE_TOP, и экран не пересоздаётся, а получает onNewIntent —
     * без пустого кадра и со своими вкладкой, поиском и прокруткой.
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
        // Ответ — тот же, что при открытии заново.
        lifecycleScope.launch { runCatching { AppContainer.get(applicationContext).store.countOpen() } }
    }

    override fun onResume() {
        super.onResume()
        // Часы экрана пересчитываются при каждом возвращении: см. rememberNow.
        ScreenClock.resumes++
        // Расписание — при каждом открытии, и при возврате из фона тоже:
        // Activity жива, onCreate не зовётся.
        if (started) SyncWorker.now(this)
        started = true
        notifications.value = Notifications.allowed(this)
        phone.value = ru.whensclass.notify.PhoneState.read(this)
        val allowed = LessonAlarms.exactAllowed(this)
        if (allowed != exactAlarms.value) {
            exactAlarms.value = allowed
            // Разрешение сменилось — переставить и напоминания, и звонок для
            // подсветки на виджетах.
            LessonAlarms.reschedule(this)
            lifecycleScope.launch { ru.whensclass.work.MidnightUpdater.schedule(applicationContext) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // За свежим расписанием сходит сам экран (App): он же и покажет
        // результат, второй заход отсюда был бы лишним.
        // Счёт ответов — только на настоящем открытии, не на повороте.
        if (savedInstanceState == null) {
            lifecycleScope.launch { runCatching { AppContainer.get(applicationContext).store.countOpen() } }
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
