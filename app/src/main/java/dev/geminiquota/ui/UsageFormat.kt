package dev.geminiquota.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.ColorRes
import dev.geminiquota.R
import dev.geminiquota.data.PlanTier
import dev.geminiquota.data.UsageSnapshot
import dev.geminiquota.data.UsageWindow
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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

    /**
     * "Updated just now" under a minute, otherwise "Updated 4 min ago". Shared so
     * the widget and the dashboard never disagree, and so a fresh fetch does not
     * read as the odd "Updated 0 min ago".
     */
    fun updatedLabel(
        context: Context,
        fetchedAtEpochMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        if (nowMs - fetchedAtEpochMs < DateUtils.MINUTE_IN_MILLIS) {
            return context.getString(R.string.updated_just_now)
        }
        val relative = DateUtils.getRelativeTimeSpanString(
            fetchedAtEpochMs,
            nowMs,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
        return context.getString(R.string.updated_relative, relative)
    }

    /** How a window's reset time should be presented. */
    enum class ResetKind { NONE, SOON, TIMED }

    /** Calendar relation of a reset to today, so nearby resets read naturally. */
    enum class ResetDay { TODAY, TOMORROW, OTHER }

    fun resetKind(
        resetAtEpochMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): ResetKind = when {
        resetAtEpochMs == null -> ResetKind.NONE
        resetAtEpochMs <= nowMs -> ResetKind.SOON
        else -> ResetKind.TIMED
    }

    fun resetDay(
        resetAtEpochMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ResetDay {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val reset = Instant.ofEpochMilli(resetAtEpochMs).atZone(zone).toLocalDate()
        return when (ChronoUnit.DAYS.between(today, reset)) {
            0L -> ResetDay.TODAY
            1L -> ResetDay.TOMORROW
            else -> ResetDay.OTHER
        }
    }

    /** Just the time of day, e.g. `03:10` — used for today/tomorrow resets. */
    fun resetClock(
        resetAtEpochMs: Long,
        twentyFourHour: Boolean,
        locale: Locale,
    ): String {
        val pattern = if (twentyFourHour) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, locale).format(Date(resetAtEpochMs))
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

        ResetKind.TIMED -> {
            // "today"/"tomorrow" beat a date the user has to decode: the 5-hour
            // reset is almost always today, the weekly one within a week.
            val reset = window!!.resetAtEpochMs!!
            val twentyFourHour = DateFormat.is24HourFormat(context)
            val locale = context.resources.configuration.locales[0]
            when (resetDay(reset, nowMs)) {
                ResetDay.TODAY -> context.getString(
                    R.string.resets_today,
                    resetClock(reset, twentyFourHour, locale),
                )

                ResetDay.TOMORROW -> context.getString(
                    R.string.resets_tomorrow,
                    resetClock(reset, twentyFourHour, locale),
                )

                ResetDay.OTHER -> context.getString(
                    R.string.resets_at,
                    resetDateTime(reset, twentyFourHour, locale),
                )
            }
        }
    }
}
