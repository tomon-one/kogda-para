package ru.whensclass.widget

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Невидимое окно, которое кладёт ссылку на занятие в буфер обмена.
 *
 * Начиная с Android 10 писать в буфер разрешено только приложению на переднем
 * плане. Виджет на домашнем экране передним планом не считается, и вызов из
 * него система молча отбрасывает — нажатие срабатывало, а ссылка не
 * копировалась. Поэтому нажатие открывает это окно: оно на мгновение получает
 * фокус, копирует и сразу закрывается.
 */
class CopyLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        copy(intent?.getStringExtra(EXTRA_URL))
        finish()
        // Без анимации: человек не должен заметить, что что-то открывалось.
        overridePendingTransition(0, 0)
    }

    private fun copy(url: String?) {
        if (url.isNullOrBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Ссылка на занятие", url))
        // С Android 13 система сама показывает, что скопировано, — свой ответ
        // был бы вторым подряд.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, CopyLinkActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
