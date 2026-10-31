package com.v1per.sxdc.download

import com.v1per.sxdc.data.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueProcessorConcurrencyTest {

    private val h = Harness()

    @Test
    fun twoProcessCalls_areSerializedByTheMutex() = runBlocking {
        val first = "https://youtu.be/first"
        val second = "https://youtu.be/second"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // The bridge is blocking by contract, so the fake blocks its worker thread for `first`.
        h.bridge.onDownload = { url ->
            if (url == first) {
                entered.complete(Unit)
                runBlocking { release.await() }
            }
        }

        withTimeout(10_000) {
            val a = launch(Dispatchers.Default) { h.processor.process(first) }
            entered.await()

            // UNDISPATCHED runs B on this thread up to its first suspension. With the lock held
            // by A that suspension is the lock itself, so B must not have expanded anything yet.
            val b = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                h.processor.process(second)
            }
            assertTrue("second call ran while the first held the lock", h.log.only("expand:$second").isEmpty())

            release.complete(Unit)
            a.join()
            b.join()
        }

        val events = h.log.all()
        assertTrue(events.indexOf("downloadDone:$first") < events.indexOf("expand:$second"))
        assertTrue(h.store.jobs().all { it.status == DownloadStatus.COMPLETED })
    }

    @Test
    fun shouldStop_whenFatalStop() = runTest {
        h.store.seed("https://youtu.be/a")
        h.processor.fatalStop = true
        assertTrue(h.processor.shouldStop())
        // Short-circuits like the old stopWhenIdle: no count when already fatally stopped.
        assertTrue(h.log.only("countPending").isEmpty())
    }

    @Test
    fun shouldStop_whenNothingPending() = runTest {
        h.store.seed("https://youtu.be/a", DownloadStatus.COMPLETED)
        assertTrue(h.processor.shouldStop())
    }

    @Test
    fun shouldNotStop_whenPendingAndNoFatalStop() = runTest {
        h.store.seed("https://youtu.be/a", DownloadStatus.QUEUED)
        assertFalse(h.processor.shouldStop())
        h.store.seed("https://youtu.be/b", DownloadStatus.DOWNLOADING)
        assertFalse(h.processor.shouldStop())
    }

    @Test
    fun failJobById_marksFailedWithClock_andNotifiesOnce() = runTest {
        val id = h.store.seed("https://youtu.be/a", DownloadStatus.DOWNLOADING)

        h.processor.failJobById(id, "timed out")

        val job = h.store.job(id)
        assertEquals(DownloadStatus.FAILED, job.status)
        assertEquals("timed out", job.failureReason)
        assertEquals(FIXED_NOW, job.completedAt)
        assertEquals(listOf(RecordingQueueNotifier.Terminal(id, null, "timed out")), h.notifier.failures)
        assertEquals(listOf("markFailed", "failure"), h.log.names("markFailed", "failure"))
    }
}
