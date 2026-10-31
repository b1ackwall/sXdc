package com.v1per.sxdc.download

import android.util.Log
import com.v1per.sxdc.R
import com.v1per.sxdc.data.DownloadJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The download queue engine: enqueue a shared URL, then drain the queue one job at a time.
 *
 * Owns no threads and names no dispatcher -- the caller (DownloadService) decides where it runs.
 * One instance per Service instance, so [processedInRun] lives exactly as long as it always did.
 */
internal class QueueProcessor(
    private val store: JobStore,
    private val bridge: DownloadBridge,
    private val mover: FileMover,
    private val preconditions: PreconditionChecker,
    private val notifier: QueueNotifier,
    private val strings: StringProvider,
    private val clock: () -> Long,
    private val stagingCleaner: StagingCleaner,
) {

    /**
     * Guarantees a single consumer. Every onStartCommand launches a drain, and two rapid shares
     * would otherwise have two loops claiming rows at once.
     */
    private val processMutex = Mutex()

    /** Only ever touched under [processMutex]. */
    private var processedInRun = 0

    /** Read by onTimeout from the main thread while the loop writes it from an IO thread. */
    @Volatile
    var currentJobId: Long? = null
        private set

    /**
     * Set when a failure applies to the whole queue rather than one URL -- no destination folder,
     * no working ffmpeg. Stops the drain with the remaining rows still QUEUED, so fixing the cause
     * in Settings and re-opening the app picks up where this left off. Cleared on every new start
     * command, since a fresh share is the user's signal that they believe it is fixed.
     */
    @Volatile
    var fatalStop = false

    suspend fun process(url: String?) {
        // Expansion, the enqueue loop and drain() all happen under one lock now, not just
        // drain() -- two rapid shares (playlist or single) must never expand/enqueue
        // concurrently, since expansion is itself a blocking network call same as a download.
        processMutex.withLock {
            // First, under the lock: this processor is the only thing that copies into the
            // destination, so no move can be in flight while its leftovers are swept.
            sweepPartials()
            if (!url.isNullOrBlank()) expandAndEnqueue(url)
            drain()
            // Still under the lock, so no download is writing into staging while it is swept.
            // Skipped if drain() throws: a cancelled run leaves its row DOWNLOADING for recovery.
            stagingCleaner.sweep()
        }
    }

    /** The mover promises never to throw; a run still must not depend on that promise. */
    private fun sweepPartials() {
        try {
            mover.sweepPartials()
        } catch (e: Exception) {
            Log.w(TAG, "Partial sweep failed", e)
        }
    }

    /** The stop condition of DownloadService.stopWhenIdle: a fatal stop, or nothing pending. */
    suspend fun shouldStop(): Boolean = fatalStop || store.countPending() == 0

    /** Retire one job from outside the drain loop (the service's onTimeout). */
    suspend fun failJobById(jobId: Long, message: String) {
        store.markFailed(jobId, message, clock())
        notifier.failure(jobId, message)
    }

    /**
     * Resolve [url] to one or more tracks (a plain video stays one; a playlist becomes N), queue
     * whichever of them are not already pending, and post exactly one toast summarising the
     * outcome -- never one toast per track, which would just spam and cancel itself into oblivion
     * on a real playlist.
     */
    private suspend fun expandAndEnqueue(url: String) {
        val tracks = bridge.expandUrls(url)

        var queued = 0
        var duplicate = 0
        for (track in tracks) {
            if (store.countPendingForUrl(track.url) > 0) {
                Log.i(TAG, "Already queued, skipping: ${track.url}")
                duplicate++
            } else {
                // createdAt keeps DownloadJob's own default, exactly as before the extraction.
                val id = store.insert(DownloadJob(url = track.url, title = track.title))
                Log.i(TAG, "Queued job $id for ${track.url}")
                queued++
            }
        }

        val message = if (tracks.size <= 1) {
            strings.get(if (duplicate > 0) R.string.toast_already_queued else R.string.toast_added_to_queue)
        } else if (duplicate > 0) {
            strings.get(R.string.toast_queued_playlist_with_duplicates, queued, duplicate)
        } else {
            strings.get(R.string.toast_queued_playlist, queued)
        }
        notifier.toast(message)
    }

    /** Whether the queue should keep going after a job resolved. */
    private enum class Outcome { CONTINUE, STOP }

    private suspend fun drain() {
        while (!fatalStop) {
            val job = store.claimNext() ?: break
            currentJobId = job.id

            val outcome = try {
                runJob(job)
            } catch (c: CancellationException) {
                // The scope is going away; leave the row DOWNLOADING for recovery to resolve.
                throw c
            } catch (t: Throwable) {
                // A crash here must still resolve the job and the notification. The throwable goes
                // to the log; the user gets the generic line, because an unhandled failure by
                // definition has no message worth showing them.
                Log.e(TAG, "Unhandled failure for ${job.url}", t)
                failJob(job, strings.get(R.string.error_unknown))
                Outcome.CONTINUE
            } finally {
                currentJobId = null
            }

            processedInRun++
            if (outcome == Outcome.STOP) {
                fatalStop = true
                break
            }
        }
    }

    private suspend fun runJob(job: DownloadJob): Outcome {
        // countPending includes the row just claimed, so `processed + pending` is the queue's
        // total as it stands right now -- and it grows correctly if a share arrives mid-drain.
        val pending = store.countPending()
        notifier.progress(job.title ?: job.url, processedInRun + 1, processedInRun + pending)

        when (val pre = preconditions.check()) {
            is PreconditionResult.Failed -> {
                // Both preconditions -- destination folder and ffmpeg -- are queue-wide
                // conditions. Draining the rest would fail every remaining job with the identical
                // message and throw the work away, so stop instead and keep them queued.
                Log.e(TAG, "Preconditions failed: ${pre.reasons}")
                failJob(job, pre.reasons.joinToString(" "))
                return Outcome.STOP
            }

            is PreconditionResult.Ok -> {
                val result = bridge.download(job.url, pre.outputDir, pre.ffmpegBinDir)
                if (!result.ok) {
                    Log.e(TAG, "Download failed for ${job.url}: ${result.error}\n${result.trace ?: ""}")
                    failJob(job, ErrorMessageMapper.messageFor(result.kind, strings))
                    // A full staging volume is the one yt-dlp failure that is not about this URL:
                    // every remaining job would run out of room at the same point. Distinct from
                    // the destination volume filling during the move, which the mover reports as
                    // queueWide -- same reasoning, different volume.
                    return if (result.kind == ErrorKinds.STORAGE) Outcome.STOP else Outcome.CONTINUE
                }

                Log.i(TAG, "Staged at ${result.path}")
                // Recorded before the move, not after: this is the only pointer a recovery pass
                // will have to the bytes on disk if the process dies during the copy.
                store.markStaged(job.id, result.title, result.path)

                return when (val moved = mover.move(result, pre.destination)) {
                    is MoveResult.Moved -> {
                        store.markCompleted(job.id, result.title ?: job.title, clock())
                        notifier.success(
                            job.id,
                            result.title ?: job.url,
                            strings.get(R.string.notif_saved_to, moved.displayName),
                        )
                        Outcome.CONTINUE
                    }

                    is MoveResult.Failed -> {
                        Log.e(TAG, "Move failed for ${job.url}: ${moved.message}")
                        failJob(job, moved.message)
                        if (moved.queueWide) Outcome.STOP else Outcome.CONTINUE
                    }
                }
            }
        }
    }

    private suspend fun failJob(job: DownloadJob, message: String) {
        store.markFailed(job.id, message, clock())
        notifier.failure(job.id, message)
    }

    private companion object {
        /** Same tag as before the extraction, so log lines read exactly as they did. */
        const val TAG = "DownloadService"
    }
}
