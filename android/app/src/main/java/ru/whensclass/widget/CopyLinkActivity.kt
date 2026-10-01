package ru.whensclass.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import ru.whensclass.data.isKnownWebinar
import ru.whensclass.ui.copyToClipboard

/**
 * Невидимое окно, которое кладёт ссылку на занятие в буфер обмена. С Android
 * 10 писать в буфер может только приложение на переднем плане, а виджет им не
 * считается — вызов из него система молча отбрасывает. Окно на мгновение
 * получает фокус, копирует и сразу закрывается.
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
        // Виджеты чужой адрес сюда не шлют; если всё же пришёл — не копируем.
        if (!isKnownWebinar(url)) {
            Toast.makeText(this, "Ссылка на чужой адрес — откройте пару в приложении", Toast.LENGTH_LONG).show()
            return
        }
        copyToClipboard(this, "Ссылка на занятие", url, "Ссылка скопирована")
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, CopyLinkActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
