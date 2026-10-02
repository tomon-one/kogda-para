package ru.whensclass

import android.app.Application
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.whensclass.work.MidnightUpdater
import ru.whensclass.work.SyncWorker

class WhensClassApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Снимок сборок до 0.1.4 склеен с парами соседней подгруппы, а
        // виджеты теперь только о своей: перевести до первой перерисовки.
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { AppContainer.get(this@WhensClassApp).store.migrateGroups() }
            runCatching { AppContainer.get(this@WhensClassApp).store.forgetUnsupportedAfterUpdate() }
        }
        SyncWorker.schedule(this)
        CoroutineScope(Dispatchers.Default).launch { MidnightUpdater.schedule(this@WhensClassApp) }
        Notifications.ensureChannels(this)
        LessonAlarms.reschedule(this)
    }
}
