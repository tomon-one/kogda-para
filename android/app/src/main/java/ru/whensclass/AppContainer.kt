package ru.whensclass

import android.content.Context
import ru.whensclass.data.AppUpdate
import ru.whensclass.data.ScheduleApi
import ru.whensclass.data.ScheduleRepository
import ru.whensclass.data.ScheduleStore

/**
 * Сборка зависимостей руками.
 *
 * В приложении из трёх экранов и одного виджета библиотека внедрения даёт
 * больше возни со сборкой, чем пользы.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val store = ScheduleStore(app)
    private val api = ScheduleApi(app.cacheDir)
    val repository = ScheduleRepository(app, api, store)
    val updates = AppUpdate(app, api)

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
