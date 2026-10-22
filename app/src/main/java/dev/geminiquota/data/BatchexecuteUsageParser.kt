package dev.geminiquota.data

import org.json.JSONArray
import org.json.JSONException

/**
 * Parses the batchexecute response for RPC `jSf9Qc` (the call the Gemini usage
 * page makes). Rows look like:
 *
 * ```
 * [remainingCompute, usedRatio, type, [[resetEpochSeconds, resetMicros]]]
 * ```
 *
 * `type` 1 is the rolling 5-hour window, 2 is the weekly window.
 */
object BatchexecuteUsageParser {

    const val RPC_ID = "jSf9Qc"
    private const val TYPE_FIVE_HOUR = 1
    private const val TYPE_WEEKLY = 2

    fun parse(body: String, fetchedAtEpochMs: Long): UsageSnapshot {
        val frames = parseFrames(body)
        var payloadText: String? = null

        frame@ for (frame in frames) {
            for (index in 0 until frame.length()) {
                val entry = frame.optJSONArray(index) ?: continue
                if (entry.optString(0) == "wrb.fr" && entry.optString(1) == RPC_ID) {
                    payloadText = entry.optString(2, "")
                    break@frame
                }
            }
        }

        if (payloadText.isNullOrBlank()) {
            throw GeminiUsageException.ProtocolChanged(
                "Gemini usage RPC response was missing",
                responseSnippet(body),
            )
        }

        val payload = try {
            JSONArray(payloadText)
        } catch (e: JSONException) {
            throw GeminiUsageException.ProtocolChanged(
                "Gemini usage RPC payload was not a JSON array",
                responseSnippet(payloadText),
            )
        }

        val rows = payload.optJSONArray(1)
            ?: throw GeminiUsageException.ProtocolChanged(
                "Gemini usage RPC payload had no rows",
                responseSnippet(payloadText),
            )

        var fiveHour: UsageWindow? = null
        var weekly: UsageWindow? = null
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val type = row.optInt(2, -1)
            val used = row.optDouble(1, Double.NaN)
            if (used.isNaN()) continue
            val window = UsageWindow(
                usedFraction = used.coerceIn(0.0, 1.0),
                resetAtEpochMs = parseReset(row.optJSONArray(3)),
            )
            when (type) {
                TYPE_FIVE_HOUR -> fiveHour = window
                TYPE_WEEKLY -> weekly = window
            }
        }

        if (fiveHour == null && weekly == null) {
            throw GeminiUsageException.ProtocolChanged(
                "Gemini usage RPC did not report any usage windows",
                responseSnippet(body),
            )
        }

        return UsageSnapshot(
            fiveHour = fiveHour,
            weekly = weekly,
            tier = null,
            fetchedAtEpochMs = fetchedAtEpochMs,
            source = UsageSource.BATCHEXECUTE,
        )
    }

    /** Batchexecute streams newline-delimited JSON frames with length markers. */
    private fun parseFrames(body: String): List<JSONArray> {
        val frames = mutableListOf<JSONArray>()
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("[")) continue
            try {
                frames += JSONArray(trimmed)
            } catch (_: JSONException) {
                // Length lines and partial frames are expected; ignore them.
            }
        }
        return frames
    }

    private fun parseReset(reset: JSONArray?): Long? {
        val timestamp = reset?.optJSONArray(0) ?: return null
        val seconds = timestamp.optLong(0, -1)
        if (seconds <= 0) return null
        val micros = timestamp.optLong(1, 0)
        return seconds * 1_000L + (micros / 1_000L)
    }
}
