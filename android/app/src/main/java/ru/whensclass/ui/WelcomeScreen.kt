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

/** Та самая таблица, из которой берётся расписание. */
private const val SCHEDULE_URL =
    "https://docs.google.com/spreadsheets/d/" +
        "1FiMov0r4UUDKT6A56NWMImpoUakDC2YDevgaOpJQ7Qc/edit?gid=656498718"

/**
 * Первый запуск: коротко о том, что это за приложение и чего от него не ждать.
 *
 * Показывается один раз. Три вещи, которые человек имеет право узнать до того,
 * как начнёт этим пользоваться: кто это сделал, откуда берётся расписание и
 * что о нём самом ничего не собирают.
 */
@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
            "Сделано студентом для студентов",
            AnnotatedString("Приложение неофициальное, и колледж к нему отношения не имеет."),
        )
        Point("Расписание не наше", scheduleSourceText())
        Point(
            "О вас ничего не собирается",
            AnnotatedString(
                "Ни имени, ни номера, ни местоположения. На сервер уходит только " +
                    "то, чьё расписание показывать: название группы или фамилия " +
                    "преподавателя.",
            ),
        )

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Понятно, выбрать группу")
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
    append(
        " колледжа. Что написано там, то и покажет приложение. За ошибки в " +
            "расписании и за пропущенные пары я не отвечаю, и когда это важно, " +
            "сверяйтесь с таблицей.",
    )
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
