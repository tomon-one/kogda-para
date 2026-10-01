package ru.whensclass.data

/**
 * Сколько дней держим на телефоне: эта неделя и следующая, с понедельника, —
 * чтобы в выходные была видна следующая неделя. Сервер отдаёт до 14 дней за
 * запрос.
 */
const val DAYS = 14

/**
 * Отметка окна: понедельник и размер. Размер — затем, чтобы телефон со
 * старым окном перезапросил его сам, а не ждал до новой недели или правки
 * таблицы: сверка идёт по строке, «2026-09-21» ≠ «2026-09-21/14».
 */
internal fun windowMark(from: java.time.LocalDate): String = "$from/$DAYS"

/**
 * С какого дня показывать расписание — с понедельника текущей недели:
 * прошедшие пары остаются видны. Воскресенье — такой же день недели, как
 * остальные; завтрашний понедельник виден за счёт следующей недели в окне.
 */
fun weekStart(today: java.time.LocalDate = ru.whensclass.widget.collegeToday()): java.time.LocalDate =
    today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
