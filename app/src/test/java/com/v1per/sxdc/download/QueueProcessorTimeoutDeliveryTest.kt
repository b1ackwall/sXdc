package com.v1per.sxdc.download

import com.v1per.sxdc.R
import com.v1per.sxdc.data.DownloadStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #75: a timeout or Emergency Stop during the folder move. */
class QueueProcessorTimeoutDeliveryTest {
    private val h = Harness()
    private val url = "https://youtu.be/a"

    /** What onTimeout does while the move runs. */
    private fun timeOut(id: Long) {
        h.processor.markAborted(id)
        runBlocking { h.processor.failJobById(id, h.processor.timeoutMessage) }
        h.processor.requestAbort(id)
    }

    private fun emergencyStop() = runBlocking {
        EmergencyStop.run(
            cancelRows = { h.store.cancelAll() },
            abortInFlight = { h.activeRun.abortCurrent() },
            clearHalt = {},
        )
    }

    @Test
    fun timeoutDuringTheMove_ofADeliveredFile_endsCompleted_withTheSuccessNotification() = runTest {
        val id = h.store.seed(url)
        h.mover.afterMove = { timeOut(id) }

        h.processor.process(null)

        val row = h.store.job(id)
        assertEquals(DownloadStatus.COMPLETED, row.status)
        assertEquals(null, row.failureReason)
        assertEquals(listOf(id), h.notifier.successes.map { it.jobId })
        // The timeout notification came first; the success replaces it under the same id.
        assertEquals(listOf(id), h.notifier.failures.map { it.jobId })
        assertTrue(h.halts.recorded.isEmpty())
    }

    @Test
    fun timeoutDuringTheMove_whoseCopyWasAborted_staysFailed_withTheTimeoutMessage() = runTest {
        val id = h.store.seed(url)
        h.mover.honoursAbort = true
        h.mover.afterMove = { timeOut(id) }

        h.processor.process(null)

        assertEquals(DownloadStatus.FAILED, h.store.job(id).status)
        assertEquals(h.processor.timeoutMessage, h.store.job(id).failureReason)
        assertTrue(h.notifier.successes.isEmpty())
        assertEquals(1, h.notifier.failures.size)
    }

    @Test
    fun emergencyStopDuringTheMove_ofADeliveredFile_staysCancelled_withNoSuccess() = runTest {
        val id = h.store.seed(url)
        h.mover.afterMove = {
            emergencyStop()
        }

        h.processor.process(null)

        assertEquals(DownloadStatus.CANCELLED, h.store.job(id).status)
        assertTrue(h.log.only("markDeliveredAfterTimeout").isEmpty())
        assertTrue(h.notifier.successes.isEmpty())
        assertTrue(h.notifier.failures.isEmpty())
    }

    @Test
    fun emergencyStopDuringTheMove_whoseCopyWasAborted_staysCancelled_andNothingIsAnnounced() = runTest {
        val id = h.store.seed(url)
        h.mover.honoursAbort = true
        h.mover.afterMove = {
            emergencyStop()
        }

        h.processor.process(null)

        assertEquals(DownloadStatus.CANCELLED, h.store.job(id).status)
        assertTrue(h.notifier.successes.isEmpty())
        assertTrue(h.notifier.failures.isEmpty())
        assertTrue(h.halts.recorded.isEmpty())
    }

    @Test
    fun anotherFailedRow_isNeverCorrected_evenWithTheTimeoutFlagSet() = runTest {
        val id = h.store.seed(url)
        h.mover.afterMove = {
            h.processor.markAborted(id)
            runBlocking { h.processor.failJobById(id, "some other failure") }
        }

        h.processor.process(null)

        assertEquals(DownloadStatus.FAILED, h.store.job(id).status)
        assertEquals("some other failure", h.store.job(id).failureReason)
        assertTrue(h.notifier.successes.isEmpty())
    }

    @Test
    fun theMoveIsAskedToAbortThroughTheJobsOwnSignal() = runTest {
        val id = h.store.seed(url)
        var sawAbort = false
        h.mover.honoursAbort = true
        h.mover.afterMove = {
            h.processor.requestAbort(id)
            sawAbort = true
        }

        h.processor.process(null)

        assertTrue(sawAbort)
        assertEquals(
            FakeStringProvider.render(R.string.error_aborted),
            h.store.job(id).failureReason,
        )
    }
}
