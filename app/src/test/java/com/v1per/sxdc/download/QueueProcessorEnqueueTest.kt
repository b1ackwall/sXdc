package com.v1per.sxdc.download

import com.v1per.sxdc.R
import com.v1per.sxdc.data.DownloadStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueProcessorEnqueueTest {

    private val h = Harness()
    private val url = "https://youtu.be/one"

    private fun inserts() = h.log.only("insert").map { it.substringAfter(':') }

    @Test
    fun singleNewUrl_isQueued_andToastsAdded() = runTest {
        h.processor.process(url)

        assertEquals(listOf(url), inserts())
        assertEquals(listOf(FakeStringProvider.render(R.string.toast_added_to_queue)), h.notifier.toasts)
        assertEquals(DownloadStatus.COMPLETED, h.store.jobs().single().status)
    }

    @Test
    fun duplicateOfQueuedJob_isSkipped_andToastsAlreadyQueued() = runTest {
        h.store.seed(url, DownloadStatus.QUEUED)

        h.processor.process(url)

        assertEquals(emptyList<String>(), inserts())
        assertEquals(listOf(FakeStringProvider.render(R.string.toast_already_queued)), h.notifier.toasts)
        assertEquals(1, h.store.jobs().size)
    }

    @Test
    fun duplicateOfDownloadingJob_isSkipped() = runTest {
        h.store.seed(url, DownloadStatus.DOWNLOADING)

        h.processor.process(url)

        assertEquals(emptyList<String>(), inserts())
        assertEquals(listOf(FakeStringProvider.render(R.string.toast_already_queued)), h.notifier.toasts)
    }

    @Test
    fun sameUrlAsFinishedJob_isQueuedAgain() = runTest {
        listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED).forEach { status ->
            val harness = Harness()
            harness.store.seed(url, status)

            harness.processor.process(url)

            assertEquals(status.name, listOf("insert:$url"), harness.log.only("insert"))
            assertEquals(
                listOf(FakeStringProvider.render(R.string.toast_added_to_queue)),
                harness.notifier.toasts,
            )
        }
    }

    @Test
    fun playlistOfThree_queuesThree() = runTest {
        val playlist = "https://music.youtube.com/playlist?list=PL1"
        h.bridge.expansions[playlist] = (1..3).map { Track("https://youtu.be/t$it", "Track $it") }

        h.processor.process(playlist)

        assertEquals((1..3).map { "https://youtu.be/t$it" }, inserts())
        assertEquals(listOf(FakeStringProvider.render(R.string.toast_queued_playlist, 3)), h.notifier.toasts)
        assertEquals(listOf("Track 1", "Track 2", "Track 3"), h.store.jobs().map { it.title })
    }

    @Test
    fun playlistWithOneDuplicate_queuesTwo_andReportsDuplicateCount() = runTest {
        val playlist = "https://music.youtube.com/playlist?list=PL2"
        h.store.seed("https://youtu.be/t2", DownloadStatus.QUEUED)
        h.bridge.expansions[playlist] = (1..3).map { Track("https://youtu.be/t$it", null) }

        h.processor.process(playlist)

        assertEquals(listOf("https://youtu.be/t1", "https://youtu.be/t3"), inserts())
        assertEquals(
            listOf(FakeStringProvider.render(R.string.toast_queued_playlist_with_duplicates, 2, 1)),
            h.notifier.toasts,
        )
    }

    @Test
    fun toast_comesAfterEnqueue_andBeforeDrain() = runTest {
        h.processor.process(url)
        assertEquals(listOf("expand", "insert", "toast", "claimNext"), h.log.names("expand", "insert", "toast", "claimNext").take(4))
    }

    @Test
    fun nullOrBlankUrl_skipsExpansion_butStillDrains() = runTest {
        listOf(null, "", "   ").forEach { blank ->
            val harness = Harness()
            val queued = harness.store.seed("https://youtu.be/existing")

            harness.processor.process(blank)

            assertTrue("expand called for '$blank'", harness.log.only("expand").isEmpty())
            assertTrue(harness.notifier.toasts.isEmpty())
            assertEquals(DownloadStatus.COMPLETED, harness.store.job(queued).status)
        }
    }
}
