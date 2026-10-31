package com.v1per.sxdc.download

import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.DownloadJobDao

/** The slice of [DownloadJobDao] the queue engine uses. */
internal interface JobStore {
    suspend fun insert(job: DownloadJob): Long
    suspend fun countPendingForUrl(url: String): Int
    suspend fun countPending(): Int
    suspend fun claimNext(): DownloadJob?
    suspend fun markStaged(id: Long, title: String?, stagedFilePath: String?)
    suspend fun markCompleted(id: Long, title: String?, completedAt: Long)
    suspend fun markFailed(id: Long, reason: String, completedAt: Long)
    suspend fun orphanedJobs(): List<DownloadJob>
    suspend fun recoverOrphans(reason: String, now: Long)
}

/** One-to-one delegation to the Room DAO. */
internal class DaoJobStore(private val dao: DownloadJobDao) : JobStore {
    override suspend fun insert(job: DownloadJob): Long = dao.insert(job)
    override suspend fun countPendingForUrl(url: String): Int = dao.countPendingForUrl(url)
    override suspend fun countPending(): Int = dao.countPending()
    override suspend fun claimNext(): DownloadJob? = dao.claimNext()
    override suspend fun markStaged(id: Long, title: String?, stagedFilePath: String?) =
        dao.markStaged(id, title, stagedFilePath)
    override suspend fun markCompleted(id: Long, title: String?, completedAt: Long) =
        dao.markCompleted(id, title, completedAt)
    override suspend fun markFailed(id: Long, reason: String, completedAt: Long) =
        dao.markFailed(id, reason, completedAt)
    override suspend fun orphanedJobs(): List<DownloadJob> = dao.orphanedJobs()
    override suspend fun recoverOrphans(reason: String, now: Long) =
        dao.recoverOrphans(reason = reason, now = now)
}
