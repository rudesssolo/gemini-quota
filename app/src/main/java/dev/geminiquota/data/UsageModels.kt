package dev.geminiquota.data

import java.util.Locale
import kotlin.math.roundToInt

/**
 * One quota window as reported by gemini.google.com/usage.
 *
 * Gemini limits are compute based, so a window exposes a used fraction and the
 * moment it refreshes. There is no public "prompts remaining" number.
 */
data class UsageWindow(
    val usedFraction: Double,
    val resetAtEpochMs: Long?,
) {
    val remainingFraction: Double
        get() = (1.0 - usedFraction).coerceIn(0.0, 1.0)

    val usedPercent: Int
        get() = (usedFraction.coerceIn(0.0, 1.0) * 100).roundToInt()

    val remainingPercent: Int
        get() = 100 - usedPercent
}

enum class UsageSource {
    /** Primary source: GEMINI_WEB_GRAPHQL persisted UsagePageQuery. */
    GRAPHQL,

    /** Fallback: batchexecute RPC jSf9Qc used by the usage page. */
    BATCHEXECUTE,
}

data class UsageSnapshot(
    val fiveHour: UsageWindow?,
    val weekly: UsageWindow?,
    val tier: String?,
    val fetchedAtEpochMs: Long,
    val source: UsageSource,
) {
    val hasAnyWindow: Boolean
        get() = fiveHour != null || weekly != null

    /** Earliest future-ish refresh timestamp among the reported windows. */
    fun nextResetAtEpochMs(): Long? =
        listOfNotNull(fiveHour?.resetAtEpochMs, weekly?.resetAtEpochMs).minOrNull()
}

/**
 * Best-effort mapping of the subscription tier reported by the usage GraphQL
 * response. Google has not documented these values; unknown values stay
 * [UNKNOWN] so the UI does not invent a plan name.
 */
enum class PlanTier {
    FREE,
    AI_PRO,
    AI_ULTRA,
    UNKNOWN,
    ;

    companion object {
        fun fromRaw(raw: String?): PlanTier {
            val value = raw?.trim()?.uppercase(Locale.US).orEmpty()
            return when (value) {
                "", "NULL", "TIER0", "FREE", "FREE_TIER" -> if (value.isEmpty()) UNKNOWN else FREE
                "TIER1", "PRO" -> AI_PRO
                "TIER2", "TIER3", "ULTRA" -> AI_ULTRA
                else -> UNKNOWN
            }
        }
    }
}
