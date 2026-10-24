package dev.geminiquota.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Parses the GEMINI_WEB_GRAPHQL UsagePageQuery response.
 *
 * Shape (via CodexBar's documented gemini-web plugin):
 * ```
 * [ { "data": { "viewer": { "rpcs": { "getUsageInfo": {
 *       "subscriptionTier": "TIER1",
 *       "pools": [
 *         { "poolType": "GXU_FIVE_HOURLY", "percentageUsed": 0.42,
 *           "gxu": { "windowRefreshesAt": "2026-10-22T23:00:00Z" } },
 *         { "poolType": "GXU_WEEKLY", "percentageUsed": 0.10, "gxu": { ... } }
 *       ] } } } } } ]
 * ```
 */
object GraphqlUsageParser {

    const val FIVE_HOUR_POOL = "GXU_FIVE_HOURLY"
    const val WEEKLY_POOL = "GXU_WEEKLY"

    fun parse(body: String, fetchedAtEpochMs: Long): UsageSnapshot {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            throw UsageException.AuthRequired("Gemini returned an empty usage response")
        }
        if (trimmed.startsWith("<")) {
            throw UsageException.AuthRequired("Gemini returned a sign-in page instead of usage data")
        }

        // Some Google endpoints prefix JSON with an XSSI guard.
        val jsonText = trimmed.removePrefix(")]}'").trim()
        val root = try {
            JSONTokener(jsonText).nextValue()
        } catch (e: JSONException) {
            throw UsageException.ProtocolChanged(
                "Gemini usage response was not valid JSON",
                responseSnippet(trimmed),
            )
        }

        if (root !is JSONObject && root !is JSONArray) {
            throw UsageException.ProtocolChanged(
                "Gemini usage response was not valid JSON",
                responseSnippet(trimmed),
            )
        }

        val data = findDataWithViewer(root)
            ?: throw UsageException.ProtocolChanged(
                "Gemini usage response had no viewer section",
                responseSnippet(trimmed),
            )

        if (!data.has("viewer") || data.isNull("viewer")) {
            throw UsageException.AuthRequired("Gemini session is not signed in")
        }
        val viewer = data.optJSONObject("viewer")
            ?: throw UsageException.ProtocolChanged(
                "Gemini viewer section had an unexpected type",
                responseSnippet(trimmed),
            )

        val usage = viewer.optJSONObject("rpcs")?.optJSONObject("getUsageInfo")
            ?: throw UsageException.ProtocolChanged(
                "Gemini usage response had no getUsageInfo",
                responseSnippet(trimmed),
            )
        val pools = usage.optJSONArray("pools")
            ?: throw UsageException.ProtocolChanged(
                "Gemini usage response had no pools",
                responseSnippet(trimmed),
            )

        val fiveHour = findPool(pools, FIVE_HOUR_POOL)
        val weekly = findPool(pools, WEEKLY_POOL)
        if (fiveHour == null && weekly == null) {
            throw UsageException.ProtocolChanged(
                "No Gemini usage windows were reported for this account",
                responseSnippet(trimmed),
            )
        }

        val tier = usage.optString("subscriptionTier")
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

        return UsageSnapshot(
            fiveHour = fiveHour,
            weekly = weekly,
            tier = tier,
            fetchedAtEpochMs = fetchedAtEpochMs,
            source = UsageSource.GRAPHQL,
        )
    }

    private fun findDataWithViewer(root: Any?): JSONObject? {
        val candidates = mutableListOf<JSONObject>()
        when (root) {
            is JSONArray -> {
                for (index in 0 until root.length()) {
                    val item = root.optJSONObject(index) ?: continue
                    item.optJSONObject("data")?.let(candidates::add)
                }
            }

            is JSONObject -> {
                val data = root.optJSONObject("data")
                if (data != null) candidates += data else candidates += root
            }

            else -> return null
        }
        return candidates.firstOrNull { it.has("viewer") } ?: candidates.firstOrNull()
    }

    private fun findPool(pools: JSONArray, poolType: String): UsageWindow? {
        for (index in 0 until pools.length()) {
            val pool = pools.optJSONObject(index) ?: continue
            if (!poolType.equals(pool.optString("poolType"), ignoreCase = true)) continue
            val used = pool.optDouble("percentageUsed", Double.NaN)
            if (used.isNaN() || used < 0.0) continue
            val reset = parseTimestamp(pool.optJSONObject("gxu")?.optString("windowRefreshesAt"))
            return UsageWindow(
                usedFraction = used.coerceIn(0.0, 1.0),
                resetAtEpochMs = reset,
            )
        }
        return null
    }

    private fun parseTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
