package com.v1per.sxdc.download

import com.v1per.sxdc.download.QueueHaltPolicy.RATE_LIMIT_HOLD_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueHaltPolicyTest {

    private val haltedAt = 1_000_000L
    private var preconditionCalls = 0

    private fun decide(
        halt: QueueHalt?,
        reason: StartReason = StartReason.RESUME_SCREEN,
        now: Long = haltedAt + 1,
        pending: Int = 3,
        preconditionsOk: Boolean = false,
    ): StartDecision = QueueHaltPolicy.decide(halt, now, pending, reason) {
        preconditionCalls++
        preconditionsOk
    }

    private fun halt(kind: HaltKind) = QueueHalt(kind, haltedAt)

    // --- no halt ---

    @Test
    fun noHalt_resumeScreen_withPending_starts() {
        assertEquals(StartDecision.START, decide(null))
    }

    @Test
    fun noHalt_resumeScreen_nothingPending_doesNotStart() {
        assertEquals(StartDecision.DO_NOT_START, decide(null, pending = 0))
    }

    @Test
    fun noHalt_shareAndUserResume_start() {
        assertEquals(StartDecision.START, decide(null, StartReason.NEW_SHARE))
        assertEquals(StartDecision.START, decide(null, StartReason.USER_RESUME))
    }

    // --- pending == 0 clears lazily ---

    @Test
    fun anyHalt_nothingPending_onResumeScreen_clearsOnly() {
        HaltKind.entries.forEach { kind ->
            assertEquals(kind.name, StartDecision.CLEAR_ONLY, decide(halt(kind), pending = 0, preconditionsOk = true))
        }
    }

    // --- STORAGE ---

    @Test
    fun storage_neverAutoClears_evenLongAfter() {
        assertEquals(StartDecision.DO_NOT_START, decide(halt(HaltKind.STORAGE)))
        val muchLater = haltedAt + 10 * RATE_LIMIT_HOLD_MS
        assertEquals(StartDecision.DO_NOT_START, decide(halt(HaltKind.STORAGE), now = muchLater))
        assertEquals(StartDecision.DO_NOT_START, decide(halt(HaltKind.STORAGE), preconditionsOk = true))
        assertEquals(0, preconditionCalls)
    }

    // --- SETUP ---

    @Test
    fun setup_preconditionsPass_clearsAndStarts() {
        assertEquals(StartDecision.CLEAR_AND_START, decide(halt(HaltKind.SETUP), preconditionsOk = true))
        assertEquals(1, preconditionCalls)
    }

    @Test
    fun setup_preconditionsFail_doesNotStart() {
        assertEquals(StartDecision.DO_NOT_START, decide(halt(HaltKind.SETUP), preconditionsOk = false))
        assertEquals(1, preconditionCalls)
    }

    @Test
    fun preconditions_onlyEvaluatedForSetupOnResumeScreen() {
        decide(null)
        decide(halt(HaltKind.STORAGE))
        decide(halt(HaltKind.RATE_LIMITED))
        decide(halt(HaltKind.SETUP), pending = 0)
        decide(halt(HaltKind.SETUP), StartReason.NEW_SHARE)
        decide(halt(HaltKind.SETUP), StartReason.USER_RESUME)
        assertEquals(0, preconditionCalls)
    }

    // --- RATE_LIMITED ---

    @Test
    fun rateLimited_justBeforeExpiry_doesNotStart() {
        assertEquals(
            StartDecision.DO_NOT_START,
            decide(halt(HaltKind.RATE_LIMITED), now = haltedAt + RATE_LIMIT_HOLD_MS - 1),
        )
    }

    @Test
    fun rateLimited_atExactlyThirtyMinutes_clearsAndStarts() {
        assertEquals(30L * 60 * 1000, RATE_LIMIT_HOLD_MS)
        assertEquals(
            StartDecision.CLEAR_AND_START,
            decide(halt(HaltKind.RATE_LIMITED), now = haltedAt + RATE_LIMIT_HOLD_MS),
        )
    }

    @Test
    fun rateLimited_clockMovedBack_countsAsExpired() {
        assertEquals(StartDecision.CLEAR_AND_START, decide(halt(HaltKind.RATE_LIMITED), now = haltedAt - 1))
    }

    @Test
    fun rateLimited_ignoresPreconditions() {
        assertEquals(StartDecision.DO_NOT_START, decide(halt(HaltKind.RATE_LIMITED), preconditionsOk = true))
    }

    // --- NEW_SHARE and USER_RESUME always clear ---

    @Test
    fun shareAndUserResume_clearEveryKind_evenWithNothingPending() {
        listOf(StartReason.NEW_SHARE, StartReason.USER_RESUME).forEach { reason ->
            HaltKind.entries.forEach { kind ->
                listOf(0, 3).forEach { pending ->
                    assertEquals(
                        "$reason $kind pending=$pending",
                        StartDecision.CLEAR_AND_START,
                        decide(halt(kind), reason, pending = pending),
                    )
                }
            }
        }
    }

    // --- decision flags and ids ---

    @Test
    fun decisionFlags() {
        assertTrue(StartDecision.START.start)
        assertFalse(StartDecision.START.clearHalt)
        assertFalse(StartDecision.DO_NOT_START.start)
        assertFalse(StartDecision.DO_NOT_START.clearHalt)
        assertTrue(StartDecision.CLEAR_AND_START.start)
        assertTrue(StartDecision.CLEAR_AND_START.clearHalt)
        assertFalse(StartDecision.CLEAR_ONLY.start)
        assertTrue(StartDecision.CLEAR_ONLY.clearHalt)
    }

    @Test
    fun haltKindIds_roundTrip_andUnknownIsNull() {
        assertEquals(listOf("storage", "rate_limited", "setup"), HaltKind.entries.map { it.id })
        HaltKind.entries.forEach { assertEquals(it, HaltKind.fromId(it.id)) }
        assertNull(HaltKind.fromId("network"))
        assertNull(HaltKind.fromId(null))
    }
}
