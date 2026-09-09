package ru.whensclass.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/**
 * Какое сегодня число — с пересчётом в полночь.
 *
 * `remember { LocalDate.now() }` считает дату один раз за жизнь композиции, а
 * живёт она столько же, сколько процесс приложения. Человек, свернувший
 * приложение вечером и открывший назавтра, видел вчерашний день как
 * сегодняшний: красная полоса стояла не на той строке, прожитое не тускнело,
 * а идущая пара не подсвечивалась вовсе — подсветка включается только внутри
 * сегодняшнего дня.
 *
 * Виджеты этим не болели: они пересобираются целиком на каждую перерисовку.
 *
 * Ожидание считается до ближайшей полуночи, а не тикает каждую минуту: телефон
 * будить незачем. Если процесс всё это время был заморожен и срок вышел
 * раньше, чем нас разбудили, следующий круг сразу поставит верную дату.
 */
@Composable
fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            today = now.toLocalDate()
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(ChronoUnit.MILLIS.between(now, midnight).coerceAtLeast(1_000L))
        }
    }

    return today
}
