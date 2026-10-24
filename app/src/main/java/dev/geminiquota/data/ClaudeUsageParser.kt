package dev.geminiquota.data

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * Parses `claude.ai/api/organizations/{orgId}/usage`.
 *
 * Shape (verified against two independent reverse-engineering write-ups):
 * ```
 * { "five_hour": {"utilization": 17.0, "resets_at": "2026-02-08T18:59:59Z"},
 *   "seven_day": {...}, "seven_day_opus": {...} | null, "extra_usage": null }
 * ```
 * `utilization` is already a percentage, unlike Gemini's fraction, and null
 * means "this plan has no such window" rather than "zero used".
 */
object ClaudeUsageParser {

    const val SOURCE = "CLAUDE_WEB"

    private const val FIVE_HOUR = "five_hour"
    private const val SEVEN_DAY = "seven_day"
    private const val SEVEN_DAY_OPUS = "seven_day_opus"

    /** Every key the endpoint is known to emit, including the plan-specific ones. */
    private val KNOWN_KEYS = listOf(
        FIVE_HOUR,
        SEVEN_DAY,
        SEVEN_DAY_OPUS,
        "seven_day_sonnet",
        "seven_day_oauth_apps",
        "seven_day_cowork",
        "extra_usage",
    )

    fun parse(body: String, nowEpochMs: Long): QuotaPage {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw UsageException.ProtocolChanged(
                "Claude usage response was not valid JSON",
                responseSnippet(body),
            )
        }

        val windows = listOfNotNull(
            window(json, FIVE_HOUR, QuotaWindowId.FIVE_HOUR),
            window(json, SEVEN_DAY, QuotaWindowId.WEEKLY),
            window(json, SEVEN_DAY_OPUS, QuotaWindowId.WEEKLY_OPUS),
        )

        // Free plans may legitimately report every window as null. That is a
        // valid answer, not a schema change: only a payload that carries none of
        // the keys we know about means the endpoint moved on.
        if (windows.isEmpty() && KNOWN_KEYS.none { json.has(it) }) {
            throw UsageException.ProtocolChanged(
                "Claude usage response had no quota windows",
                responseSnippet(body),
            )
        }

        return QuotaPage(
            provider = ProviderId.CLAUDE,
            tier = null,
            windows = windows,
            fetchedAtEpochMs = nowEpochMs,
            source = SOURCE,
        )
    }

    private fun window(json: JSONObject, key: String, id: QuotaWindowId): QuotaWindow? {
        if (!json.has(key) || json.isNull(key)) return null
        val entry = json.optJSONObject(key)
            ?: throw UsageException.ProtocolChanged(
                "Claude usage field $key was not an object",
                responseSnippet(json.optString(key)),
            )

        val utilization = if (entry.has("utilization") && !entry.isNull("utilization")) {
            entry.optDouble("utilization", Double.NaN)
        } else {
            Double.NaN
        }
        if (utilization.isNaN()) {
            throw UsageException.ProtocolChanged(
                "Claude usage field $key had no utilization",
                responseSnippet(entry.toString()),
            )
        }

        val resetAt = if (entry.has("resets_at") && !entry.isNull("resets_at")) {
            parseTimestamp(entry.optString("resets_at"))
        } else {
            null
        }

        return QuotaWindow(
            id = id,
            usedPercent = utilization.roundToInt().coerceIn(0, 100),
            resetAtEpochMs = resetAt,
        )
    }

    /** `resets_at` comes as ISO-8601, usually `Z`, occasionally with an offset. */
    private fun parseTimestamp(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .getOrNull()
    }
}
