package dev.geminiquota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageUiStateTest {

    private val fetchedAt = 1_700_000_000_000L

    private fun snapshot() = UsageSnapshot(
        fiveHour = UsageWindow(0.2, null),
        weekly = null,
        tier = null,
        fetchedAtEpochMs = fetchedAt,
        source = UsageSource.GRAPHQL,
    )

    private fun state(errorKind: ErrorKind?, snapshot: UsageSnapshot? = snapshot()) = UsageUiState(
        isSignedIn = true,
        snapshot = snapshot,
        errorKind = errorKind,
    )

    @Test
    fun `a healthy state has no note`() {
        assertNull(state(null).offlineNote(fetchedAt))
    }

    @Test
    fun `a hiccup right after a fetch is not worth a note`() {
        assertNull(state(ErrorKind.NETWORK).offlineNote(fetchedAt + 2 * 60_000))
    }

    @Test
    fun `stale numbers plus a failed refresh are worth a note`() {
        assertEquals(
            ErrorKind.NETWORK,
            state(ErrorKind.NETWORK).offlineNote(fetchedAt + 21 * 60_000),
        )
    }

    @Test
    fun `the threshold is inclusive and configurable`() {
        val state = state(ErrorKind.SERVER)
        assertNull(state.offlineNote(fetchedAt + 20 * 60_000 - 1))
        assertEquals(ErrorKind.SERVER, state.offlineNote(fetchedAt + 20 * 60_000))
        assertEquals(ErrorKind.SERVER, state.offlineNote(fetchedAt, offlineAfterMs = 0))
    }

    @Test
    fun `an expired session is reported by its own state, not as offline`() {
        assertNull(state(ErrorKind.AUTH).offlineNote(fetchedAt + 60 * 60_000))
    }

    @Test
    fun `rate limiting keeps its own kind`() {
        assertEquals(
            ErrorKind.RATE_LIMITED,
            state(ErrorKind.RATE_LIMITED).offlineNote(fetchedAt + 60 * 60_000),
        )
    }

    @Test
    fun `without numbers there is nothing to caption`() {
        assertNull(state(ErrorKind.NETWORK, snapshot = null).offlineNote(fetchedAt + 60 * 60_000))
    }
}
