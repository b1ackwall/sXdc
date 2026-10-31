package com.v1per.sxdc.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadJobDaoQueueTest {

    @get:Rule
    val dbRule = InMemoryDatabaseRule()

    private val dao get() = dbRule.dao

    private suspend fun byId(id: Long): DownloadJob = dao.byId(id)

    @Test
    fun insert_returnsIncreasingIds_andAppliesEntityDefaults() = runBlocking {
        val before = System.currentTimeMillis()
        val id1 = dao.insert(DownloadJob(url = "https://example.com/1"))
        val id2 = dao.insert(DownloadJob(url = "https://example.com/2"))
        val id3 = dao.insert(DownloadJob(url = "https://example.com/3"))
        val after = System.currentTimeMillis()

        assertTrue(id1 > 0)
        assertTrue(id2 > id1)
        assertTrue(id3 > id2)

        val stored = byId(id1)
        assertEquals("https://example.com/1", stored.url)
        assertEquals(DownloadStatus.QUEUED, stored.status)
        assertEquals(0, stored.attempts)
        assertTrue(stored.createdAt in before..after)
        assertNull(stored.title)
        assertNull(stored.stagedFilePath)
        assertNull(stored.failureReason)
        assertNull(stored.completedAt)
    }

    @Test
    fun statusColumn_storesPlainEnumName() = runBlocking {
        DownloadStatus.entries.forEach { status ->
            val id = dao.insert(job(url = "https://example.com/${status.name}", status = status))
            assertEquals(status.name, dbRule.rawStatus(id))
        }
    }

    @Test
    fun countPendingForUrl_countsOnlyQueuedAndDownloading_forExactUrl() = runBlocking {
        val url = "https://example.com/watch?v=abc"
        DownloadStatus.entries.forEach { dao.insert(job(url = url, status = it)) }
        dao.insert(job(url = url, status = DownloadStatus.QUEUED))
        // Near-duplicates: exact string match only, so these are not counted.
        dao.insert(job(url = "$url/", status = DownloadStatus.QUEUED))
        dao.insert(job(url = url.uppercase(), status = DownloadStatus.QUEUED))
        dao.insert(job(url = "https://example.com/other", status = DownloadStatus.DOWNLOADING))

        assertEquals(3, dao.countPendingForUrl(url))
        assertEquals(1, dao.countPendingForUrl("$url/"))
        assertEquals(1, dao.countPendingForUrl(url.uppercase()))
        assertEquals(0, dao.countPendingForUrl("https://example.com/nothing"))
    }

    @Test
    fun countPending_countsQueuedAndDownloadingAcrossAllUrls() = runBlocking {
        assertEquals(0, dao.countPending())
        DownloadStatus.entries.forEachIndexed { i, status ->
            dao.insert(job(url = "https://example.com/$i", status = status))
            dao.insert(job(url = "https://example.com/x$i", status = status))
        }
        assertEquals(4, dao.countPending())
    }

    @Test
    fun nextQueued_returnsLowestQueuedId_orNull() = runBlocking {
        assertNull(dao.nextQueued())
        dao.insert(job(status = DownloadStatus.DOWNLOADING))
        dao.insert(job(status = DownloadStatus.COMPLETED))
        assertNull(dao.nextQueued())

        val firstQueued = dao.insert(job(url = "https://example.com/q1"))
        dao.insert(job(url = "https://example.com/q2"))
        assertEquals(firstQueued, dao.nextQueued()?.id)
    }

    @Test
    fun claimNext_claimsOldestQueued_inOrder_thenNull() = runBlocking {
        dao.insert(job(url = "https://example.com/busy", status = DownloadStatus.DOWNLOADING))
        val a = dao.insert(job(url = "https://example.com/a", title = "A", attempts = 2))
        val b = dao.insert(job(url = "https://example.com/b", title = "B"))

        val first = dao.claimNext()
        assertNotNull(first)
        assertEquals(a, first!!.id)
        assertEquals(DownloadStatus.DOWNLOADING, first.status)
        assertEquals("https://example.com/a", first.url)
        assertEquals("A", first.title)
        assertEquals(2, first.attempts)
        assertEquals(DownloadStatus.DOWNLOADING, byId(a).status)

        val second = dao.claimNext()
        assertEquals(b, second?.id)
        assertEquals(DownloadStatus.DOWNLOADING, second?.status)
        assertEquals("B", second?.title)

        assertNull(dao.claimNext())
        assertEquals(3, dao.countPending())
    }

    @Test
    fun claimNext_returnsNull_onEmptyTable() = runBlocking {
        assertNull(dao.claimNext())
    }

    @Test
    fun claimNext_concurrentConsumers_claimEachJobExactlyOnce() = runBlocking {
        val ids = (1..20).map { dao.insert(job(url = "https://example.com/c$it")) }

        val claimedPerWorker = (1..4).map {
            async(Dispatchers.IO) {
                val mine = mutableListOf<Long>()
                while (true) {
                    val claimed = dao.claimNext() ?: break
                    mine += claimed.id
                }
                mine
            }
        }.awaitAll()

        val all = claimedPerWorker.flatten()
        assertEquals("duplicates in $all", all.size, all.toSet().size)
        assertEquals(ids.toSet(), all.toSet())
        assertEquals(0, dao.snapshot().count { it.status == DownloadStatus.QUEUED })
    }
}
