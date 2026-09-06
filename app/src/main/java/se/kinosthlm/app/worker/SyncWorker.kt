package se.kinosthlm.app.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import se.kinosthlm.app.data.repository.KinoRepository

/**
 * The scheduled half of the app: refresh watchlists, poll cinemas, notify.
 *
 * It runs exactly the same [KinoRepository.sync] the Refresh button calls, so background and
 * manual results can never diverge.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result =
    try {
      val report = KinoRepository.getInstance(applicationContext).sync()
      Log.d(TAG, "Sync finished: ${report.statusMessage}")
      // sync() reports source failures in its result rather than throwing, and a cinema being
      // down is not worth a retry storm — the next scheduled run will pick it up.
      Result.success()
    } catch (error: Exception) {
      Log.e(TAG, "Sync failed", error)
      if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

  companion object {
    private const val TAG = "SyncWorker"
    private const val MAX_ATTEMPTS = 3

    const val PERIODIC_WORK = "kinosthlm_periodic_sync"
    const val MANUAL_WORK = "kinosthlm_manual_sync"

    private val constraints =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * (Re)schedule the recurring sync. UPDATE keeps the existing work's history, so changing the
     * interval does not reset the schedule or trigger an immediate run.
     *
     * [hourOfDay] is when the first run of each period should land, in local time. Cinema
     * programmes change once a day at most, so the point of syncing is to have looked *today*,
     * not to have looked recently — and a fixed hour means it happens overnight rather than
     * whenever the app last happened to be opened. WorkManager treats it as an initial delay,
     * not a guarantee; it will still defer for Doze and network.
     */
    fun schedulePeriodic(context: Context, intervalHours: Long, hourOfDay: Int) {
      val request =
        PeriodicWorkRequestBuilder<SyncWorker>(intervalHours, TimeUnit.HOURS)
          .setConstraints(constraints)
          .setInitialDelay(minutesUntil(hourOfDay), TimeUnit.MINUTES)
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
          .build()

      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Minutes from now until the next [hourOfDay] in local time. */
    internal fun minutesUntil(hourOfDay: Int, now: LocalDateTime = LocalDateTime.now()): Long {
      val target = now.toLocalDate().atTime(hourOfDay.coerceIn(0, 23), 0)
      val next = if (target.isAfter(now)) target else target.plusDays(1)
      return Duration.between(now, next).toMinutes()
    }

    fun cancelPeriodic(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    /** Fire a sync now. Used by the Refresh button so it survives the app being backgrounded. */
    fun syncNow(context: Context) {
      val request =
        OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
      WorkManager.getInstance(context)
        .enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.REPLACE, request)
    }
  }
}
