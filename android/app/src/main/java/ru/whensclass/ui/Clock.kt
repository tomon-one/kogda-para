package ru.whensclass.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import ru.whensclass.widget.collegeNow
import ru.whensclass.widget.nextTick

/** Сколько раз экран возвращался на передний план — растёт в MainActivity.onResume. */
object ScreenClock {
    var resumes by mutableIntStateOf(0)
}

/**
 * Который сейчас час — с пересчётом на каждом звонке, в полночь и при каждом
 * возвращении на экран. Время — колледжа (см. COLLEGE_ZONE).
 *
 * `remember { LocalDate.now() }` считал дату один раз за жизнь композиции, и
 * свернувший приложение вечером видел назавтра вчерашний день как сегодняшний.
 * Потом ожидание полуночи делал delay — но он идёт на монотонных часах,
 * которые в глубоком сне стоят: утром экран всё ещё считал «сегодня» вчера.
 * Поэтому пересчёт ещё и на ON_RESUME. А подсветка идущей
 * пары считалась при компоновке карточки и со звонком не двигалась, пока экран
 * открыт (дефект 7 в handoff): теперь ожидание — до ближайшего звонка, а не
 * только до полуночи.
 */
@Composable
fun rememberNow(bells: Map<String, List<String>> = emptyMap()): LocalDateTime {
    var now by remember { mutableStateOf(collegeNow()) }
    val resumes = ScreenClock.resumes

    LaunchedEffect(bells, resumes) {
        while (true) {
            val current = collegeNow()
            now = current
            delay(ChronoUnit.MILLIS.between(current, nextTick(bells, current)).coerceAtLeast(1_000L))
        }
    }

    return now
}

/** Какое сегодня число — см. [rememberNow]. */
@Composable
fun rememberToday(): LocalDate = rememberNow().toLocalDate()
