package com.v1per.sxdc.download

import com.v1per.sxdc.R
import com.v1per.sxdc.data.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #75: a share survives a teardown during expansion; Emergency Stop also drops it. */
class QueueProcessorStopDuringExpansionTest {
    private val playlist = "https://music.youtube.com/playlist?list=PL1"
    private val tracks = (1..3).map { Track("https://youtu.be/t$it", "Track $it") }

    @Test
    fun cancellationAfterExpansion_stillQueuesThePlaylist_andDownloadsNothing() = runBlocking {
        // yield() in the recovery gate throws once the scope is cancelled, so the drain never starts.
        val h = Harness(awaitRecovery = { yield() })
        h.bridge.expansions[playlist] = tracks
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.bridge.onExpand = {
            entered.complete(Unit)
            runBlocking { release.await() }
        }

        withTimeout(TIMEOUT_MS) {
            val run = launch(Dispatchers.Default) { h.processor.process(playlist) }
            entered.await()
            run.cancel()
            release.complete(Unit)
            run.join()
        }

        assertEquals(tracks.map { it.url }, h.store.jobs().map { it.url })
        assertTrue(h.store.jobs().all { it.status == DownloadStatus.QUEUED })
        assertEquals(
            listOf(FakeStringProvider.renderPlural(R.plurals.toast_queued_playlist, 3, 3)),
            h.notifier.toasts,
        )
        assertTrue(h.log.only("claimNext", "download").isEmpty())
    }

    @Test
    fun emergencyStopDuringExpansion_queuesNothing_andShowsStopped() = runTest {
        val h = Harness()
        h.bridge.expansions[playlist] = tracks
        h.bridge.onExpand = { h.activeRun.abortCurrent() }

        h.processor.process(playlist)

        assertTrue(h.store.jobs().isEmpty())
        assertTrue(h.log.only("insert").isEmpty())
        assertEquals(listOf(FakeStringProvider.render(R.string.toast_stopped)), h.notifier.toasts)
        assertTrue(h.log.only("download").isEmpty())
    }

    @Test
    fun emergencyStopBeforeExpansion_doesNotDropALaterShare() = runTest {
        val h = Harness()
        h.activeRun.abortCurrent()

        h.processor.process("https://youtu.be/new")

        assertEquals(listOf("https://youtu.be/new"), h.store.jobs().map { it.url })
    }

    @Test
    fun stopBetweenTheClaimAndThePublish_abortsTheDownloadItself() = runTest {
        val h = Harness()
        val id = h.store.seed("https://youtu.be/j1")
        // Emergency Stop in the gap: the row is claimed, the signal is not yet published.
        h.store.afterClaim = {
            h.store.setStatus(id, DownloadStatus.CANCELLED)
            h.activeRun.abortCurrent()
        }

        h.processor.process(null)

        assertEquals(1, h.bridge.aborts.size)
        assertTrue("the signal was not requested", h.bridge.aborts.single().requested)
        assertEquals(DownloadStatus.CANCELLED, h.store.job(id).status)
        assertTrue(h.log.only("move").isEmpty())
    }

    @Test
    fun noStop_theSignalIsNeverRequestedByTheDrain() = runTest {
        val h = Harness()
        h.store.seed("https://youtu.be/j1")

        h.processor.process(null)

        assertTrue(h.bridge.aborts.none { it.requested })
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
