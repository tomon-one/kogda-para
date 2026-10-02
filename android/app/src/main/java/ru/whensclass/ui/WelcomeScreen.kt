package ru.whensclass.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import ru.whensclass.R

/**
 * Таблица, из которой берётся расписание, — книга целиком, без листа: адрес
 * листа приходит с сервера (`src_url`), а до первого ответа его нет. Листы
 * колледж переименовывает и заводит новые, а книга одна.
 */
private const val SCHEDULE_URL =
    "https://docs.google.com/spreadsheets/d/1FiMov0r4UUDKT6A56NWMImpoUakDC2YDevgaOpJQ7Qc/edit"

/**
 * Первый запуск, один раз: кто сделал приложение, откуда берётся расписание и
 * что о человеке ничего не собирают.
 */
@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Отступ от системных полос: у приветствия нет Scaffold, и при
            // enableEdgeToEdge логотип ушёл бы под часы.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_ngok),
            contentDescription = null,
            modifier = Modifier.height(44.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Когда пара?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Расписание НГОК на домашнем экране",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        Point(
            // «Для студентов и преподавателей»: преподаватель не должен читать
            // на первом экране «не для вас».
            "Сделано студентом",
            AnnotatedString("Для студентов и преподавателей. Колледж к приложению отношения не имеет."),
        )
        Point("Расписание — колледжа", scheduleSourceText())
        Point(
            "Приложение о вас ничего не собирает",
            AnnotatedString(
                // У преподавателя на сервер уходит его имя из таблицы; у всех —
                // выбранные группы, чьё чужое расписание смотрят, и номер
                // сборки (X-App-Build в ScheduleApi).
                "Серверу уходит только то, чьё расписание показать (группы, " +
                    "у преподавателя — имя из таблицы), и номер версии " +
                    "приложения. Ни номера телефона, ни местоположения.",
            ),
        )

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            // Кнопка называет действие, а не согласие с текстом выше; жмёт и
            // преподаватель — «Я преподаватель» на следующем экране.
            Text("Выбрать расписание", maxLines = 1, autoSize = FIT)
        }

        Text(
            "Создано Tomon",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** Абзац про источник расписания — со ссылкой прямо на таблицу колледжа. */
@Composable
private fun scheduleSourceText(): AnnotatedString = buildAnnotatedString {
    append("Оно берётся из ")
    withLink(
        LinkAnnotation.Url(
            SCHEDULE_URL,
            TextLinkStyles(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
        ),
    ) {
        append("гугл таблицы расписания")
    }
    // Отказ от ответственности целиком — в настройках: здесь, до первой пары,
    // он превратил бы экран в договор.
    append(" колледжа. Что написано там, то и покажет приложение.")
}

@Composable
private fun Point(title: String, text: AnnotatedString) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        // Точка выравнивается по первой строке заголовка, а не по верху блока.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 7.dp).size(8.dp),
        ) {}
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
