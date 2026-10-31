package com.v1per.sxdc.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadJobDaoLifecycleTest {

    @get:Rule
    val dbRule = InMemoryDatabaseRule()

    private val dao get() = dbRule.dao

    private val reason = "Gave up after repeated crashes"
    private val now = 9_999L

    @Test
    fun markStaged_setsTitleAndPath_keepsStatus() = runBlocking {
        val id = dao.insert(job(status = DownloadStatus.DOWNLOADING))
        dao.markStaged(id, "Song", "/staging/song.flac")

        val row = dao.byId(id)
        assertEquals("Song", row.title)
        assertEquals("/staging/song.flac", row.stagedFilePath)
        assertEquals(DownloadStatus.DOWNLOADING, row.status)
    }

    @Test
    fun markCompleted_setsCompleted_andClearsStagedPathAndFailureReason() = runBlocking {
        val id = dao.insert(job(status = DownloadStatus.DOWNLOADING))
        dao.markStaged(id, "Old", "/staging/x.flac")
        dao.markFailed(id, "boom", 500L)
        assertEquals("boom", dao.byId(id).failureReason)

        dao.markCompleted(id, "New title", 1_234L)

        val row = dao.byId(id)
        assertEquals(DownloadStatus.COMPLETED, row.status)
        assertEquals("New title", row.title)
        assertEquals(1_234L, row.completedAt)
        assertNull(row.stagedFilePath)
        assertNull(row.failureReason)
    }

    @Test
    fun markFailed_setsFailedReasonAndTime_leavesStagedPath() = runBlocking {
        val id = dao.insert(job(status = DownloadStatus.DOWNLOADING))
        dao.markStaged(id, "T", "/staging/keep.flac")

        dao.markFailed(id, "SAF write failed", 777L)

        val row = dao.byId(id)
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals("SAF write failed", row.failureReason)
        assertEquals(777L, row.completedAt)
        assertEquals("/staging/keep.flac", row.stagedFilePath)
        assertEquals("T", row.title)
    }

    @Test
    fun recoverOrphans_requeuesBelowMax_failsAtMax() = runBlocking {
        assertEquals(3, DownloadJobDao.MAX_ATTEMPTS)
        val fresh = dao.insert(job(status = DownloadStatus.DOWNLOADING, attempts = 0, stagedFilePath = "/s/0"))
        val two = dao.insert(job(status = DownloadStatus.DOWNLOADING, attempts = 2, stagedFilePath = "/s/2"))
        val three = dao.insert(job(status = DownloadStatus.DOWNLOADING, attempts = 3, stagedFilePath = "/s/3"))

        dao.recoverOrphans(reason, now)

        dao.byId(fresh).let {
            assertEquals(DownloadStatus.QUEUED, it.status)
            assertEquals(1, it.attempts)
            assertNull(it.stagedFilePath)
            assertNull(it.failureReason)
            assertNull(it.completedAt)
        }
        // attempts 2 -> requeued to 3, and NOT failed in the same call (fail runs before requeue).
        dao.byId(two).let {
            assertEquals(DownloadStatus.QUEUED, it.status)
            assertEquals(3, it.attempts)
            assertNull(it.stagedFilePath)
            assertNull(it.failureReason)
        }
        dao.byId(three).let {
            assertEquals(DownloadStatus.FAILED, it.status)
            assertEquals(3, it.attempts)
            assertEquals(reason, it.failureReason)
            assertEquals(now, it.completedAt)
            assertNull(it.stagedFilePath)
        }
    }

    @Test
    fun recoverOrphans_leavesNonDownloadingRowsUntouched() = runBlocking {
        val untouched = listOf(
            DownloadStatus.QUEUED, DownloadStatus.COMPLETED,
            DownloadStatus.FAILED, DownloadStatus.CANCELLED,
        ).map { status ->
            dao.insert(
                job(
                    url = "https://example.com/${status.name}",
                    status = status,
                    attempts = 3,
                    stagedFilePath = "/s/${status.name}",
                    failureReason = "r-${status.name}",
                    completedAt = 42L,
                ),
            )
        }
        val before = untouched.map { dao.byId(it) }

        dao.recoverOrphans(reason, now)

        assertEquals(before, untouched.map { dao.byId(it) })
    }

    @Test
    fun recoverOrphans_respectsCustomMaxAttempts() = runBlocking {
        val zero = dao.insert(job(status = DownloadStatus.DOWNLOADING, attempts = 0))
        val one = dao.insert(job(status = DownloadStatus.DOWNLOADING, attempts = 1))

        dao.recoverOrphans(reason, now, maxAttempts = 1)

        assertEquals(DownloadStatus.QUEUED, dao.byId(zero).status)
        assertEquals(1, dao.byId(zero).attempts)
        assertEquals(DownloadStatus.FAILED, dao.byId(one).status)
        assertEquals(reason, dao.byId(one).failureReason)
    }

    @Test
    fun orphanedJobs_returnsExactlyDownloadingRows() = runBlocking {
        val downloading = mutableSetOf<Long>()
        DownloadStatus.entries.forEachIndexed { i, status ->
            val id = dao.insert(job(url = "https://example.com/$i", status = status))
            if (status == DownloadStatus.DOWNLOADING) downloading += id
        }
        downloading += dao.insert(job(url = "https://example.com/d2", status = DownloadStatus.DOWNLOADING))

        val orphans = dao.orphanedJobs()
        assertEquals(downloading, orphans.map { it.id }.toSet())
        assertEquals(orphans.size, orphans.map { it.id }.toSet().size)
    }

    @Test
    fun cancelAllPending_cancelsOnlyQueued() = runBlocking {
        val q1 = dao.insert(job(url = "https://example.com/q1"))
        val q2 = dao.insert(job(url = "https://example.com/q2"))
        val others = listOf(
            DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED,
            DownloadStatus.FAILED, DownloadStatus.CANCELLED,
        ).map { dao.insert(job(url = "https://example.com/${it.name}", status = it, completedAt = 5L)) }
        val othersBefore = others.map { dao.byId(it) }

        dao.cancelAllPending(now)

        listOf(q1, q2).forEach {
            assertEquals(DownloadStatus.CANCELLED, dao.byId(it).status)
            assertEquals(now, dao.byId(it).completedAt)
        }
        assertEquals(othersBefore, others.map { dao.byId(it) })
    }

    @Test
    fun cancelAllPending_onEmptyTable_isNoOp() = runBlocking {
        dao.cancelAllPending(now)
        assertEquals(0, dao.snapshot().size)
    }

    @Test
    fun clearHistory_deletesFinishedRows_keepsPending() = runBlocking {
        val ids = DownloadStatus.entries.associateWith {
            dao.insert(job(url = "https://example.com/${it.name}", status = it))
        }

        dao.clearHistory()

        val remaining = dao.snapshot().map { it.id }.toSet()
        assertEquals(
            setOf(ids.getValue(DownloadStatus.QUEUED), ids.getValue(DownloadStatus.DOWNLOADING)),
            remaining,
        )
    }
}
