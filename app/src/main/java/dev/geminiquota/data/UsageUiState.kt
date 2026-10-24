package dev.geminiquota.data

data class UsageUiState(
    val isSignedIn: Boolean,
    val snapshot: UsageSnapshot?,
    val isRefreshing: Boolean = false,
    val authRequired: Boolean = false,
    val errorKind: ErrorKind? = null,
    val errorMessage: String? = null,
    val errorDetail: String? = null,
) {
    val hasData: Boolean
        get() = snapshot?.hasAnyWindow == true

    fun isStale(nowEpochMs: Long, maxAgeMs: Long = DEFAULT_STALE_MS): Boolean {
        val snapshot = snapshot ?: return true
        return nowEpochMs - snapshot.fetchedAtEpochMs > maxAgeMs
    }

    /**
     * The error worth mentioning next to the numbers, if any.
     *
     * A failed refresh is not the same event as stale data: if the numbers were
     * fetched two minutes ago they are still true, and stamping "offline" on them
     * just because the last poll hiccuped is noise. Likewise an expired session is
     * already reported by its own state, so it is not an "offline" situation.
     *
     * @return the kind to surface, or null when the numbers speak for themselves.
     */
    fun offlineNote(
        nowEpochMs: Long,
        offlineAfterMs: Long = OFFLINE_NOTE_AFTER_MS,
    ): ErrorKind? {
        val kind = errorKind ?: return null
        if (kind == ErrorKind.AUTH) return null
        val fetchedAt = snapshot?.fetchedAtEpochMs ?: return null
        return if (nowEpochMs - fetchedAt >= offlineAfterMs) kind else null
    }

    companion object {
        const val DEFAULT_STALE_MS = 30 * 60 * 1_000L

        /** How old the numbers must be before a failed refresh is worth flagging. */
        const val OFFLINE_NOTE_AFTER_MS = 20 * 60 * 1_000L
    }
}

enum class RefreshOutcome {
    SUCCESS,
    NOT_SIGNED_IN,
    AUTH_REQUIRED,
    FAILED,
    ALREADY_RUNNING,
}

sealed class ConnectResult {
    data object Success : ConnectResult()
    data class Failure(val kind: ErrorKind, val message: String) : ConnectResult()
}

/** Outcome of linking a claude.ai session; carries the page it managed to read. */
sealed class ClaudeConnectResult {
    data class Success(val page: QuotaPage) : ClaudeConnectResult()
    data class Failure(val kind: ErrorKind, val message: String) : ClaudeConnectResult()
}
