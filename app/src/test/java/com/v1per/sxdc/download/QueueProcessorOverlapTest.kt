package com.v1per.sxdc.download

import com.v1per.sxdc.data.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #43: a service instance that onTimeout stopped while its download was still running, and the
 * instance started after it. Each builds its own QueueProcessor; [Harness.secondInstance] is the
 * second one, over the same queue and, unless a test says otherwise, the same run lock.
 */
class QueueProcessorOverlapTest {
    private val h = Harness()
    private val urlA = "https://youtu.be/a"
    private val urlB = "https://youtu.be/b"
    private val stagedA = File("/staging/a.flac")

    /** Blocks the download of [urlA] on a worker thread until [release] completes. */
    private val enteredA = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    /** What the stale call's discard had cleared by the time B's download started. */
    private var clearedWhenBStarted: List<File>? = null

    private fun blockA() {
        h.bridge.onDownload = { url ->
            if (url == urlA) {
                enteredA.complete(Unit)
                runBlocking { release.await() }
            } else {
                clearedWhenBStarted = h.clearedStaged.toList()
            }
        }
    }

    /** What DownloadService.onTimeout does to the stale instance. */
    private suspend fun timeOut(stale: QueueProcessor, jobId: Long) {
        stale.fatalStop = true
        stale.markAborted(jobId)
        stale.failJobById(jobId, "timed out")
    }

    @Test
    fun secondInstance_claimsNothingUntilTheStaleRunHasDiscardedAndSwept() = runBlocking {
        val a = h.store.seed(urlA)
        val b = h.store.seed(urlB)
        blockA()

        withTimeout(TIMEOUT_MS) {
            val staleRun = launch(Dispatchers.Default) { h.processor.process(null) }
            enteredA.await()
            timeOut(h.processor, a)

            val second = h.secondInstance()
            val secondRun = launch(Dispatchers.Default) { second.process(null) }
            delay(WAIT_MS)
            // Waiting on the shared lock: no partial sweep, no claim, no download yet.
            assertEquals(1, h.mover.sweepPartialsCalls)
            assertEquals(listOf("claimNext:$a"), h.log.only("claimNext"))
            assertFalse(h.log.all().contains("download:$urlB"))

            release.complete(Unit)
            staleRun.join()
            secondRun.join()
        }

        val events = h.log.all()
        val staleSweep = events.indexOf("sweep")
        val secondPartialSweep = events.lastIndexOf("sweepPartials")
        assertTrue(events.indexOf("downloadDone:$urlA") < staleSweep)
        assertTrue("partials swept before the stale run was over: $events", staleSweep < secondPartialSweep)
        assertTrue(secondPartialSweep < events.indexOf("claimNext:$b"))
        assertEquals(listOf(stagedA), clearedWhenBStarted)

        assertEquals(DownloadStatus.FAILED, h.store.job(a).status)
        assertEquals("timed out", h.store.job(a).failureReason)
        assertEquals(DownloadStatus.COMPLETED, h.store.job(b).status)
        assertEquals(listOf(a), h.notifier.failures.map { it.jobId })
        assertEquals(listOf(b), h.notifier.successes.map { it.jobId })
        // One move, B's: the stale result was discarded, not delivered.
        assertEquals(listOf("/staging/b.flac"), h.mover.moves.map { it.first.path })
        assertEquals(2, h.cleaner.sweeps)
        assertTrue(h.halts.recorded.isEmpty())
    }

    @Test
    fun staleAbortedResult_thatWouldHalt_recordsNoHalt_andTheNextInstanceStillDrains() = runBlocking {
        val a = h.store.seed(urlA)
        val b = h.store.seed(urlB)
        // Without the timeout this result would halt the whole queue.
        h.bridge.results[urlA] = FakeDownloadBridge.failed(ErrorKinds.RATE_LIMITED)
        blockA()

        withTimeout(TIMEOUT_MS) {
            val staleRun = launch(Dispatchers.Default) { h.processor.process(null) }
            enteredA.await()
            timeOut(h.processor, a)
            val secondRun = launch(Dispatchers.Default) { h.secondInstance().process(null) }
            release.complete(Unit)
            staleRun.join()
            secondRun.join()
        }

        assertTrue(h.halts.recorded.isEmpty())
        assertEquals("timed out", h.store.job(a).failureReason)
        assertEquals(DownloadStatus.COMPLETED, h.store.job(b).status)
        assertEquals(listOf(a), h.notifier.failures.map { it.jobId })
    }

    /** Why the lock is process-wide: with one lock per instance, as before #43, the runs overlap. */
    @Test
    fun withALockPerInstance_theSecondInstanceDownloadsWhileTheStaleCallIsStillRunning() = runBlocking {
        val a = h.store.seed(urlA)
        h.store.seed(urlB)
        blockA()

        withTimeout(TIMEOUT_MS) {
            val staleRun = launch(Dispatchers.Default) { h.processor.process(null) }
            enteredA.await()
            timeOut(h.processor, a)
            val ownLock = h.secondInstance(Mutex())
            val secondRun = launch(Dispatchers.Default) { ownLock.process(null) }
            secondRun.join()

            assertTrue(h.log.all().contains("download:$urlB"))
            assertFalse("the stale call has not returned", h.log.all().contains("downloadDone:$urlA"))

            release.complete(Unit)
            staleRun.join()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val WAIT_MS = 300L
    }
}
