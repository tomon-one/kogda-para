package ru.whensclass.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import ru.whensclass.AppContainer
import ru.whensclass.data.RefreshResult

/** Периодическое обновление расписания в фоне. */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        val result = container.repository.refresh()
        // Заодно смотрим, не вышла ли новая сборка: раз в час — ровно та
        // частота, с которой об этом стоит узнавать.
        runCatching { container.updates.announceIfNew(container.store) }
        // Повтор с паузой — только у разовой работы. У периодической retry()
        // подменял час экспоненциальной паузой до пяти часов, и после
        // починки сервера телефон часами держал «сбой»: она придёт сама через час.
        return when {
            result is RefreshResult.Failed && ONE_SHOT_TAG in tags -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "sync"
        private const val ONE_SHOT = "sync-now"
        private const val ONE_SHOT_TAG = "sync-one-shot"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Обновить прямо сейчас: открыли приложение, сменили группу, нажали кнопку.
         *
         * Без setExpedited. Срочная работа на Android 8–11 требует
         * getForegroundInfo, а его у нас нет: WorkManager 2.11 валил такую
         * работу до doWork, и обновление при открытии, при установке виджета
         * и ночью не выполнялось вовсе. Обычная разовая
         * работа с сетью и так запускается почти сразу.
         */
        fun now(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag(ONE_SHOT_TAG)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
