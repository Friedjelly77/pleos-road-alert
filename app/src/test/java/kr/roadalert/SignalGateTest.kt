package kr.roadalert

import org.junit.Assert.*
import org.junit.Test

class SignalGateTest {
    private val now = 1_800_000_000_000
    private fun air(at: Long, active: Boolean, seat: String = "0") =
        Observation("deployment", "pleos", at, active, seat = seat)

    @Test fun deploymentNeedsTransitionAndCoalescesSeats() {
        val gate = SignalGate()
        assertEquals(Decision.NONE, gate.accept(air(now, true)))
        assertEquals(Decision.NONE, gate.accept(air(now + 10, false)))
        assertEquals(Decision.NONE, gate.accept(air(now + 20, false, "1")))
        assertEquals(Decision.DEPLOYED, gate.accept(air(now + 30, true)))
        assertEquals(Decision.NONE, gate.accept(air(now + 40, true, "1")))
        assertEquals(Decision.NONE, gate.accept(air(now + 50, true)))
    }

    @Test fun hazardAndDecelerationOnlyPromptAndShareCooldown() {
        val gate = SignalGate()
        assertEquals(Decision.NONE, gate.accept(Observation("speed", "aaos", now, speedMps = 16.0)))
        assertEquals(Decision.ASK, gate.accept(Observation("speed", "aaos", now + 1000, speedMps = 5.0)))
        assertEquals(Decision.NONE, gate.accept(Observation("hazards", "simulator", now + 1500)))
        assertEquals(Decision.ASK, gate.accept(Observation("hazards", "simulator", now + 92000)))
        assertEquals(Decision.NONE, gate.accept(Observation("brake", "pleos", now + 190000)))
    }

    @Test fun stationarySlowOrOutOfOrderSamplesDoNotPrompt() {
        val gate = SignalGate()
        for ((t,v) in listOf(0L to 15.0, 150L to 14.8, 140L to 1.5, 8000L to 0.0, 8400L to 0.0))
            assertEquals(Decision.NONE, gate.accept(Observation("speed", "aaos", now + t, speedMps = v)))
        assertNull(UnmappedPosition.current(now))
        assertEquals(400, DemoPosition(Station.B).current(now).offset)
        assertTrue(now - DemoPosition(Station.STALE).current(now).observedAt > 20000)
    }
}
