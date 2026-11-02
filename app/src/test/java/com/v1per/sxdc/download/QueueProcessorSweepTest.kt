package com.v1per.sxdc.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** When QueueProcessor sweeps the staging folder, and when it must not. */
class QueueProcessorSweepTest {

    private val h = Harness()

    private fun seed(n: Int) = (1..n).map { h.store.seed("https://youtu.be/j$it") }

    @Test
    fun sweepsOncePerProcess_afterTheLastJobsNotification() = runTest {
        seed(2)

        h.processor.process(null)

        assertEquals(1, h.cleaner.sweeps)
        assertEquals("sweep", h.log.all().last())
        assertEquals(
            listOf("success", "success", "sweep"),
            h.log.names("success", "failure", "sweep"),
        )
    }

    @Test
    fun sweepsOnceForEachProcessCall() = runTest {
        seed(1)
        h.processor.process(null)
        h.processor.process(null)

        assertEquals(2, h.cleaner.sweeps)
    }

    @Test
    fun sweepComesAfterTheFinalCompletionEvent() = runTest {
        val (a, b) = seed(2)
        h.bridge.results["https://youtu.be/j2"] = FakeDownloadBridge.failed(ErrorKinds.NETWORK)

        h.processor.process(null)

        val events = h.log.all()
        assertEquals(
            listOf("markCompleted:$a", "markFailed:$b", "sweep"),
            h.log.only("markCompleted", "markFailed", "sweep"),
        )
        assertTrue(events.indexOf("sweep") > events.indexOf("failure:$b"))
        assertTrue(events.indexOf("sweep") > events.lastIndexOf("claimNext:null"))
    }

    @Test
    fun sweepsForAnEmptyQueue() = runTest {
        h.processor.process(null)

        assertEquals(1, h.cleaner.sweeps)
        assertEquals(listOf("sweepPartials", "claimNext:null", "sweep"), h.log.all())
    }

    @Test
    fun sweepsAfterEnqueueingWhenTheUrlWasShared() = runTest {
        h.processor.process("https://youtu.be/new")

        assertEquals(1, h.cleaner.sweeps)
        assertEquals("sweep", h.log.all().last())
    }

    @Test
    fun sweepsWhenAFatalStopEndedTheRun() = runTest {
        seed(2)
        h.bridge.results["https://youtu.be/j1"] = FakeDownloadBridge.failed(ErrorKinds.STORAGE)

        h.processor.process(null)

        assertTrue(h.processor.fatalStop)
        assertEquals(1, h.cleaner.sweeps)
        assertEquals(listOf("failure", "sweep"), h.log.names("failure", "sweep"))
    }

    @Test
    fun sweepsAfterARateLimitedStop() = runTest {
        seed(2)
        h.bridge.results["https://youtu.be/j1"] = FakeDownloadBridge.failed(ErrorKinds.RATE_LIMITED)

        h.processor.process(null)

        assertTrue(h.processor.fatalStop)
        assertEquals(1, h.cleaner.sweeps)
        assertEquals(listOf("failure", "halt", "sweep"), h.log.names("failure", "halt", "sweep"))
    }

    @Test
    fun sweepsWhenAHaltBlocksTheClaim() = runTest {
        seed(1)
        h.halts.halt = QueueHalt(HaltKind.STORAGE, FIXED_NOW)

        h.processor.process(null)

        assertEquals(listOf("sweepPartials", "countPending", "sweep"), h.log.all())
    }

    @Test
    fun sweepsWhenFatalStopWasAlreadySet() = runTest {
        seed(1)
        h.processor.fatalStop = true

        h.processor.process(null)

        assertEquals(listOf("sweepPartials", "sweep"), h.log.all())
    }

    @Test
    fun doesNotSweepWhenTheBridgeThrowsCancellation() = runTest {
        seed(2)
        h.bridge.downloadThrows["https://youtu.be/j1"] = CancellationException("scope gone")

        try {
            h.processor.process(null)
            fail("CancellationException was swallowed")
        } catch (_: CancellationException) {
        }

        assertEquals(0, h.cleaner.sweeps)
        assertTrue(h.log.only("sweep").isEmpty())
    }

    @Test
    fun stillSweepsAfterAnUnexpectedBridgeException() = runTest {
        seed(1)
        h.bridge.downloadThrows["https://youtu.be/j1"] = IllegalStateException("boom")

        h.processor.process(null)

        assertEquals(1, h.cleaner.sweeps)
    }
}
