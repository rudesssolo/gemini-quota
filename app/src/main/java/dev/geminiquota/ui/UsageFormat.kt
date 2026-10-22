package dev.geminiquota.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.ColorRes
import dev.geminiquota.R
import dev.geminiquota.data.PlanTier
import dev.geminiquota.data.UsageSnapshot
import dev.geminiquota.data.UsageWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** How a window's reset time should be presented. */
    enum class ResetKind { NONE, SOON, TIMED }

    fun resetKind(
        resetAtEpochMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): ResetKind = when {
        resetAtEpochMs == null -> ResetKind.NONE
        resetAtEpochMs <= nowMs -> ResetKind.SOON
        else -> ResetKind.TIMED
    }

    /**
     * Absolute reset time, e.g. `Sun 25 Oct · 14:30`.
     *
     * A countdown was tried first and is wrong here: the widget only redraws
     * every 15 minutes, and a Chronometer that was never started shows `00:00`
     * — it also cannot be measured into a fixed-height widget. A wall-clock
     * time is always correct, needs no ticking and survives screen-off.
     */
    fun resetDateTime(
        resetAtEpochMs: Long,
        twentyFourHour: Boolean,
        locale: Locale,
    ): String {
        val pattern = if (twentyFourHour) "EEE d MMM · HH:mm" else "EEE d MMM · h:mm a"
        return SimpleDateFormat(pattern, locale).format(Date(resetAtEpochMs))
    }

    /** The reset line shared by the widget and the app, with status fallbacks. */
    fun resetLabel(
        context: Context,
        window: UsageWindow?,
        nowMs: Long = System.currentTimeMillis(),
    ): String = when (resetKind(window?.resetAtEpochMs, nowMs)) {
        ResetKind.NONE -> context.getString(
            if (window == null) R.string.window_not_reported else R.string.reset_unknown,
        )

        ResetKind.SOON -> context.getString(R.string.reset_soon)

        ResetKind.TIMED -> context.getString(
            R.string.resets_at,
            resetDateTime(
                resetAtEpochMs = window!!.resetAtEpochMs!!,
                twentyFourHour = DateFormat.is24HourFormat(context),
                locale = context.resources.configuration.locales[0],
            ),
        )
    }
}
