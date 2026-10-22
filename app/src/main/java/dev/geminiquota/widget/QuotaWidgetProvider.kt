package dev.geminiquota.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import dev.geminiquota.GeminiQuotaApp
import dev.geminiquota.data.UsageUiState
import dev.geminiquota.work.RefreshScheduler

class QuotaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val repository = (context.applicationContext as? GeminiQuotaApp)?.repository
        val state = repository?.currentState()
            ?: UsageUiState(isSignedIn = false, snapshot = null)
        val views = QuotaWidgetRenderer.buildViews(context, state)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }

        if (state.isSignedIn && !state.authRequired) {
            RefreshScheduler.ensurePeriodic(context)
            RefreshScheduler.requestImmediate(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_REFRESH) return

        val repository = (context.applicationContext as? GeminiQuotaApp)?.repository ?: return
        val state = repository.currentState()
        if (!state.isSignedIn) return

        // Show the tap immediately; the worker result will arrive via the
        // repository's StateFlow.
        QuotaWidgetRenderer.updateAll(context, state.copy(isRefreshing = true))
        RefreshScheduler.requestImmediate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RefreshScheduler.cancelAll(context)
    }

    companion object {
        const val ACTION_REFRESH = "dev.geminiquota.widget.REFRESH"
    }
}
