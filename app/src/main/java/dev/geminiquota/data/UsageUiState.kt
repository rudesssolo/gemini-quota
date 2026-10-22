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

    companion object {
        const val DEFAULT_STALE_MS = 30 * 60 * 1_000L
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
