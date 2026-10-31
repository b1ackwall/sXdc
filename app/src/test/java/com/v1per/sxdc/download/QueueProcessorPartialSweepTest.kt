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
import org.junit.Assert.assertTrue
import org.junit.Test

/** When QueueProcessor sweeps leftover temporary documents out of the destination. */
class QueueProcessorPartialSweepTest {

    private val h = Harness()

    private fun seed(n: Int) = (1..n).map { h.store.seed("https://youtu.be/j$it") }

    @Test
    fun sweepsPartialsOncePerProcess_beforeAnythingElse() = runTest {
        seed(2)

        h.processor.process(null)

        assertEquals(1, h.mover.sweepPartialsCalls)
        val events = h.log.all()
        assertEquals("sweepPartials", events.first())
        assertTrue(events.indexOf("sweepPartials") < events.indexOf("move:/staging/j1.flac"))
        assertEquals(listOf("sweepPartials", "move", "move"), h.log.names("sweepPartials", "move"))
    }

    @Test
    fun sweepsPartialsBeforeExpandingASharedUrl() = runTest {
        h.processor.process("https://youtu.be/new")

        assertEquals(listOf("sweepPartials", "expand", "move"), h.log.names("sweepPartials", "expand", "move"))
    }

    @Test
    fun sweepsPartialsOnceForEachProcessCall() = runTest {
        seed(1)
        h.processor.process(null)
        h.processor.process(null)

        assertEquals(2, h.mover.sweepPartialsCalls)
    }

    @Test
    fun sweepsPartialsEvenWhenFatalStopWasAlreadySet() = runTest {
        seed(1)
        h.processor.fatalStop = true

        h.processor.process(null)

        assertEquals(1, h.mover.sweepPartialsCalls)
        assertTrue(h.log.only("move").isEmpty())
    }

    @Test
    fun failingPartialSweep_doesNotStopTheRun() = runTest {
        val (a, b) = seed(2)
        h.mover.sweepThrows = IllegalStateException("provider exploded")

        h.processor.process(null)

        assertEquals(DownloadStatus.COMPLETED, h.store.job(a).status)
        assertEquals(DownloadStatus.COMPLETED, h.store.job(b).status)
        assertEquals(1, h.cleaner.sweeps)
    }

    @Test
    fun partialSweep_runsInsideTheLock() = runBlocking {
        val first = "https://youtu.be/first"
        val second = "https://youtu.be/second"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Block the first run's sweep; the second run must not get past the lock meanwhile.
        h.mover.onSweep = {
            if (h.mover.sweepPartialsCalls == 1) {
                entered.complete(Unit)
                runBlocking { release.await() }
            }
        }

        withTimeout(10_000) {
            val a = launch(Dispatchers.Default) { h.processor.process(first) }
            entered.await()

            val b = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                h.processor.process(second)
            }
            assertEquals("second run swept while the first held the lock", 1, h.mover.sweepPartialsCalls)
            assertTrue(h.log.only("expand:$second").isEmpty())

            release.complete(Unit)
            a.join()
            b.join()
        }

        val events = h.log.all()
        assertEquals(2, h.mover.sweepPartialsCalls)
        assertTrue(events.indexOf("move:/staging/first.flac") < events.lastIndexOf("sweepPartials"))
    }
}
