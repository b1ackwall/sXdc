package com.v1per.sxdc.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        assertEquals(2000 + (1 shl 30) - 1, Notifications.terminalId((1L shl 30) - 1))
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

    @Test
    fun terminalId_ofSmallIds_isBasePlusId() {
        for (jobId in longArrayOf(0, 1, 2, 1999, 2000, 100_000, (1L shl 30) - 1)) {
            assertEquals(2000 + jobId.toInt(), Notifications.terminalId(jobId))
        }
    }

    // Intent kept from the old characterization test: ids above Int.MAX_VALUE used to truncate
    // (2^32 aliased job 0, others went negative). Now they are masked and stay in range.
    @Test
    fun terminalId_ofHugeAndNegativeIds_isNeverBelowBaseOrNegative() {
        val ids =
            longArrayOf(
                Int.MAX_VALUE.toLong(),
                Int.MAX_VALUE.toLong() + 1,
                1L shl 32,
                Long.MAX_VALUE,
                -1L,
                Long.MIN_VALUE,
            )
        for (jobId in ids) {
            val id = Notifications.terminalId(jobId)
            assertTrue("job $jobId gave $id", id >= 2000)
        }
    }

    @Test
    fun terminalId_neverEqualsAFixedId() {
        val fixed = listOf(Notifications.ID_PROGRESS, Notifications.ID_TERMINAL_SUMMARY)
        val ids =
            longArrayOf(
                0,
                1,
                Int.MAX_VALUE.toLong(),
                1L shl 32,
                Long.MAX_VALUE,
                -1L,
                Long.MIN_VALUE,
            )
        for (jobId in ids) {
            assertFalse("job $jobId collides with a fixed id", Notifications.terminalId(jobId) in fixed)
        }
    }

    @Test
    fun terminalId_isDistinctForConsecutiveIds() {
        val ids = (1L..10_000L).map { Notifications.terminalId(it) }.toSet()
        assertEquals(10_000, ids.size)
    }
}
