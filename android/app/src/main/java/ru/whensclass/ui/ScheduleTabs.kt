package ru.whensclass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Разделы расписания. Пересдачи и экзамены колледж публикует отдельными листами. */
enum class Tab(val title: String, val ready: Boolean, val emptyMessage: String = "") {
    STUDENTS("Студентам", true),
    TEACHERS("Преподавателям", true),
    // Говорим, какими будут, а не «пока пусто»: пустая вкладка без слов
    // читается как недоделка. В листе пересдач колледжа только предмет и
    // преподаватель, колонки группы нет — личных пересдач не будет.
    RETAKES("Пересдачи", false, "Будут списком по предметам и преподавателям: групп в таблице колледжа нет"),
    EXAMS("Экзамены", false, "Появятся к сессии — колледж выкладывает их в декабре"),
}

/** Порядок вкладок в верхнем ряду: преподавателю первым — раздел преподавателей. */
private fun topTabs(teacherMode: Boolean): List<Tab> =
    if (teacherMode) listOf(Tab.TEACHERS, Tab.STUDENTS) else listOf(Tab.STUDENTS, Tab.TEACHERS)

/**
 * Вкладки над разделом и сам раздел ([content]). Нижний ряд — пересдачи и
 * экзамены — уезжает вместе со списком и возвращается, когда список докручен до
 * верха, как на сайте: пока эти разделы пусты, он только отнимает место. Выбран
 * раздел из нижнего ряда — ряд стоит.
 */
@Composable
internal fun ScheduleTabs(
    current: Tab,
    teacherMode: Boolean,
    onPick: (Tab) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val top = topTabs(teacherMode)
    val pinned by rememberUpdatedState(current.ordinal >= 2)
    var rowHeight by remember { mutableIntStateOf(0) }
    // Насколько нижний ряд уехал вверх: от минус его высоты до нуля.
    var shift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(current) { shift = 0f }
    val collapse = remember {
        object : NestedScrollConnection {
            fun move(dy: Float): Float {
                if (pinned || rowHeight == 0) return 0f
                val next = (shift + dy).coerceIn(-rowHeight.toFloat(), 0f)
                val used = next - shift
                shift = next
                return used
            }

            // Вверх — сначала уезжает ряд, потом листается список.
            override fun onPreScroll(available: Offset, source: NestedScrollSource) =
                if (available.y < 0f) Offset(0f, move(available.y)) else Offset.Zero

            // Вниз — ряд возвращается тем, что список не смог взять: он уже наверху.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
                if (available.y > 0f) Offset(0f, move(available.y)) else Offset.Zero
        }
    }
    Column(Modifier.fillMaxSize()) {
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
        // Сдвиг читается только при раскладке: прокрутка не пересобирает экран.
        Box(
            Modifier
                .clipToBounds()
                .layout { measurable, constraints ->
                    val row = measurable.measure(
                        constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
                    )
                    rowHeight = row.height
                    val shown = (row.height + shift.roundToInt()).coerceAtLeast(0)
                    layout(row.width, shown) { row.place(0, shown - row.height) }
                },
        ) {
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
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).nestedScroll(collapse), content = content)
    }
}

/** Объяснение вместо пустого экрана: пустой раздел без слов читается как поломка. */
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
        // Колледж не выложил или сервер не нашёл лист — отсюда не видно; зато
        // видно, где лежит ответ.
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
