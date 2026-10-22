package dev.geminiquota.ui

import android.content.Context
import android.os.SystemClock
import androidx.annotation.ColorRes
import dev.geminiquota.R
import dev.geminiquota.data.PlanTier
import dev.geminiquota.data.UsageSnapshot
import dev.geminiquota.data.UsageWindow

enum class QuotaSeverity {
    OK,
    WARN,
    DANGER,
}

/** Presentation helpers shared by the widget and the app. */
object UsageFormat {

    private const val WARN_THRESHOLD = 0.60
    private const val DANGER_THRESHOLD = 0.85

    fun severity(window: UsageWindow?): QuotaSeverity {
        val used = window?.usedFraction ?: return QuotaSeverity.OK
        return when {
            used >= DANGER_THRESHOLD -> QuotaSeverity.DANGER
            used >= WARN_THRESHOLD -> QuotaSeverity.WARN
            else -> QuotaSeverity.OK
        }
    }

    fun worstSeverity(snapshot: UsageSnapshot?): QuotaSeverity {
        val windows = listOfNotNull(snapshot?.fiveHour, snapshot?.weekly)
        if (windows.isEmpty()) return QuotaSeverity.OK
        return windows.maxBy { severity(it).ordinal }.let { severity(it) }
    }

    @ColorRes
    fun severityColorRes(severity: QuotaSeverity): Int = when (severity) {
        QuotaSeverity.OK -> R.color.severity_ok
        QuotaSeverity.WARN -> R.color.severity_warn
        QuotaSeverity.DANGER -> R.color.severity_danger
    }

    /** Null when the tier is unknown, so the UI does not invent a plan name. */
    fun planLabel(tier: String?, context: Context): String? = when (PlanTier.fromRaw(tier)) {
        PlanTier.FREE -> context.getString(R.string.plan_free)
        PlanTier.AI_PRO -> context.getString(R.string.plan_ai_pro)
        PlanTier.AI_ULTRA -> context.getString(R.string.plan_ai_ultra)
        PlanTier.UNKNOWN -> null
    }

    fun percentText(context: Context, window: UsageWindow?): String =
        if (window == null) "—" else context.getString(R.string.percent_format, window.usedPercent)

    /**
     * Base timestamp for a count-down Chronometer. Chronometer counts against
     * elapsedRealtime, so convert the absolute reset time into that clock.
     */
    fun countdownBase(
        window: UsageWindow?,
        nowWallClockMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Long? {
        val reset = window?.resetAtEpochMs ?: return null
        if (reset <= nowWallClockMs) return null
        return nowElapsedMs + (reset - nowWallClockMs)
    }
}
