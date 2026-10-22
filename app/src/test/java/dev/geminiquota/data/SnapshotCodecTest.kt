package dev.geminiquota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotCodecTest {

    @Test
    fun `round trips a snapshot`() {
        val snapshot = UsageSnapshot(
            fiveHour = UsageWindow(0.42, 1_761_260_400_000L),
            weekly = UsageWindow(0.10, null),
            tier = "TIER1",
            fetchedAtEpochMs = 1_790_000_000_000L,
            source = UsageSource.GRAPHQL,
        )

        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `round trips without a weekly window or tier`() {
        val snapshot = UsageSnapshot(
            fiveHour = UsageWindow(0.0, null),
            weekly = null,
            tier = null,
            fetchedAtEpochMs = 1_790_000_000_000L,
            source = UsageSource.BATCHEXECUTE,
        )

        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `rejects missing or malformed payloads`() {
        assertNull(SnapshotCodec.decode(null))
        assertNull(SnapshotCodec.decode(""))
        assertNull(SnapshotCodec.decode("{}"))
        assertNull(SnapshotCodec.decode("not json"))
        assertNull(
            SnapshotCodec.decode(
                """{"version":1,"fetchedAt":1,"source":"GRAPHQL"}""",
            ),
        )
    }

    @Test
    fun `treats a zero reset as unknown`() {
        val snapshot = UsageSnapshot(
            fiveHour = UsageWindow(0.5, 0L),
            weekly = null,
            tier = null,
            fetchedAtEpochMs = 1_790_000_000_000L,
            source = UsageSource.GRAPHQL,
        )
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))
        assertNull(decoded?.fiveHour?.resetAtEpochMs)
    }
}
