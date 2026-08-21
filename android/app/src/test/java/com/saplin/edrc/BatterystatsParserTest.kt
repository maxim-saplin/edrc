package com.saplin.edrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterystatsParserTest {
    private val chargedHeader = """
        Statistics since last charge:
          System starts: 0, currently on battery: false
          Estimated battery capacity: 5600 mAh
          Time on battery: 10h 21m 17s 868ms (47.9%) realtime, 5h 47m 6s 142ms (55.9%) uptime
          Time on battery screen off: 7h 9m 8s 127ms (69.1%) realtime
          Discharge: 2604 mAh
          Screen off discharge: 1106 mAh
          Screen on discharge: 1498 mAh
          Start clock time: 2026-08-20-18-05-06
          Screen on: 3h 12m 9s 741ms (30.9%) 247x, Interactive: 3h 3m 30s 791ms (29.5%)
    """.trimIndent()

    @Test
    fun duration_parses_hours_minutes_seconds() {
        val hours = BatterystatsParser.parseDurationToHours("3h 12m 9s 741ms")
        assertEquals(3.2027, hours, 0.0001)
    }

    @Test
    fun charged_header_sot_is_twelve_hours() {
        val snap = BatterystatsParser.parseChargedHeader(chargedHeader, 1_000L)
        assertEquals("2026-08-20-18-05-06", snap.startClock)
        assertEquals(5600.0, snap.capacityMah, 0.01)
        assertEquals(1498.0, snap.screenOnMah, 0.01)
        assertEquals(3.2027, snap.screenOnHours, 0.0001)
        assertFalse(snap.onBattery)
        assertTrue(snap.hasEnoughData)
        assertEquals(12.0, snap.sotHoursPer100!!, 0.05)
        assertEquals(1106.0, snap.screenOffMah, 0.01)
        assertEquals(7.1523, snap.screenOffHours, 0.0002)
        assertTrue(snap.hasEnoughIdle)
        assertEquals(36.2, snap.idleHoursPer100!!, 0.15)
    }

    @Test
    fun thin_cycle_has_no_sot() {
        val dump = """
            Statistics since last charge:
              currently on battery: true
              Estimated battery capacity: 5600 mAh
              Screen on discharge: 50 mAh
              Start clock time: 2026-08-21-12-00-00
              Screen on: 12m 0s 0ms
        """.trimIndent()
        val snap = BatterystatsParser.parseChargedHeader(dump, 2_000L)
        assertFalse(snap.hasEnoughData)
        assertNull(snap.sotHoursPer100)
        assertTrue(snap.onBattery)
    }
}

class FrameLogTest {
    private fun frame(
        clock: String,
        on: Double,
        mah: Double,
        ts: Long,
    ) = Frame(
        tsMs = ts,
        startClock = clock,
        screenOnHours = on,
        screenOnMah = mah,
        timeOnBatteryHours = on + 1,
        capacityMah = 5600.0,
        onBattery = true,
    )

    @Test
    fun clock_change_saves_last_cycle_and_isolates_new_frames() {
        val a = frame("2026-08-20-18-05-06", 3.2, 1498.0, 100)
        val b = frame("2026-08-21-12-00-00", 0.1, 40.0, 200)
        var state = FrameLogState(emptyList(), emptyList())
        state = FrameLog.append(state, a)
        state = FrameLog.append(state, b)
        assertEquals("2026-08-20-18-05-06", state.lastCycle?.startClock)
        assertEquals(1, state.past.size)
        assertEquals(1498.0, state.lastCycle?.screenOnMah ?: 0.0, 0.01)
        assertEquals("2026-08-21-12-00-00", state.currentClock)
        assertEquals(1, state.frames.count { it.startClock == b.startClock })
        assertTrue(state.currentIntervals().isEmpty())
    }

    @Test
    fun last_cycle_absent_when_only_one_clock() {
        var state = FrameLogState(emptyList(), emptyList())
        state = FrameLog.append(state, frame("clock-a", 1.0, 200.0, 1))
        assertNull(state.lastCycle)
        assertTrue(state.past.isEmpty())
    }

    @Test
    fun interval_sot_requires_100_mah() {
        val a = frame("c", 1.0, 200.0, 1_000)
        val b = frame("c", 1.4, 250.0, 2_000)
        val c = frame("c", 2.0, 450.0, 3_000)
        val intervals = FrameLog.intervals(listOf(a, b, c))
        assertEquals(2, intervals.size)
        assertFalse(intervals[0].hasEnoughData)
        assertNull(intervals[0].sotHoursPer100)
        assertEquals(50.0, intervals[0].drainMah, 0.01)
        assertTrue(intervals[1].hasEnoughData)
        assertEquals(200.0, intervals[1].drainMah, 0.01)
        assertEquals(16.8, intervals[1].sotHoursPer100!!, 0.1)
    }
}
