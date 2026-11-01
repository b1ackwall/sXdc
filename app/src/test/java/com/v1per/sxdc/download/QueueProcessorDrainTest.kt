package com.v1per.sxdc.download

import com.v1per.sxdc.R
import com.v1per.sxdc.data.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QueueProcessorDrainTest {

    private val h = Harness()
    private val store get() = h.store

    private fun seed(n: Int, title: (Int) -> String? = { null }) =
        (1..n).map { store.seed("https://youtu.be/j$it", title = title(it)) }

    @Test
    fun jobsProcessedOldestFirst() = runTest {
        val ids = seed(3)
        h.processor.process(null)
        assertEquals(ids.map { "claimNext:$it" } + "claimNext:null", h.log.only("claimNext"))
        assertEquals(ids.map { "https://youtu.be/j${it}" }, h.bridge.downloadArgs.map { it.first })
    }

    @Test
    fun successPath_callOrder() = runTest {
        val id = seed(1).single()
        h.processor.process(null)
        assertEquals(
            listOf("progress", "preconditions", "download", "markStaged", "move", "markCompleted", "success"),
            h.log.names("progress", "preconditions", "download", "markStaged", "move", "markCompleted", "success"),
        )
        assertEquals(DownloadStatus.COMPLETED, store.job(id).status)
        assertEquals(FIXED_NOW, store.job(id).completedAt)
    }

    @Test
    fun successPath_passesPreconditionPathsToBridgeAndMover() = runTest {
        seed(1)
        h.processor.process(null)
        val (_, outDir, binDir) = h.bridge.downloadArgs.single()
        assertEquals(FakePreconditionChecker.OUT_DIR, outDir)
        assertEquals(FakePreconditionChecker.BIN_DIR, binDir)
        assertEquals(FakePreconditionChecker.DESTINATION, h.mover.moves.single().second)
    }

    @Test
    fun success_titleFallbacks() = runTest {
        val id = store.seed("https://youtu.be/t", title = "Queued Title")
        h.bridge.results["https://youtu.be/t"] = FakeDownloadBridge.ok(path = "/staging/song.flac", title = null)

        h.processor.process(null)

        // Completed row falls back to the job's title; the notification falls back to the URL.
        assertEquals("Queued Title", store.job(id).title)
        val success = h.notifier.successes.single()
        assertEquals(id, success.jobId)
        assertEquals("https://youtu.be/t", success.title)
        assertEquals(FakeStringProvider.render(R.string.notif_saved_to, "song.flac"), success.text)
    }

    @Test
    fun success_resultTitleWins() = runTest {
        val id = store.seed("https://youtu.be/t", title = "Queued Title")
        h.bridge.results["https://youtu.be/t"] = FakeDownloadBridge.ok(path = "/staging/s.flac", title = "Real Title")

        h.processor.process(null)

        assertEquals("Real Title", store.job(id).title)
        assertEquals("Real Title", h.notifier.successes.single().title)
    }

    @Test
    fun progress_labelAndPositions() = runTest {
        seed(3) { if (it == 2) "Second" else null }
        h.processor.process(null)
        assertEquals(
            listOf(
                RecordingQueueNotifier.Progress("https://youtu.be/j1", 1, 3),
                RecordingQueueNotifier.Progress("Second", 2, 3),
                RecordingQueueNotifier.Progress("https://youtu.be/j3", 3, 3),
            ),
            h.notifier.progress,
        )
    }

    @Test
    fun perUrlFailure_failsOnlyThatJob_andNextRuns() = runTest {
        val (a, b) = seed(2)
        h.bridge.results["https://youtu.be/j1"] = FakeDownloadBridge.failed(ErrorKinds.NETWORK)

        h.processor.process(null)

        assertEquals(DownloadStatus.FAILED, store.job(a).status)
        assertEquals(FakeStringProvider.render(R.string.error_network), store.job(a).failureReason)
        assertEquals(FIXED_NOW, store.job(a).completedAt)
        assertEquals(listOf(a), h.notifier.failures.map { it.jobId })
        assertEquals(DownloadStatus.COMPLETED, store.job(b).status)
        assertFalse(h.processor.fatalStop)
    }

    @Test
    fun storageFailure_stopsRun_laterJobsStayQueued() = runTest {
        val (a, b, c) = seed(3)
        h.bridge.results["https://youtu.be/j1"] = FakeDownloadBridge.failed(ErrorKinds.STORAGE)

        h.processor.process(null)

        assertTrue(h.processor.fatalStop)
        assertEquals(DownloadStatus.FAILED, store.job(a).status)
        assertEquals(FakeStringProvider.render(R.string.error_storage), store.job(a).failureReason)
        assertEquals(DownloadStatus.QUEUED, store.job(b).status)
        assertEquals(DownloadStatus.QUEUED, store.job(c).status)
        assertEquals(1, h.bridge.downloadArgs.size)
    }

    @Test
    fun preconditionsFailed_failsJobWithJoinedReasons_andStops() = runTest {
        val (a, b) = seed(2)
        h.preconditions.result = PreconditionResult.Failed(listOf("No folder.", "No ffmpeg."))

        h.processor.process(null)

        assertTrue(h.processor.fatalStop)
        assertEquals("No folder. No ffmpeg.", store.job(a).failureReason)
        assertEquals(listOf("No folder. No ffmpeg."), h.notifier.failures.map { it.text })
        assertEquals(DownloadStatus.QUEUED, store.job(b).status)
        assertTrue(h.log.only("download").isEmpty())
    }

    @Test
    fun moveFailed_notQueueWide_continues() = runTest {
        val (a, b) = seed(2)
        h.mover.results["/staging/j1.flac"] = MoveResult.Failed("copy failed")

        h.processor.process(null)

        assertEquals(DownloadStatus.FAILED, store.job(a).status)
        assertEquals("copy failed", store.job(a).failureReason)
        // markFailed leaves the staged path, recorded by markStaged before the move.
        assertEquals("/staging/j1.flac", store.job(a).stagedFilePath)
        assertEquals(DownloadStatus.COMPLETED, store.job(b).status)
        assertFalse(h.processor.fatalStop)
    }

    @Test
    fun moveFailed_queueWide_stops() = runTest {
        val (a, b) = seed(2)
        h.mover.results["/staging/j1.flac"] = MoveResult.Failed("folder lost", queueWide = true)

        h.processor.process(null)

        assertTrue(h.processor.fatalStop)
        assertEquals(DownloadStatus.FAILED, store.job(a).status)
        assertEquals(DownloadStatus.QUEUED, store.job(b).status)
    }

    @Test
    fun markStaged_happensBeforeMove() = runTest {
        val a = seed(1).single()
        var stagedAtMoveTime: String? = null
        h.mover.results["/staging/j1.flac"] = MoveResult.Failed("x")
        // Observe the row at the moment the mover runs, via a mover wrapper.
        val observing = object : FileMover {
            override fun move(result: JobResult, destination: String): MoveResult {
                stagedAtMoveTime = store.job(a).stagedFilePath
                return h.mover.move(result, destination)
            }

            override fun sweepPartials(): Int = h.mover.sweepPartials()
        }
        QueueProcessor(
            store, h.bridge, observing, h.preconditions, h.notifier, h.strings, fixedClock, h.cleaner,
            awaitRecovery = {},
        ).process(null)
        assertEquals("/staging/j1.flac", stagedAtMoveTime)
    }

    @Test
    fun unexpectedBridgeException_failsWithUnknown_andContinues() = runTest {
        val (a, b) = seed(2)
        h.bridge.downloadThrows["https://youtu.be/j1"] = IllegalStateException("boom")

        h.processor.process(null)

        assertEquals(FakeStringProvider.render(R.string.error_unknown), store.job(a).failureReason)
        assertEquals(listOf(a), h.notifier.failures.map { it.jobId })
        assertEquals(DownloadStatus.COMPLETED, store.job(b).status)
        assertNull(h.processor.currentJobId)
    }

    @Test
    fun cancellationFromBridge_propagates_andLeavesState() = runTest {
        val (a, b) = seed(2)
        h.bridge.downloadThrows["https://youtu.be/j1"] = CancellationException("scope gone")

        try {
            h.processor.process(null)
            fail("CancellationException was swallowed")
        } catch (_: CancellationException) {
        }

        assertNull(h.processor.currentJobId)
        assertFalse(h.processor.fatalStop)
        assertEquals(DownloadStatus.DOWNLOADING, store.job(a).status)
        assertEquals(DownloadStatus.QUEUED, store.job(b).status)
        assertTrue(h.notifier.failures.isEmpty())
    }

    @Test
    fun fatalStopBeforeProcess_claimsNothing() = runTest {
        val a = seed(1).single()
        h.processor.fatalStop = true

        h.processor.process(null)

        assertTrue(h.log.only("claimNext").isEmpty())
        assertEquals(DownloadStatus.QUEUED, store.job(a).status)
    }

    @Test
    fun currentJobId_setDuringJob_nullAfter() = runTest {
        val ids = seed(2)
        val seen = mutableListOf<Long?>()
        h.bridge.onDownload = { seen += h.processor.currentJobId }
        assertNull(h.processor.currentJobId)

        h.processor.process(null)

        assertEquals(ids, seen)
        assertNull(h.processor.currentJobId)
    }
}
