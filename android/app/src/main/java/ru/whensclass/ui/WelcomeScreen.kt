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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.whensclass.R

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
            "Приложение неофициальное: колледж к нему отношения не имеет и за " +
                "него не отвечает.",
        )
        Point(
            "Расписание не наше",
            "Оно берётся из общей таблицы колледжа. Что написано там — то и " +
                "покажет приложение. За ошибки в расписании и за пропущенные " +
                "пары мы не отвечаем: когда это важно, сверяйтесь с таблицей.",
        )
        Point(
            "О вас ничего не собирается",
            "Ни имени, ни номера, ни местоположения. На сервер уходит только " +
                "название выбранной группы — иначе непонятно, чьё расписание " +
                "присылать. Учётной записи нет, рекламы и слежки тоже.",
        )

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Понятно, выбрать группу")
        }
    }
}

@Composable
private fun Point(title: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(8.dp).padding(top = 0.dp),
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
