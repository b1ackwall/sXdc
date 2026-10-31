package com.v1per.sxdc.download

import com.v1per.sxdc.data.DownloadStatus
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timeout / late-return race: guarded terminal writes and the abort flag. */
class QueueProcessorAbortTest {

    private val h = Harness()
    private val url = "https://youtu.be/a"
    private val staged = File("/staging/a.flac")

    @Test
    fun lateReturnAfterAbort_writesNothing_movesNothing_notifiesNothing_andCleansStaging() = runTest {
        val id = h.store.seed(url)
        // What onTimeout does while the blocking download is still running.
        h.bridge.onDownload = {
            h.processor.markAborted(id)
            runBlockingFail(id)
        }

        h.processor.process(null)

        assertTrue(h.log.only("markStaged", "move", "markCompleted", "success").isEmpty())
        assertEquals(DownloadStatus.FAILED, h.store.job(id).status)
        assertEquals("timed out", h.store.job(id).failureReason)
        assertEquals(1, h.log.only("markFailed").size)
        assertTrue(h.notifier.successes.isEmpty())
        assertEquals(listOf(id), h.notifier.failures.map { it.jobId })
        assertEquals(listOf(File("/staging/a.flac")), h.clearedStaged.toList())
    }

    @Test
    fun lateFailureAfterAbort_doesNotOverwriteOrNotifyAgain() = runTest {
        val id = h.store.seed(url)
        h.bridge.results[url] = FakeDownloadBridge.failed(ErrorKinds.NETWORK)
        h.bridge.onDownload = {
            h.processor.markAborted(id)
            runBlockingFail(id)
        }

        h.processor.process(null)

        assertEquals("timed out", h.store.job(id).failureReason)
        assertEquals(1, h.notifier.failures.size)
    }

    @Test
    fun markStagedReturningZero_skipsMove_cleansStaging_noNotification() = runTest {
        val id = h.store.seed(url)
        // Retired by someone else after the download, before markStaged.
        h.bridge.onDownload = { h.store.setStatus(id, DownloadStatus.FAILED) }

        h.processor.process(null)

        assertTrue(h.log.only("move", "markCompleted").isEmpty())
        assertEquals(listOf(staged), h.clearedStaged.toList())
        assertTrue(h.notifier.successes.isEmpty())
        assertTrue(h.notifier.failures.isEmpty())
        assertEquals(DownloadStatus.FAILED, h.store.job(id).status)
    }

    @Test
    fun markCompletedReturningZero_noSuccessNotification_fileNotTouched() = runTest {
        val id = h.store.seed(url)
        h.mover.results[staged.path] = MoveResult.Moved("a.flac")
        h.mover.afterMove = { h.store.setStatus(id, DownloadStatus.FAILED) }

        h.processor.process(null)

        assertEquals(1, h.mover.moves.size)
        assertTrue(h.notifier.successes.isEmpty())
        assertTrue(h.clearedStaged.isEmpty())
        assertEquals(DownloadStatus.FAILED, h.store.job(id).status)
    }

    @Test
    fun failJobById_onCompletedRow_leavesItCompleted_andPostsNothing() = runTest {
        val id = h.store.seed(url, DownloadStatus.COMPLETED)

        h.processor.failJobById(id, "timed out")

        assertEquals(DownloadStatus.COMPLETED, h.store.job(id).status)
        assertTrue(h.notifier.failures.isEmpty())
    }

    @Test
    fun failJobById_onDownloadingRow_stillFailsAndNotifies() = runTest {
        val id = h.store.seed(url, DownloadStatus.DOWNLOADING)

        h.processor.failJobById(id, "timed out")

        assertEquals(DownloadStatus.FAILED, h.store.job(id).status)
        assertEquals(1, h.notifier.failures.size)
    }

    @Test
    fun abortOfPreviousJob_doesNotAffectNextJob() = runTest {
        val first = h.store.seed("https://youtu.be/first")
        val second = h.store.seed("https://youtu.be/second")
        h.bridge.onDownload = { u ->
            if (u.endsWith("first")) {
                h.processor.markAborted(first)
                runBlockingFail(first)
            }
        }

        h.processor.process(null)

        assertEquals(DownloadStatus.FAILED, h.store.job(first).status)
        assertEquals(DownloadStatus.COMPLETED, h.store.job(second).status)
        assertNull(h.processor.abortedJobId)
        assertEquals(listOf(second), h.notifier.successes.map { it.jobId })
    }

    @Test
    fun abortFlag_isClearedWhenTheNextJobStarts() = runTest {
        val id = h.store.seed(url)
        h.processor.markAborted(id + 99)

        h.processor.process(null)

        assertNull(h.processor.abortedJobId)
        assertEquals(DownloadStatus.COMPLETED, h.store.job(id).status)
    }

    private fun runBlockingFail(id: Long) = kotlinx.coroutines.runBlocking {
        h.processor.failJobById(id, "timed out")
    }
}
