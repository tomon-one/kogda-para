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
        lifecycleScope.launch { runCatching { AppContainer.get(applicationContext).store.countOpen() } }
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
