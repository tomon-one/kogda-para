package ru.whensclass.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import ru.whensclass.data.isKnownWebinar
import ru.whensclass.ui.copyToClipboard

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
        // Виджеты чужой адрес сюда не шлют; если всё же пришёл — не копируем
        // молча.
        if (!isKnownWebinar(url)) {
            Toast.makeText(this, "Ссылка на чужой адрес — откройте пару в приложении", Toast.LENGTH_LONG).show()
            return
        }
        // Через общую функцию: здесь та же логика была набрана заново, и при
        // копировании в неё дважды попала одна и та же проверка версии —
        // вложенная сама в себя.
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
