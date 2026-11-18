package ru.whensclass.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// То, что приложение делает с телефоном помимо экранов: разрешение на уведомления и значки
// системной полосы.

/** Разрешение на уведомления спрашиваем сами: с Android 13 оно не выдаётся по умолчанию. */
@Composable
internal fun AskNotificationPermission(granted: Boolean) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Один раз, а не на каждое пересоздание экрана (поворот). Отказ ничего не ломает — в настройках
    // останется подсказка.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted) {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Тема внутри приложения, а значки системной полосы снаружи: без связки светлая тема при тёмной
 * системе даёт белые значки на белом.
 */
@Composable
internal fun ApplyDarkTheme(dark: Boolean, scheme: androidx.compose.material3.ColorScheme) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as android.app.Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        // До Android 10 полосу навигации красит enableEdgeToEdge — по теме системы, а не приложения.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = scheme.background.toArgb()
        }
    }
}
