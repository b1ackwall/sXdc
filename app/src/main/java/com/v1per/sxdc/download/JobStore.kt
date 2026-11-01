package com.v1per.sxdc.download

import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.DownloadJobDao

/** The slice of [DownloadJobDao] the queue engine uses. */
internal interface JobStore {
    suspend fun insert(job: DownloadJob): Long
    suspend fun pendingUrls(): List<String>
    suspend fun countPending(): Int
    suspend fun claimNext(): DownloadJob?

    /** The three marks below only write a DOWNLOADING row and return the rows affected (0 or 1). */
    suspend fun markStaged(id: Long, title: String?, stagedFilePath: String?): Int
    suspend fun markCompleted(id: Long, title: String?, completedAt: Long): Int
    suspend fun markFailed(id: Long, reason: String, completedAt: Long): Int
    suspend fun orphanedJobs(): List<DownloadJob>
    suspend fun recoverOrphans(reason: String, now: Long)
}

/** One-to-one delegation to the Room DAO. */
internal class DaoJobStore(private val dao: DownloadJobDao) : JobStore {
    override suspend fun insert(job: DownloadJob): Long = dao.insert(job)
    override suspend fun pendingUrls(): List<String> = dao.pendingUrls()
    override suspend fun countPending(): Int = dao.countPending()
    override suspend fun claimNext(): DownloadJob? = dao.claimNext()
    override suspend fun markStaged(id: Long, title: String?, stagedFilePath: String?): Int =
        dao.markStaged(id, title, stagedFilePath)
    override suspend fun markCompleted(id: Long, title: String?, completedAt: Long): Int =
        dao.markCompleted(id, title, completedAt)
    override suspend fun markFailed(id: Long, reason: String, completedAt: Long): Int =
        dao.markFailed(id, reason, completedAt)
    override suspend fun orphanedJobs(): List<DownloadJob> = dao.orphanedJobs()
    override suspend fun recoverOrphans(reason: String, now: Long) =
        dao.recoverOrphans(reason = reason, now = now)
}
