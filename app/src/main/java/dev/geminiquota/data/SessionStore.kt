package dev.geminiquota.data

import android.content.Context

data class StoredError(
    val kind: ErrorKind,
    val message: String,
    val detail: String?,
    val atEpochMs: Long,
)

/**
 * App-private persistence for the (encrypted) session cookie, the last usage
 * snapshot and the last error. Nothing here is ever sent anywhere.
 */
class SessionStore(context: Context, private val crypto: CryptoBox = CryptoBox()) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveCookie(rawHeader: String) {
        val normalized = CookieUtils.normalize(rawHeader)
        if (normalized.isBlank()) return
        val encrypted = crypto.encrypt(normalized) ?: return
        // commit() so a worker or widget read right after sign-in sees the session.
        prefs.edit().putString(KEY_COOKIE, encrypted).commit()
    }

    fun cookie(): String? {
        val payload = prefs.getString(KEY_COOKIE, null) ?: return null
        return crypto.decrypt(payload)
    }

    fun hasSession(): Boolean = !cookie().isNullOrBlank()

    fun saveSnapshot(snapshot: UsageSnapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, SnapshotCodec.encode(snapshot)).apply()
    }

    fun snapshot(): UsageSnapshot? = SnapshotCodec.decode(prefs.getString(KEY_SNAPSHOT, null))

    fun saveError(kind: ErrorKind, message: String, detail: String?) {
        prefs.edit()
            .putString(KEY_ERROR_KIND, kind.name)
            .putString(KEY_ERROR_MESSAGE, message)
            .putString(KEY_ERROR_DETAIL, detail)
            .putLong(KEY_ERROR_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastError(): StoredError? {
        val kind = prefs.getString(KEY_ERROR_KIND, null)?.let {
            runCatching { ErrorKind.valueOf(it) }.getOrNull()
        } ?: return null
        return StoredError(
            kind = kind,
            message = prefs.getString(KEY_ERROR_MESSAGE, "").orEmpty(),
            detail = prefs.getString(KEY_ERROR_DETAIL, null),
            atEpochMs = prefs.getLong(KEY_ERROR_AT, 0L),
        )
    }

    fun clearError() {
        prefs.edit()
            .remove(KEY_ERROR_KIND)
            .remove(KEY_ERROR_MESSAGE)
            .remove(KEY_ERROR_DETAIL)
            .remove(KEY_ERROR_AT)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val PREFS = "gemini_quota"
        const val KEY_COOKIE = "session_cookie"
        const val KEY_SNAPSHOT = "usage_snapshot"
        const val KEY_ERROR_KIND = "error_kind"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_ERROR_DETAIL = "error_detail"
        const val KEY_ERROR_AT = "error_at"
    }
}
