package dev.geminiquota.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.geminiquota.GeminiQuotaApp
import dev.geminiquota.data.RefreshOutcome

/**
 * Fetches usage in the background. Auth failures are terminal for this run:
 * retrying cannot fix an expired session, so the widget asks the user instead.
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = (applicationContext as? GeminiQuotaApp)?.repository
            ?: return Result.failure()

        return when (repository.refresh()) {
            RefreshOutcome.SUCCESS -> {
                RefreshScheduler.scheduleAtNextReset(
                    applicationContext,
                    repository.currentState().snapshot,
                )
                Result.success()
            }

            RefreshOutcome.NOT_SIGNED_IN,
            RefreshOutcome.AUTH_REQUIRED,
            RefreshOutcome.ALREADY_RUNNING,
            -> Result.success()

            RefreshOutcome.FAILED ->
                if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
