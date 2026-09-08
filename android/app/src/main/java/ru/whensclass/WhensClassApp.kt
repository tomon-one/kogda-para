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
        SyncWorker.schedule(this)
        CoroutineScope(Dispatchers.Default).launch { MidnightUpdater.schedule(this@WhensClassApp) }
        Notifications.ensureChannels(this)
        LessonAlarms.reschedule(this)
    }
}
