package dev.geminiquota.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RampTest {

    @Test
    fun `endpoints are exact`() {
        assertEquals(12, Ramp.valueAt(12, 80, 0f))
        assertEquals(80, Ramp.valueAt(12, 80, 1f))
    }

    @Test
    fun `equal endpoints never move`() {
        assertEquals(7, Ramp.valueAt(7, 7, 0.3f))
        assertEquals(0, Ramp.valueAt(0, 0, 1f))
    }

    @Test
    fun `ease out is past halfway at the midpoint`() {
        val mid = Ramp.valueAt(0, 100, 0.5f)
        assertEquals(88, mid)
        assertTrue(mid in 51..99)
    }

    @Test
    fun `clamps fractions outside the unit interval`() {
        assertEquals(0, Ramp.valueAt(0, 100, -1f))
        assertEquals(100, Ramp.valueAt(0, 100, 2f))
    }

    @Test
    fun `moves monotonically towards a higher target`() {
        var previous = -1
        for (step in 0..10) {
            val value = Ramp.valueAt(20, 90, step / 10f)
            assertTrue("$value after $previous", value >= previous)
            previous = value
        }
        assertEquals(90, previous)
    }

    @Test
    fun `moves monotonically towards a lower target`() {
        var previous = 101
        for (step in 0..10) {
            val value = Ramp.valueAt(90, 20, step / 10f)
            assertTrue("$value after $previous", value <= previous)
            previous = value
        }
        assertEquals(20, previous)
    }
}
