package dev.geminiquota.data

import android.content.Context
import dev.geminiquota.work.RefreshScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single source of truth for usage state. Refreshes are serialized so the
 * widget, the app and the background worker never hit Gemini at the same time.
 */
class UsageRepository(
    private val appContext: Context,
    private val store: SessionStore,
    private val client: GeminiUsageClient = GeminiUsageClient(),
    private val claudeClient: ClaudeUsageClient = ClaudeUsageClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    /** Last claude.ai page read, or null when no Claude session is stored. */
    private val _claudePage = MutableStateFlow(store.page(ProviderId.CLAUDE))
    val claudePage: StateFlow<QuotaPage?> = _claudePage.asStateFlow()

    private val refreshMutex = Mutex()

    fun currentState(): UsageUiState = _state.value

    suspend fun refresh(): RefreshOutcome {
        if (!store.hasSession()) {
            _state.update { it.copy(isSignedIn = false, isRefreshing = false, authRequired = false) }
            return RefreshOutcome.NOT_SIGNED_IN
        }
        if (refreshMutex.isLocked) return RefreshOutcome.ALREADY_RUNNING

        return refreshMutex.withLock {
            _state.update { it.copy(isRefreshing = true) }
            try {
                refreshLocked()
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun refreshLocked(): RefreshOutcome {
        val cookie = store.cookie()
        if (cookie.isNullOrBlank()) {
            _state.update { it.copy(isSignedIn = false, isRefreshing = false) }
            return RefreshOutcome.NOT_SIGNED_IN
        }

        val snapshot = try {
            fetchWithRetry(cookie)
        } catch (e: Exception) {
            return handleRefreshFailure(e)
        }

        withContext(Dispatchers.IO) {
            store.saveSnapshot(snapshot)
            store.clearError()
        }
        _state.value = UsageUiState(
            isSignedIn = true,
            snapshot = snapshot,
            isRefreshing = false,
        )
        RefreshScheduler.scheduleAtNextReset(appContext, snapshot, clock())
        return RefreshOutcome.SUCCESS
    }

    suspend fun refreshIfStale(maxAgeMs: Long = 5 * 60 * 1_000L) {
        if (!_state.value.isSignedIn) return
        if (_state.value.isStale(clock(), maxAgeMs)) refresh()
    }

    /** Validates and stores a cookie header obtained from WebView or a paste. */
    suspend fun connect(rawCookie: String): ConnectResult {
        val normalized = CookieUtils.normalize(rawCookie)
        if (!CookieUtils.looksLikeGeminiSession(normalized)) {
            return ConnectResult.Failure(
                ErrorKind.AUTH,
                "The pasted cookies did not contain a Google session.",
            )
        }

        val snapshot = try {
            withContext(Dispatchers.IO) { client.fetchUsage(normalized) }
        } catch (e: Exception) {
            val kind = e.toErrorKind()
            val message = e.message?.takeIf { it.isNotBlank() } ?: "Connection failed"
            val detail = (e as? UsageException.ProtocolChanged)?.detail
            withContext(Dispatchers.IO) { store.saveError(kind, message, detail) }
            _state.update {
                it.copy(
                    isRefreshing = false,
                    authRequired = kind == ErrorKind.AUTH,
                    errorKind = kind,
                    errorMessage = message,
                    errorDetail = detail,
                )
            }
            return ConnectResult.Failure(kind, message)
        }

        withContext(Dispatchers.IO) {
            store.saveCookie(normalized)
            store.saveSnapshot(snapshot)
            store.clearError()
        }
        _state.value = UsageUiState(isSignedIn = true, snapshot = snapshot)
        RefreshScheduler.ensurePeriodic(appContext)
        RefreshScheduler.scheduleAtNextReset(appContext, snapshot, clock())
        return ConnectResult.Success
    }

    suspend fun signOut() {
        withContext(Dispatchers.IO) { store.clearAll() }
        RefreshScheduler.cancelAll(appContext)
        _state.value = UsageUiState(isSignedIn = false, snapshot = null)
    }

    /**
     * Links a claude.ai session: one discovery call, one usage call, then the
     * page is stored under the `claude` provider. The Gemini session and its
     * widget state are never touched, so the two accounts coexist.
     */
    suspend fun connectClaude(rawCookies: String): ClaudeConnectResult {
        val cookie = ClaudeCookies.cookieHeader(rawCookies)
            ?: return ClaudeConnectResult.Failure(
                ErrorKind.AUTH,
                "The pasted text did not contain a claude.ai sessionKey.",
            )

        val page = try {
            withContext(Dispatchers.IO) { claudeClient.fetchUsage(cookie) }
        } catch (e: Exception) {
            val kind = e.toErrorKind()
            val message = e.message?.takeIf { it.isNotBlank() } ?: "Connection failed"
            val detail = (e as? UsageException.ProtocolChanged)?.detail
            withContext(Dispatchers.IO) {
                store.saveError(kind, message, detail, ProviderId.CLAUDE)
            }
            return ClaudeConnectResult.Failure(kind, message)
        }

        withContext(Dispatchers.IO) {
            store.saveCookie(cookie, ProviderId.CLAUDE)
            store.savePage(page)
            store.clearError(ProviderId.CLAUDE)
        }
        _claudePage.value = page
        return ClaudeConnectResult.Success(page)
    }

    /**
     * Retries only the failures that are worth retrying.
     *
     * A phone that hops between Wi-Fi and cellular, or a Google edge node that
     * answers 503, should not cost the user an "offline" badge: those clear up in
     * under a second. Auth, rate limiting and protocol changes are decided by the
     * server and would just burn quota, so they surface immediately.
     */
    private suspend fun fetchWithRetry(cookie: String): UsageSnapshot {
        var attempt = 1
        while (true) {
            try {
                return withContext(Dispatchers.IO) { client.fetchUsage(cookie) }
            } catch (e: Exception) {
                val kind = e.toErrorKind()
                val retryable = kind == ErrorKind.NETWORK || kind == ErrorKind.SERVER
                if (!retryable || attempt >= MAX_FETCH_ATTEMPTS) throw e
                delay(RETRY_BASE_DELAY_MS * attempt)
                attempt++
            }
        }
    }

    private suspend fun handleRefreshFailure(error: Exception): RefreshOutcome {
        val kind = error.toErrorKind()
        val message = error.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
        val detail = (error as? UsageException.ProtocolChanged)?.detail
        withContext(Dispatchers.IO) { store.saveError(kind, message, detail) }
        _state.update {
            it.copy(
                isRefreshing = false,
                authRequired = kind == ErrorKind.AUTH,
                errorKind = kind,
                errorMessage = message,
                errorDetail = detail,
            )
        }
        return if (kind == ErrorKind.AUTH) RefreshOutcome.AUTH_REQUIRED else RefreshOutcome.FAILED
    }

    private fun initialState(): UsageUiState {
        val signedIn = store.hasSession()
        val stored = store.lastError()
        val kind = stored?.kind
        return UsageUiState(
            isSignedIn = signedIn,
            snapshot = store.snapshot(),
            authRequired = signedIn && kind == ErrorKind.AUTH,
            errorKind = kind,
            errorMessage = stored?.message,
            errorDetail = stored?.detail,
        )
    }

    private companion object {
        const val MAX_FETCH_ATTEMPTS = 3
        const val RETRY_BASE_DELAY_MS = 600L
    }
}
