package dev.geminiquota.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.geminiquota.MainActivity
import dev.geminiquota.R
import dev.geminiquota.data.UsageUiState
import dev.geminiquota.data.UsageWindow
import dev.geminiquota.ui.Ramp
import dev.geminiquota.ui.UsageFormat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds the RemoteViews for every widget instance. Pure rendering only: the
 * provider and the repository decide when to call this.
 */
object QuotaWidgetRenderer {

    private const val REQUEST_OPEN_APP = 100
    private const val REQUEST_REFRESH = 101

    /** Ten frames over ~400 ms: the finest motion a launcher will render for a widget. */
    private const val RAMP_STEPS = 9
    private const val RAMP_STEP_MS = 42L

    private val rampExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "quota-widget-ramp").apply { isDaemon = true }
    }
    private val ramping = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private var lastPercents: Pair<Int, Int>? = null
    private var lastFetchedAt: Long? = null

    fun updateAll(context: Context, state: UsageUiState) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, QuotaWidgetProvider::class.java),
        )
        if (ids.isEmpty()) return

        val views = buildViews(context, state)

        val target = state.percentPair()
        val fetchedAt = state.snapshot?.fetchedAtEpochMs
        val from = lastPercents
        val isNewFetch = fetchedAt != null && fetchedAt != lastFetchedAt
        lastPercents = target
        lastFetchedAt = fetchedAt
        val myGeneration = generation.incrementAndGet()

        // Only a genuinely new set of numbers is worth animating: a state change
        // that merely toggles the spinner should not replay the motion.
        val animate = from != null && target != null && from != target && isNewFetch &&
            ramping.compareAndSet(false, true)

        if (!animate) {
            ids.forEach { id -> manager.updateAppWidget(id, views) }
            return
        }

        rampExecutor.execute {
            try {
                for (step in 1..RAMP_STEPS) {
                    // A newer state arrived: drop this run, it already drew itself.
                    if (generation.get() != myGeneration) return@execute

                    val fraction = step / RAMP_STEPS.toFloat()
                    val five = Ramp.valueAt(from.first, target.first, fraction)
                    val weekly = Ramp.valueAt(from.second, target.second, fraction)
                    views.setProgressBar(R.id.progress_five, 100, five, false)
                    views.setProgressBar(R.id.progress_weekly, 100, weekly, false)
                    views.setTextViewText(
                        R.id.percent_five,
                        context.getString(R.string.percent_format, five),
                    )
                    views.setTextViewText(
                        R.id.percent_weekly,
                        context.getString(R.string.percent_format, weekly),
                    )
                    ids.forEach { id -> manager.updateAppWidget(id, views) }
                    if (step < RAMP_STEPS) Thread.sleep(RAMP_STEP_MS)
                }
            } catch (e: Exception) {
                // A launcher that refuses rapid updates must not take the app down.
            } finally {
                ramping.set(false)
            }
        }
    }

    private fun UsageUiState.percentPair(): Pair<Int, Int>? {
        val current = snapshot ?: return null
        return (current.fiveHour?.usedPercent ?: 0) to (current.weekly?.usedPercent ?: 0)
    }

    fun buildViews(context: Context, state: UsageUiState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quota)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java),
            flags,
        )
        val refresh = PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH,
            Intent(context, QuotaWidgetProvider::class.java)
                .setAction(QuotaWidgetProvider.ACTION_REFRESH),
            flags,
        )
        views.setOnClickPendingIntent(R.id.widget_root, openApp)
        views.setOnClickPendingIntent(R.id.state_container, openApp)
        views.setOnClickPendingIntent(R.id.refresh, refresh)

        val snapshot = state.snapshot
        val planLabel = UsageFormat.planLabel(snapshot?.tier, context)
        if (planLabel != null) {
            views.setTextViewText(R.id.plan_chip, planLabel)
            views.setViewVisibility(R.id.plan_chip, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.plan_chip, View.GONE)
        }

        val hasData = state.hasData
        views.setViewVisibility(
            R.id.data_container,
            if (hasData) View.VISIBLE else View.GONE,
        )
        views.setViewVisibility(
            R.id.state_container,
            if (hasData) View.GONE else View.VISIBLE,
        )
        // The footer now shares the title row, so it must not repeat the
        // empty-state text that state_container already shows.
        views.setViewVisibility(
            R.id.footer,
            if (hasData) View.VISIBLE else View.GONE,
        )
        if (hasData) {
            views.setViewVisibility(R.id.status_dot, View.VISIBLE)
            views.setTextColor(
                R.id.status_dot,
                context.getColor(
                    UsageFormat.severityColorRes(
                        UsageFormat.worstSeverity(snapshot),
                    ),
                ),
            )
        } else {
            views.setViewVisibility(R.id.status_dot, View.GONE)
            bindEmptyState(context, views, state)
        }

        bindWindow(
            context, views,
            percentId = R.id.percent_five,
            progressId = R.id.progress_five,
            resetId = R.id.reset_five,
            window = snapshot?.fiveHour,
        )
        bindWindow(
            context, views,
            percentId = R.id.percent_weekly,
            progressId = R.id.progress_weekly,
            resetId = R.id.reset_weekly,
            window = snapshot?.weekly,
        )

        views.setTextViewText(R.id.footer, footerText(context, state))
        views.setViewVisibility(
            R.id.loading,
            if (state.isRefreshing) View.VISIBLE else View.GONE,
        )
        return views
    }

    private fun bindWindow(
        context: Context,
        views: RemoteViews,
        percentId: Int,
        progressId: Int,
        resetId: Int,
        window: UsageWindow?,
    ) {
        if (window == null) {
            views.setTextViewText(percentId, "—")
            views.setTextColor(percentId, context.getColor(R.color.text_secondary))
            views.setProgressBar(progressId, 100, 0, false)
            views.setTextViewText(resetId, context.getString(R.string.window_not_reported))
            return
        }

        views.setTextViewText(percentId, UsageFormat.percentText(context, window))
        views.setTextColor(
            percentId,
            context.getColor(UsageFormat.severityColorRes(UsageFormat.severity(window))),
        )
        views.setProgressBar(progressId, 100, window.usedPercent, false)
        views.setTextViewText(resetId, UsageFormat.resetLabel(context, window))
    }

    private fun bindEmptyState(context: Context, views: RemoteViews, state: UsageUiState) {
        val titleRes: Int
        val bodyRes: Int
        when {
            !state.isSignedIn -> {
                titleRes = R.string.widget_connect_title
                bodyRes = R.string.widget_connect_body
            }

            state.authRequired -> {
                titleRes = R.string.widget_session_expired
                bodyRes = R.string.widget_expired_body
            }

            state.errorKind != null -> {
                titleRes = R.string.widget_error_title
                bodyRes = R.string.widget_error_body
            }

            else -> {
                titleRes = R.string.widget_loading
                bodyRes = R.string.widget_loading_body
            }
        }
        views.setTextViewText(R.id.state_title, context.getString(titleRes))
        views.setTextViewText(R.id.state_body, context.getString(bodyRes))
    }

    private fun footerText(context: Context, state: UsageUiState): String {
        if (!state.isSignedIn) return context.getString(R.string.widget_signed_out_footer)
        if (state.authRequired) return context.getString(R.string.widget_session_expired)
        if (state.isRefreshing) return context.getString(R.string.refreshing)

        val snapshot = state.snapshot
        if (snapshot != null) {
            val updated = UsageFormat.updatedLabel(context, snapshot.fetchedAtEpochMs)
            return if (state.errorKind != null) {
                "$updated · ${context.getString(R.string.offline_suffix)}"
            } else {
                updated
            }
        }

        if (state.errorKind != null) return context.getString(R.string.widget_load_failed)
        return context.getString(R.string.widget_loading)
    }
}
