package com.v1per.sxdc.download

import com.v1per.sxdc.data.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The startup-recovery gate (issue #67): no claim before recovery is over. */
class QueueProcessorRecoveryGateTest {

    private val gate = CompletableDeferred<Unit>()
    private val h = Harness(awaitRecovery = { gate.await() })

    @Test
    fun process_doesNotClaimUntilTheGateOpens() = runTest {
        val a = h.store.seed("https://youtu.be/j1")

        val run = launch { h.processor.process(null) }
        advanceUntilIdle()

        assertTrue(h.log.only("claimNext").isEmpty())
        assertEquals(DownloadStatus.QUEUED, h.store.job(a).status)

        gate.complete(Unit)
        run.join()

        assertEquals(DownloadStatus.COMPLETED, h.store.job(a).status)
    }

    @Test
    fun expansionAndEnqueue_runBeforeTheGateOpens() = runTest {
        val run = launch { h.processor.process("https://youtu.be/new") }
        advanceUntilIdle()

        assertEquals(listOf("expand", "insert"), h.log.names("expand", "insert", "claimNext"))

        gate.complete(Unit)
        run.join()

        assertEquals(listOf("expand", "insert", "claimNext"), h.log.names("expand", "insert", "claimNext").take(3))
    }

    @Test
    fun alreadyOpenGate_addsNoDelay() = runTest {
        gate.complete(Unit)
        val a = h.store.seed("https://youtu.be/j1")

        h.processor.process(null)

        assertEquals(0L, testScheduler.currentTime)
        assertEquals(DownloadStatus.COMPLETED, h.store.job(a).status)
    }

    @Test
    fun throwingRecovery_stillOpensTheGate_andDoesNotThrow() = runTest {
        var opened = false

        StartupRecovery.run(
            recover = { throw IllegalStateException("database exploded") },
            markDone = { opened = true },
        )

        assertTrue(opened)
    }

    @Test
    fun successfulRecovery_opensTheGateOnlyAfterItFinishes() = runTest {
        val events = mutableListOf<String>()

        StartupRecovery.run(recover = { events += "recover" }, markDone = { events += "open" })

        assertEquals(listOf("recover", "open"), events)
    }

    @Test
    fun gateOpenedByRecovery_releasesAWaitingRun() = runTest {
        val a = h.store.seed("https://youtu.be/j1")
        val run = launch { h.processor.process(null) }
        advanceUntilIdle()
        assertFalse(run.isCompleted)

        StartupRecovery.run(recover = { throw IllegalStateException("boom") }, markDone = { gate.complete(Unit) })
        run.join()

        assertEquals(DownloadStatus.COMPLETED, h.store.job(a).status)
    }
}
