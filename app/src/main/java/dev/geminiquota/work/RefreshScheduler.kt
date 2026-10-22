package dev.geminiquota.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.geminiquota.data.UsageSnapshot
import java.util.concurrent.TimeUnit

/**
 * Refresh policy (battery-friendly "as real as Android allows"):
 * - periodic WorkManager refresh, 15 min floor (the platform minimum)
 * - one-shot refresh on widget tap / app open
 * - one-shot refresh shortly after the next quota window resets
 *
 * Between refreshes the widget ticks its own countdown, so it still feels live
 * without waking the app.
 */
object RefreshScheduler {

    const val PERIODIC_WORK = "gemini-quota-periodic"
    const val IMMEDIATE_WORK = "gemini-quota-immediate"
    const val RESET_WORK = "gemini-quota-reset"
    const val REFRESH_INTERVAL_MINUTES = 15L

    private const val RESET_GRACE_MS = 60_000L
    private const val MAX_RESET_DELAY_MS = 8L * 24 * 60 * 60 * 1_000L

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(
            REFRESH_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun requestImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun scheduleAtNextReset(
        context: Context,
        snapshot: UsageSnapshot?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        val nextReset = snapshot?.nextResetAtEpochMs() ?: return
        val delay = nextReset + RESET_GRACE_MS - nowEpochMs
        if (delay <= 0L || delay > MAX_RESET_DELAY_MS) return

        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RESET_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(IMMEDIATE_WORK)
        workManager.cancelUniqueWork(RESET_WORK)
        workManager.cancelUniqueWork(PERIODIC_WORK)
    }
}
