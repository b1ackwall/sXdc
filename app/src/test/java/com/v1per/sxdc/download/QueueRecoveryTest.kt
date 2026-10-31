package com.v1per.sxdc.download

import com.v1per.sxdc.R
import com.v1per.sxdc.data.DownloadStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The orchestration only. The requeue / attempt-limit SQL itself is covered by the instrumented
 * DownloadJobDaoLifecycleTest.
 */
class QueueRecoveryTest {

    private val log = EventLog()
    private val store = FakeJobStore(log)
    private val cleared = mutableListOf<File>()
    private val recovery = QueueRecovery(store, FakeStringProvider(), fixedClock) { file ->
        log.add("clear:${file.path}")
        cleared += file
    }

    @Test
    fun clearsStagedFilesOfOrphans_thenRecoversOnce() = runTest {
        store.seed("https://a", DownloadStatus.DOWNLOADING, stagedFilePath = "/staging/a.flac")
        store.seed("https://b", DownloadStatus.DOWNLOADING, stagedFilePath = null)
        store.seed("https://c", DownloadStatus.DOWNLOADING, stagedFilePath = "/staging/c.flac")
        store.seed("https://d", DownloadStatus.FAILED, stagedFilePath = "/staging/d.flac")

        recovery.recover()

        assertEquals(listOf(File("/staging/a.flac"), File("/staging/c.flac")), cleared)
        assertEquals(listOf(FakeStringProvider.render(R.string.error_gave_up) to FIXED_NOW), store.recoverCalls)
        assertEquals(
            listOf("orphanedJobs", "clear", "clear", "recoverOrphans"),
            log.names("orphanedJobs", "clear", "recoverOrphans"),
        )
    }

    @Test
    fun orphansWithoutStagedFiles_stillRecovered() = runTest {
        store.seed("https://a", DownloadStatus.DOWNLOADING)

        recovery.recover()

        assertTrue(cleared.isEmpty())
        assertEquals(1, store.recoverCalls.size)
    }

    /** Pins the early return carried over from DownloadQueue.recoverOrphans. */
    @Test
    fun noOrphans_doesNothing() = runTest {
        store.seed("https://a", DownloadStatus.QUEUED, stagedFilePath = "/staging/a.flac")

        recovery.recover()

        assertTrue(cleared.isEmpty())
        assertTrue(store.recoverCalls.isEmpty())
    }
}
