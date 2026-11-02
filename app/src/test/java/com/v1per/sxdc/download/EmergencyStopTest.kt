package com.v1per.sxdc.download

import com.v1per.sxdc.data.DownloadStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emergency Stop (#60): cancel every pending row, then abort the call in flight. */
class EmergencyStopTest {

    private val h = Harness()

    /** EmergencyStop.run wired to the harness, as Settings wires it to the DAO and QueueRunLock. */
    private suspend fun stop(order: MutableList<String> = mutableListOf()) = EmergencyStop.run(
        cancelRows = {
            order += "cancel"
            h.store.jobs()
                .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
                .forEach { h.store.setStatus(it.id, DownloadStatus.CANCELLED) }
        },
        abortInFlight = {
            order += "abort"
            h.activeRun.abortCurrent()
        },
        clearHalt = {
            order += "clearHalt"
            h.halts.clear()
        },
    )

    @Test
    fun cancelsTheRowsBeforeAborting_andClearsTheHaltLast() = runTest {
        val order = mutableListOf<String>()
        stop(order)
        assertEquals(listOf("cancel", "abort", "clearHalt"), order)
    }

    @Test
    fun midDownload_runningAndQueuedRowsEndCancelled_noHaltNoNotification_andTheRunStops() = runTest {
        val running = h.store.seed("https://youtu.be/a")
        val queued = h.store.seed("https://youtu.be/b")
        h.bridge.onDownload = { runBlocking { stop() } }

        h.processor.process(null)

        assertEquals(DownloadStatus.CANCELLED, h.store.job(running).status)
        assertEquals(DownloadStatus.CANCELLED, h.store.job(queued).status)
        assertTrue("the in-flight call was not asked to stop", h.bridge.aborts.single().requested)
        assertTrue(h.halts.recorded.isEmpty())
        assertTrue(h.notifier.failures.isEmpty())
        assertTrue(h.notifier.successes.isEmpty())
        assertTrue(h.mover.moves.isEmpty())
        assertEquals(1, h.cleaner.sweeps)
        assertTrue(h.processor.shouldStop())
    }

    @Test
    fun withNothingInFlight_onlyTheRowsAndTheHaltChange() = runTest {
        val queued = h.store.seed("https://youtu.be/b")

        stop()

        assertEquals(DownloadStatus.CANCELLED, h.store.job(queued).status)
        assertFalse(h.activeRun.abortCurrent())
    }

    // ---- AbortSignal and ActiveRun ----

    @Test
    fun abortSignal_requestIsIdempotent_andRunsTheAttachedActionOnce() {
        val signal = AbortSignal()
        var runs = 0
        signal.attach { runs++ }
        assertFalse(signal.requested)

        signal.request()
        signal.request()

        assertTrue(signal.requested)
        assertEquals(1, runs)
    }

    @Test
    fun abortSignal_aRequestBeforeTheAttach_isNotLost() {
        val signal = AbortSignal()
        signal.request()
        var runs = 0

        signal.attach { runs++ }

        assertEquals(1, runs)
    }

    @Test
    fun activeRun_withdrawLeavesALaterSignalInPlace() {
        val run = ActiveRun()
        val first = AbortSignal()
        val second = AbortSignal()
        run.publish(first)
        run.publish(second)

        run.withdraw(first)

        assertTrue(run.abortCurrent())
        assertTrue(second.requested)
        assertFalse(first.requested)
        run.withdraw(second)
        assertFalse(run.abortCurrent())
    }
}
