package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Разделы расписания. Пересдачи и экзамены колледж публикует отдельными листами. */
enum class Tab(val title: String, val ready: Boolean, val emptyMessage: String = "") {
    STUDENTS("Студентам", true),
    TEACHERS("Преподавателям", true),
    // Говорим, когда появится, а не «пока пусто»: пустая вкладка без срока
    // читается как недоделка, со сроком — как план. Про пересдачи важно
    // сказать сразу, что личных не будет: в листе колледжа нет колонки
    // группы, и собрать их оттуда нельзя ни при каком разборе.
    // Строки листа пересдач — предмет и преподаватель, групп нет: своё
    // преподаватель найдёт, студент — только по предмету.
    RETAKES("Пересдачи", false, "Будут списком по предметам и преподавателям: групп в таблице колледжа нет"),
    EXAMS("Экзамены", false, "Появятся к сессии — колледж выкладывает их в декабре"),
}

/**
 * Порядок вкладок в верхнем ряду.
 *
 * Преподавателю первым нужен раздел преподавателей: он открывает приложение
 * ради своих пар, а не чужих.
 */
private fun topTabs(teacherMode: Boolean): List<Tab> =
    if (teacherMode) listOf(Tab.TEACHERS, Tab.STUDENTS) else listOf(Tab.STUDENTS, Tab.TEACHERS)

@Composable
internal fun ScheduleTabs(current: Tab, teacherMode: Boolean, onPick: (Tab) -> Unit) {
    val top = topTabs(teacherMode)
    Column {
        // Полоска рисуется только в том ряду, где выбранная вкладка: иначе
        // подчёркнутыми оказываются сразу две.
        TabRow(
            selectedTabIndex = top.indexOf(current).coerceAtLeast(0),
            containerColor = MaterialTheme.colorScheme.background,
            divider = {},
            indicator = { positions ->
                val index = top.indexOf(current)
                if (index >= 0) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[index]),
                    )
                }
            },
        ) {
            top.forEach { TabButton(it, current, onPick) }
        }
        TabRow(
            selectedTabIndex = (current.ordinal - 2).coerceIn(0, 1),
            containerColor = MaterialTheme.colorScheme.background,
            divider = {},
            indicator = { positions ->
                // Полоску под нижним рядом рисуем, только когда там и правда
                // выбран раздел: иначе она подчёркивает пустое место.
                if (current.ordinal >= 2) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[current.ordinal - 2]),
                    )
                }
            },
        ) {
            TabButton(Tab.RETAKES, current, onPick)
            TabButton(Tab.EXAMS, current, onPick)
        }
    }
}

/**
 * Объяснение вместо пустого экрана.
 *
 * Пустой раздел без единого слова читается как поломка, поэтому везде, где
 * показывать нечего, приложение говорит почему.
 */
@Composable
internal fun Explanation(title: String, text: String, sourceUrl: String? = null, busy: Boolean = false) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp).padding(bottom = 4.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        // Кто виноват — колледж не выложил или мы не нашли лист — отсюда
        // не видно. Зато видно, где лежит ответ.
        sourceUrl?.let { url ->
            ActionButton(
                label = "Открыть таблицу колледжа",
                onClick = { openLink(context, url) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TabButton(tab: Tab, current: Tab, onPick: (Tab) -> Unit) {
    Tab(
        selected = tab == current,
        onClick = { onPick(tab) },
        text = {
            Text(
                tab.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                // Тесно (крупный шрифт, разделённый экран) — «Преподавателям»
                // сначала мельчает, а многоточие — только если и так не влезло.
                overflow = TextOverflow.Ellipsis,
                autoSize = FIT,
                // Неготовые разделы видно, но они приглушены.
                color = if (tab.ready) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
