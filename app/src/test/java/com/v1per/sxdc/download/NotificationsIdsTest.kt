package com.v1per.sxdc.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationsIdsTest {

    @Test
    fun terminalId_ofJobZero_is2000() {
        assertEquals(2000, Notifications.terminalId(0L))
    }

    @Test
    fun terminalId_ofJobOne_is2001() {
        assertEquals(2001, Notifications.terminalId(1L))
    }

    @Test
    fun terminalId_ofLargeValidJobId_isBasePlusId() {
        assertEquals(1_002_000, Notifications.terminalId(1_000_000L))
        // Largest job id whose terminal id still fits in an Int without wrapping.
        assertEquals(Int.MAX_VALUE, Notifications.terminalId(Int.MAX_VALUE.toLong() - 2000))
    }

    @Test
    fun terminalId_differsForDifferentJobIds() {
        assertNotEquals(Notifications.terminalId(7L), Notifications.terminalId(8L))
    }

    @Test
    fun constants_haveExpectedValues() {
        assertEquals(1001, Notifications.ID_PROGRESS)
        assertEquals(1002, Notifications.ID_TERMINAL_SUMMARY)
        assertEquals("sxdc.downloads", Notifications.CHANNEL_PROGRESS)
        assertEquals("sxdc.downloads.terminal", Notifications.CHANNEL_TERMINAL)
    }

    @Test
    fun terminalIds_forJobs0To1000_neverCollideWithFixedIds() {
        for (jobId in 0L..1000L) {
            val id = Notifications.terminalId(jobId)
            assertFalse("job $jobId collides with ID_PROGRESS", id == Notifications.ID_PROGRESS)
            assertFalse("job $jobId collides with ID_TERMINAL_SUMMARY", id == Notifications.ID_TERMINAL_SUMMARY)
        }
    }

    /**
     * Pins CURRENT behaviour, not desired behaviour. terminalId() does jobId.toInt(), which
     * silently truncates ids above Int.MAX_VALUE, so such ids wrap negative and can alias a
     * small job id's notification id. Issue candidate.
     */
    @Test
    fun terminalId_aboveIntMax_pinsCurrentTruncatingBehavior() {
        assertEquals(Int.MIN_VALUE + 2000, Notifications.terminalId(Int.MAX_VALUE.toLong() + 1))
        // 2^32 truncates to 0, so it aliases job 0.
        assertEquals(Notifications.terminalId(0L), Notifications.terminalId(1L shl 32))
    }
}
