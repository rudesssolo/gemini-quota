package dev.geminiquota.data

import android.content.Context

data class StoredError(
    val kind: ErrorKind,
    val message: String,
    val detail: String?,
    val atEpochMs: Long,
)

/**
 * App-private persistence for the (encrypted) session cookies, the last usage
 * page of each provider and the last error. Nothing here is ever sent anywhere.
 *
 * Keys are scoped per [ProviderId] so Gemini and Claude can be signed in at the
 * same time. Keys written by earlier versions are migrated on construction, so an
 * existing Gemini session keeps working.
 */
class SessionStore(context: Context, private val crypto: CryptoBox = CryptoBox()) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacyKeys()
    }

    fun saveCookie(rawHeader: String, provider: ProviderId = ProviderId.GEMINI) {
        val normalized = when (provider) {
            ProviderId.GEMINI -> CookieUtils.normalize(rawHeader)
            ProviderId.CLAUDE -> ClaudeCookies.normalize(rawHeader).orEmpty()
        }.ifBlank { rawHeader.trim() }
        if (normalized.isBlank()) return
        val encrypted = crypto.encrypt(normalized) ?: return
        // commit() so a worker or widget read right after sign-in sees the session.
        prefs.edit().putString(cookieKey(provider), encrypted).commit()
    }

    fun cookie(provider: ProviderId = ProviderId.GEMINI): String? {
        val payload = prefs.getString(cookieKey(provider), null) ?: return null
        return crypto.decrypt(payload)
    }

    fun hasSession(provider: ProviderId = ProviderId.GEMINI): Boolean =
        !cookie(provider).isNullOrBlank()

    fun saveSnapshot(snapshot: UsageSnapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, SnapshotCodec.encode(snapshot)).apply()
        savePage(snapshot.toQuotaPage())
    }

    fun snapshot(): UsageSnapshot? = SnapshotCodec.decode(prefs.getString(KEY_SNAPSHOT, null))

    /** Provider-neutral cache: every provider writes one of these. */
    fun savePage(page: QuotaPage) {
        prefs.edit().putString(pageKey(page.provider), QuotaPageCodec.encode(page)).apply()
    }

    fun page(provider: ProviderId): QuotaPage? =
        QuotaPageCodec.decode(prefs.getString(pageKey(provider), null))

    fun clearPage(provider: ProviderId) {
        prefs.edit().remove(pageKey(provider)).apply()
    }

    fun saveError(
        kind: ErrorKind,
        message: String,
        detail: String?,
        provider: ProviderId = ProviderId.GEMINI,
    ) {
        prefs.edit()
            .putString(errorKey(provider, KEY_ERROR_KIND), kind.name)
            .putString(errorKey(provider, KEY_ERROR_MESSAGE), message)
            .putString(errorKey(provider, KEY_ERROR_DETAIL), detail)
            .putLong(errorKey(provider, KEY_ERROR_AT), System.currentTimeMillis())
            .apply()
    }

    fun lastError(provider: ProviderId = ProviderId.GEMINI): StoredError? {
        val kind = prefs.getString(errorKey(provider, KEY_ERROR_KIND), null)?.let {
            runCatching { ErrorKind.valueOf(it) }.getOrNull()
        } ?: return null
        return StoredError(
            kind = kind,
            message = prefs.getString(errorKey(provider, KEY_ERROR_MESSAGE), "").orEmpty(),
            detail = prefs.getString(errorKey(provider, KEY_ERROR_DETAIL), null),
            atEpochMs = prefs.getLong(errorKey(provider, KEY_ERROR_AT), 0L),
        )
    }

    fun clearError(provider: ProviderId = ProviderId.GEMINI) {
        prefs.edit()
            .remove(errorKey(provider, KEY_ERROR_KIND))
            .remove(errorKey(provider, KEY_ERROR_MESSAGE))
            .remove(errorKey(provider, KEY_ERROR_DETAIL))
            .remove(errorKey(provider, KEY_ERROR_AT))
            .apply()
    }

    /** Signs one provider out without touching the other. */
    fun clearProvider(provider: ProviderId) {
        prefs.edit()
            .remove(cookieKey(provider))
            .remove(pageKey(provider))
            .remove(errorKey(provider, KEY_ERROR_KIND))
            .remove(errorKey(provider, KEY_ERROR_MESSAGE))
            .remove(errorKey(provider, KEY_ERROR_DETAIL))
            .remove(errorKey(provider, KEY_ERROR_AT))
            .apply()
        if (provider == ProviderId.GEMINI) prefs.edit().remove(KEY_SNAPSHOT).apply()
    }

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    /**
     * Moves the pre-provider keys written by 1.0.0 onto the Gemini namespace.
     * Runs once: afterwards the legacy keys no longer exist.
     */
    private fun migrateLegacyKeys() {
        val gemini = ProviderId.GEMINI
        val moves = listOf(
            LEGACY_COOKIE to cookieKey(gemini),
            LEGACY_ERROR_KIND to errorKey(gemini, KEY_ERROR_KIND),
            LEGACY_ERROR_MESSAGE to errorKey(gemini, KEY_ERROR_MESSAGE),
            LEGACY_ERROR_DETAIL to errorKey(gemini, KEY_ERROR_DETAIL),
            LEGACY_ERROR_AT to errorKey(gemini, KEY_ERROR_AT),
        ).filter { (from, to) -> prefs.contains(from) && !prefs.contains(to) }
        if (moves.isEmpty()) return

        val editor = prefs.edit()
        moves.forEach { (from, to) ->
            // Types must be preserved: error_at is a Long and is read with getLong.
            when (val value = prefs.all[from]) {
                is String -> editor.putString(to, value)
                is Long -> editor.putLong(to, value)
                is Int -> editor.putInt(to, value)
                is Boolean -> editor.putBoolean(to, value)
                is Float -> editor.putFloat(to, value)
                is Set<*> -> editor.putStringSet(to, value.filterIsInstance<String>().toSet())
                else -> Unit
            }
        }
        moves.forEach { (from, _) -> editor.remove(from) }
        editor.commit()
    }

    private fun cookieKey(provider: ProviderId) = "cookie_${provider.key}"

    private fun pageKey(provider: ProviderId) = "page_${provider.key}"

    private fun errorKey(provider: ProviderId, suffix: String) = "${suffix}_${provider.key}"

    private companion object {
        const val PREFS = "gemini_quota"
        const val KEY_SNAPSHOT = "usage_snapshot"
        const val KEY_ERROR_KIND = "error_kind"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_ERROR_DETAIL = "error_detail"
        const val KEY_ERROR_AT = "error_at"

        const val LEGACY_COOKIE = "session_cookie"
        const val LEGACY_ERROR_KIND = "error_kind"
        const val LEGACY_ERROR_MESSAGE = "error_message"
        const val LEGACY_ERROR_DETAIL = "error_detail"
        const val LEGACY_ERROR_AT = "error_at"
    }
}
