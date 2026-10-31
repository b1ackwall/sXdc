package com.v1per.sxdc.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadJobDaoObserveTest {

    @get:Rule
    val dbRule = InMemoryDatabaseRule()

    private val dao get() = dbRule.dao

    private val timeoutMs = 10_000L

    @Test
    fun observeAll_ordersByCreatedAtDesc_thenIdDesc() = runBlocking {
        val old = dao.insert(job(url = "https://example.com/old", createdAt = 100L))
        val tieA = dao.insert(job(url = "https://example.com/tieA", createdAt = 200L))
        val tieB = dao.insert(job(url = "https://example.com/tieB", createdAt = 200L))
        val newest = dao.insert(job(url = "https://example.com/new", createdAt = 300L))

        val ids = dao.observeAll().first().map { it.id }
        assertEquals(listOf(newest, tieB, tieA, old), ids)
    }

    @Test
    fun observeAll_isCappedAt50_newestFirst() = runBlocking {
        val ids = (1..60).map { dao.insert(job(url = "https://example.com/$it", createdAt = it * 10L)) }

        val rows = dao.observeAll().first()
        assertEquals(50, rows.size)
        assertEquals(ids.takeLast(50).reversed(), rows.map { it.id })
        val oldestTen = ids.take(10).toSet()
        assertTrue(rows.none { it.id in oldestTen })
    }

    @Test
    fun observeActive_returnsOnlyQueuedAndDownloading_orderedById() = runBlocking {
        val expected = mutableListOf<Long>()
        listOf(
            DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED, DownloadStatus.QUEUED,
            DownloadStatus.FAILED, DownloadStatus.CANCELLED, DownloadStatus.QUEUED,
        ).forEachIndexed { i, status ->
            // Descending createdAt proves ordering is by id, not by time.
            val id = dao.insert(job(url = "https://example.com/$i", status = status, createdAt = 1_000L - i))
            if (status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING) expected += id
        }

        assertEquals(expected, dao.observeActive().first().map { it.id })
    }

    @Test
    fun observeActive_emitsWhenJobCompletes() = runBlocking {
        val keep = dao.insert(job(url = "https://example.com/keep"))
        val done = dao.insert(job(url = "https://example.com/done", status = DownloadStatus.DOWNLOADING))

        val updated = withTimeout(timeoutMs) {
            dao.observeActive().withIndex().first { (index, list) ->
                if (index == 0) {
                    assertEquals(listOf(keep, done), list.map { it.id })
                    dao.markCompleted(done, "Done", 5L)
                    false
                } else {
                    list.none { it.id == done }
                }
            }.value
        }
        assertEquals(listOf(keep), updated.map { it.id })
    }

    @Test
    fun observeAll_emitsWhenJobInserted() = runBlocking {
        val first = dao.insert(job(url = "https://example.com/first", createdAt = 1L))
        var inserted = -1L

        val updated = withTimeout(timeoutMs) {
            dao.observeAll().withIndex().first { (index, list) ->
                if (index == 0) {
                    assertEquals(listOf(first), list.map { it.id })
                    inserted = dao.insert(job(url = "https://example.com/second", createdAt = 2L))
                    false
                } else {
                    list.any { it.id == inserted }
                }
            }.value
        }
        assertEquals(listOf(inserted, first), updated.map { it.id })
    }
}
