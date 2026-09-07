package ru.whensclass

import android.app.Application
import ru.whensclass.notify.LessonAlarms
import ru.whensclass.notify.Notifications
import ru.whensclass.work.MidnightUpdater
import ru.whensclass.work.SyncWorker

class WhensClassApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
        MidnightUpdater.schedule(this)
        Notifications.ensureChannels(this)
        LessonAlarms.reschedule(this)
    }
}
