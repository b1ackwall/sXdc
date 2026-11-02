package com.v1per.sxdc.download

import com.v1per.sxdc.R
import com.v1per.sxdc.data.DownloadStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finished-file outcomes of #45 and #46 in the queue engine: the saved-without note on a
 * delivered file, and a truncated file that fails as incomplete. Kept apart from
 * QueueProcessorDrainTest, which is near the file size limit.
 */
class QueueProcessorWarningsTest {

    private val h = Harness()
    private val store get() = h.store
    private val url = "https://youtu.be/w"

    private fun render(id: Int, vararg args: Any) = FakeStringProvider.render(id, *args)

    @Test
    fun deliveredWithWarnings_noteReachesTheNotificationAndTheRow() = runTest {
        val id = store.seed(url)
        h.bridge.results[url] = FakeDownloadBridge.ok(
            path = "/staging/song.flac",
            warnings = listOf(WarningKinds.NO_TAGS, WarningKinds.NO_COVER, WarningKinds.SHORT_AUDIO),
        )

        h.processor.process(null)

        val note = render(R.string.warn_no_tags_or_cover) + " " + render(R.string.warn_short_audio)
        val success = h.notifier.successes.single()
        assertEquals(id, success.jobId)
        assertEquals(render(R.string.notif_saved_to, "song.flac"), success.text)
        assertEquals(note, success.note)
        val row = store.job(id)
        assertEquals(DownloadStatus.COMPLETED, row.status)
        assertEquals(note, row.failureReason)
        assertTrue(h.notifier.failures.isEmpty())
    }

    @Test
    fun deliveredWithoutWarnings_hasNoNote() = runTest {
        val id = store.seed(url)

        h.processor.process(null)

        assertNull(h.notifier.successes.single().note)
        assertNull(store.job(id).failureReason)
    }

    @Test
    fun onlyUnknownWarnings_areNoNote() = runTest {
        val id = store.seed(url)
        h.bridge.results[url] = FakeDownloadBridge.ok(warnings = listOf("something_new"))

        h.processor.process(null)

        assertNull(h.notifier.successes.single().note)
        assertNull(store.job(id).failureReason)
    }

    @Test
    fun incompleteFile_failsThatJobOnly_movesNothing_recordsNoHalt() = runTest {
        val a = store.seed(url)
        val b = store.seed("https://youtu.be/next")
        h.bridge.results[url] = FakeDownloadBridge.failed(ErrorKinds.INCOMPLETE)

        h.processor.process(null)

        assertEquals(DownloadStatus.FAILED, store.job(a).status)
        assertEquals(render(R.string.error_incomplete), store.job(a).failureReason)
        assertEquals(listOf(a), h.notifier.failures.map { it.jobId })
        assertEquals(listOf(0), h.notifier.failures.map { it.waiting })
        assertEquals(listOf("/staging/next.flac"), h.mover.moves.map { it.first.path })
        assertEquals(DownloadStatus.COMPLETED, store.job(b).status)
        assertFalse(h.processor.fatalStop)
        assertTrue(h.halts.recorded.isEmpty())
        assertNull(h.halts.halt)
    }

    @Test
    fun warningsOnAFailedResult_areIgnored() = runTest {
        val id = store.seed(url)
        h.bridge.results[url] = FakeDownloadBridge.failed(ErrorKinds.NETWORK)
            .copy(warnings = listOf(WarningKinds.NO_COVER))

        h.processor.process(null)

        assertEquals(render(R.string.error_network), store.job(id).failureReason)
        assertEquals(render(R.string.error_network), h.notifier.failures.single().text)
        assertTrue(h.notifier.successes.isEmpty())
    }

    // ---- a COMPLETED row with a note is a completed row everywhere ----

    @Test
    fun completedRowWithANote_isNotPending_notOrphaned_andNotRetried() = runTest {
        val done = store.seed(url)
        h.bridge.results[url] = FakeDownloadBridge.ok(warnings = listOf(WarningKinds.NO_COVER))
        h.processor.process(null)
        assertEquals(render(R.string.warn_no_cover), store.job(done).failureReason)
        val claimsBefore = h.log.only("claimNext").size

        assertEquals(0, store.countPending())
        assertTrue(store.orphanedJobs().isEmpty())
        assertTrue(h.processor.shouldStop())
        h.processor.failJobById(done, "timed out")
        h.processor.process(null)

        assertEquals(DownloadStatus.COMPLETED, store.job(done).status)
        assertEquals(render(R.string.warn_no_cover), store.job(done).failureReason)
        assertEquals(1, h.notifier.successes.size)
        assertTrue(h.notifier.failures.isEmpty())
        assertEquals(listOf("claimNext:null"), h.log.only("claimNext").drop(claimsBefore))
    }
}
