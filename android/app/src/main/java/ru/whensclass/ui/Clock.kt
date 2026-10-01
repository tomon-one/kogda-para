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
 * Возвращение на экран нужно отдельно: delay идёт на монотонных часах,
 * которые в глубоком сне стоят, и утром экран считал бы «сегодня» вчера.
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
