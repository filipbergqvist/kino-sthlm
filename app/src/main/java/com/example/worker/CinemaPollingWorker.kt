package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.repository.CinemaWatchlistRepository
import java.util.concurrent.TimeUnit

/**
 * Completely automated background worker that polls Stockholm cinema websites
 * for movie showings matching the user's IMDb & Google TV watchlist.
 */
class CinemaPollingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("CinemaPollingWorker", "Automated Stockholm cinema polling job started...")
        val repository = CinemaWatchlistRepository.getInstance(applicationContext)

        return try {
            val report = repository.scanStockholmCinemas()
            Log.d("CinemaPollingWorker", "Poll finished. Result: ${report.statusMessage}")
            Result.success()
        } catch (e: Exception) {
            Log.e("CinemaPollingWorker", "Worker exception: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val PERIODIC_WORK_TAG = "stockholm_cinema_periodic_poll"
        const val ONE_TIME_WORK_TAG = "stockholm_cinema_manual_poll"

        /**
         * Sets up automated background polling at the specified interval (e.g. 2 hours, 6 hours).
         */
        fun schedulePeriodic(context: Context, intervalHours: Long = 2) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<CinemaPollingWorker>(
                intervalHours, TimeUnit.HOURS,
                15, TimeUnit.MINUTES // Flex window
            )
                .setConstraints(constraints)
                .addTag(PERIODIC_WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            Log.d("CinemaPollingWorker", "Scheduled periodic polling every $intervalHours hours.")
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_TAG)
            Log.d("CinemaPollingWorker", "Cancelled periodic polling.")
        }

        fun triggerImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<CinemaPollingWorker>()
                .setConstraints(constraints)
                .addTag(ONE_TIME_WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_TAG,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }
}
