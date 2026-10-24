package dev.geminiquota.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON encoding for a cached [QuotaPage], used by the per-provider storage.
 *
 * A page can legitimately have zero windows — a free plan that reports no quota
 * windows — so unlike [SnapshotCodec] an empty list is a valid payload and only
 * a missing `fetchedAt` means "nothing usable".
 */
object QuotaPageCodec {

    private const val VERSION = 1

    fun encode(page: QuotaPage): String = JSONObject()
        .put("version", VERSION)
        .put("provider", page.provider.key)
        .put("fetchedAt", page.fetchedAtEpochMs)
        .put("source", page.source)
        .put("windows", JSONArray().apply {
            page.windows.forEach { window ->
                put(
                    JSONObject()
                        .put("id", window.id.name)
                        .put("percent", window.usedPercent)
                        .apply { window.resetAtEpochMs?.let { put("reset", it) } },
                )
            }
        })
        .apply { page.tier?.let { put("tier", it) } }
        .toString()

    fun decode(raw: String?): QuotaPage? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            if (json.optInt("version", 0) != VERSION) return null

            val provider = ProviderId.entries
                .firstOrNull { it.key == json.optString("provider") }
                ?: return null
            val fetchedAt = json.optLong("fetchedAt", 0L)
            if (fetchedAt <= 0L) return null

            val windows = json.optJSONArray("windows").orEmpty().mapNotNull { element ->
                val entry = element as? JSONObject ?: return@mapNotNull null
                val id = runCatching { QuotaWindowId.valueOf(entry.optString("id")) }
                    .getOrNull() ?: return@mapNotNull null
                val percent = entry.optInt("percent", -1)
                if (percent < 0) return@mapNotNull null
                QuotaWindow(
                    id = id,
                    usedPercent = percent.coerceIn(0, 100),
                    resetAtEpochMs = entry.optLong("reset", 0L).takeIf { it > 0L },
                )
            }

            QuotaPage(
                provider = provider,
                tier = json.optString("tier").takeIf { it.isNotBlank() && it != "null" },
                windows = windows,
                fetchedAtEpochMs = fetchedAt,
                source = json.optString("source", ""),
            )
        } catch (_: JSONException) {
            null
        }
    }

    private fun JSONArray?.orEmpty(): List<Any?> =
        if (this == null) emptyList() else (0 until length()).map { opt(it) }
}
