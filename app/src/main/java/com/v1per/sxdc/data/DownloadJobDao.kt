package com.v1per.sxdc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The queue.
 *
 * An abstract class rather than an interface purely so [claimNext] and [recoverOrphans] can be
 * concrete `@Transaction` members -- Room wraps those in a real database transaction, which a
 * Kotlin interface's default method (compiled into a `DefaultImpls` holder) cannot be.
 *
 * Note the split between the two kinds of read here, which is the central rule of this design:
 * the **suspend one-shot** queries drive the service's work loop, and the **Flow** queries exist
 * only for the UI. Driving the loop from a Flow would be re-entrant -- the loop's own status
 * writes would re-trigger its own collector.
 */
@Dao
abstract class DownloadJobDao {

    @Insert
    abstract suspend fun insert(job: DownloadJob): Long

    /**
     * How many rows already hold [url] and are still going to be acted on.
     *
     * COMPLETED and FAILED are excluded on purpose: re-sharing something that finished, or
     * something that failed, is a retry and must be allowed. Only an *actively pending* duplicate
     * is worth rejecting.
     */
    @Query(
        "SELECT COUNT(*) FROM download_jobs " +
            "WHERE url = :url AND status IN ('QUEUED', 'DOWNLOADING')",
    )
    abstract suspend fun countPendingForUrl(url: String): Int

    /** Outstanding work, claimed or not. Drives both "N of M" and "is the service done". */
    @Query("SELECT COUNT(*) FROM download_jobs WHERE status IN ('QUEUED', 'DOWNLOADING')")
    abstract suspend fun countPending(): Int

    @Query("SELECT * FROM download_jobs WHERE status = 'QUEUED' ORDER BY id LIMIT 1")
    abstract suspend fun nextQueued(): DownloadJob?

    @Query("UPDATE download_jobs SET status = 'DOWNLOADING' WHERE id = :id")
    abstract suspend fun markDownloading(id: Long)

    /**
     * Take the oldest queued row and mark it claimed, atomically.
     *
     * There is only ever one consumer, so contention is not the reason for the transaction --
     * correctness under a future second consumer, and never leaving a row selected-but-unclaimed
     * across a crash, are.
     */
    @Transaction
    open suspend fun claimNext(): DownloadJob? {
        val job = nextQueued() ?: return null
        markDownloading(job.id)
        return job.copy(status = DownloadStatus.DOWNLOADING)
    }

    /** Rows left mid-flight by a killed process. Read before recovery, for their staged files. */
    @Query("SELECT * FROM download_jobs WHERE status = 'DOWNLOADING'")
    abstract suspend fun orphanedJobs(): List<DownloadJob>

    @Query(
        "UPDATE download_jobs " +
            "SET status = 'QUEUED', attempts = attempts + 1, stagedFilePath = NULL " +
            "WHERE status = 'DOWNLOADING' AND attempts < :maxAttempts",
    )
    abstract suspend fun requeueOrphans(maxAttempts: Int): Int

    @Query(
        "UPDATE download_jobs " +
            "SET status = 'FAILED', failureReason = :reason, completedAt = :now, " +
            "stagedFilePath = NULL " +
            "WHERE status = 'DOWNLOADING' AND attempts >= :maxAttempts",
    )
    abstract suspend fun failExhaustedOrphans(reason: String, now: Long, maxAttempts: Int): Int

    /**
     * Resolve every orphaned claim: retire the ones that have already burned their attempts, and
     * requeue the rest.
     *
     * Order is load-bearing. Requeuing increments `attempts`, so retiring has to be decided
     * against the pre-increment value or a row would get one attempt more than [maxAttempts]
     * allows. Both statements run in one transaction so no row can be seen half-recovered.
     */
    @Transaction
    open suspend fun recoverOrphans(
        reason: String,
        now: Long,
        maxAttempts: Int = MAX_ATTEMPTS,
    ) {
        failExhaustedOrphans(reason, now, maxAttempts)
        requeueOrphans(maxAttempts)
    }

    /** Records the staged file *before* the move, so a kill mid-move still leaves a path to clean. */
    @Query("UPDATE download_jobs SET title = :title, stagedFilePath = :stagedFilePath WHERE id = :id")
    abstract suspend fun markStaged(id: Long, title: String?, stagedFilePath: String?)

    @Query(
        "UPDATE download_jobs " +
            "SET status = 'COMPLETED', title = :title, stagedFilePath = NULL, " +
            "failureReason = NULL, completedAt = :completedAt " +
            "WHERE id = :id",
    )
    abstract suspend fun markCompleted(id: Long, title: String?, completedAt: Long)

    /**
     * `stagedFilePath` is intentionally left alone: the move path deliberately keeps a staged file
     * when saving into the SAF folder fails, so the bytes do not have to be fetched twice.
     */
    @Query(
        "UPDATE download_jobs " +
            "SET status = 'FAILED', failureReason = :reason, completedAt = :completedAt " +
            "WHERE id = :id",
    )
    abstract suspend fun markFailed(id: Long, reason: String, completedAt: Long)

    /** UI only. Newest first, for the history list. Capped so a huge playlist can't grow this unbounded. */
    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC, id DESC LIMIT 50")
    abstract fun observeAll(): Flow<List<DownloadJob>>

    /** UI only. Oldest first, i.e. the order the queue will actually work through them. */
    @Query("SELECT * FROM download_jobs WHERE status IN ('QUEUED', 'DOWNLOADING') ORDER BY id")
    abstract fun observeActive(): Flow<List<DownloadJob>>

    /** Settings' "Clear download history". Leaves QUEUED/DOWNLOADING rows untouched. */
    @Query("DELETE FROM download_jobs WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    abstract suspend fun clearHistory()

    /**
     * Emergency Stop. Only rows still pending are affected -- a job already DOWNLOADING runs to
     * its natural conclusion, per the locked design; this never interrupts yt-dlp mid-flight.
     */
    @Query(
        "UPDATE download_jobs SET status = 'CANCELLED', completedAt = :now " +
            "WHERE status = 'QUEUED'",
    )
    abstract suspend fun cancelAllPending(now: Long)

    companion object {
        /** A job that takes the process down with it gets three goes, then it is retired. */
        const val MAX_ATTEMPTS = 3
    }
}
