package dev.geminiquota.data

import org.json.JSONException
import org.json.JSONObject

/** JSON encoding for the cached usage snapshot (app-private storage only). */
object SnapshotCodec {

    private const val VERSION = 1

    fun encode(snapshot: UsageSnapshot): String {
        val json = JSONObject()
            .put("version", VERSION)
            .put("fetchedAt", snapshot.fetchedAtEpochMs)
            .put("source", snapshot.source.name)
        snapshot.tier?.let { json.put("tier", it) }
        snapshot.fiveHour?.let { json.put("fiveHour", encodeWindow(it)) }
        snapshot.weekly?.let { json.put("weekly", encodeWindow(it)) }
        return json.toString()
    }

    fun decode(raw: String?): UsageSnapshot? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            if (json.optInt("version", 0) != VERSION) return null
            val fetchedAt = json.optLong("fetchedAt", 0L)
            if (fetchedAt <= 0L) return null
            val fiveHour = json.optJSONObject("fiveHour")?.let(::decodeWindow)
            val weekly = json.optJSONObject("weekly")?.let(::decodeWindow)
            if (fiveHour == null && weekly == null) return null
            UsageSnapshot(
                fiveHour = fiveHour,
                weekly = weekly,
                tier = json.optString("tier").takeIf { it.isNotBlank() && it != "null" },
                fetchedAtEpochMs = fetchedAt,
                source = runCatching {
                    UsageSource.valueOf(json.optString("source", UsageSource.GRAPHQL.name))
                }.getOrDefault(UsageSource.GRAPHQL),
            )
        } catch (_: JSONException) {
            null
        }
    }

    private fun encodeWindow(window: UsageWindow): JSONObject = JSONObject()
        .put("used", window.usedFraction)
        .apply { window.resetAtEpochMs?.let { put("reset", it) } }

    private fun decodeWindow(json: JSONObject): UsageWindow? {
        val used = json.optDouble("used", Double.NaN)
        if (used.isNaN()) return null
        val reset = json.optLong("reset", 0L).takeIf { it > 0L }
        return UsageWindow(usedFraction = used.coerceIn(0.0, 1.0), resetAtEpochMs = reset)
    }
}
