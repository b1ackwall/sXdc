package com.v1per.sxdc.download

import android.util.Log
import com.v1per.sxdc.R
import com.v1per.sxdc.data.DownloadJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The download queue engine: enqueue a shared URL, then drain the queue one job at a time.
 *
 * Owns no threads and names no dispatcher -- the caller (DownloadService) decides where it runs.
 * One instance per Service instance, so [processedInRun] lives exactly as long as it always did.
 * The lock it runs under is not per instance: see [runLock].
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
    /** Suspends until startup recovery is over; see [StartupRecovery]. */
    private val awaitRecovery: suspend () -> Unit,
    /** The persisted halt; the source of truth for whether an auto-start may claim rows. */
    private val haltRecorder: QueueHaltRecorder,
    /**
     * Guarantees a single consumer across the whole process: [QueueRunLock.mutex] in production.
     * Every onStartCommand launches a run, and a service instance that timed out can still be
     * inside its blocking download when the next instance starts (#43). Required, so a test
     * always says whether its processors share one.
     */
    private val runLock: Mutex,
    /** Where the in-flight call's abort signal is published, for Emergency Stop: [QueueRunLock.active]. */
    private val activeRun: ActiveRun,
    private val clearStaged: (File) -> Unit = StagingFiles::clear,
) {
    /** Only ever touched under [runLock]. */
    private var processedInRun = 0

    /** Read by onTimeout from the main thread while the loop writes it from an IO thread. */
    @Volatile
    var currentJobId: Long? = null
        private set

    /**
     * The job the service's onTimeout retired while its blocking download was still running.
     * The download only stops cooperatively (#60), so when it returns late it consults this before
     * touching the database or the destination. Cleared when the next job is claimed.
     */
    @Volatile
    var abortedJobId: Long? = null
        private set

    /** Called synchronously from onTimeout, before the async failJobById that owns the FAILED row. */
    fun markAborted(jobId: Long) {
        abortedJobId = jobId
    }

    /** The claimed job and its download call's abort signal, published as one. */
    private class InFlight(val jobId: Long, val abort: AbortSignal)

    @Volatile
    private var inFlight: InFlight? = null

    /**
     * Asks [jobId]'s download call to stop, if it is still the one in flight (#60). onTimeout calls
     * this off the main thread: the request sets a Python event, which takes the interpreter lock.
     */
    fun requestAbort(jobId: Long) {
        inFlight?.takeIf { it.jobId == jobId }?.abort?.request()
    }

    /**
     * Ends this instance's drain: set by a fatal stop (which also records a persisted halt, see
     * [haltRecorder]) and by the service's onTimeout (which does not). Cleared on every new start
     * command; whether a later start may claim rows again is the persisted halt's decision.
     */
    @Volatile
    var fatalStop = false

    /**
     * @param reason who asked; a share defaults to [StartReason.NEW_SHARE], anything else to
     *   [StartReason.RESUME_SCREEN]. The service passes [StartReason.USER_RESUME] for the button.
     */
    suspend fun process(
        url: String?,
        reason: StartReason = if (url.isNullOrBlank()) StartReason.RESUME_SCREEN else StartReason.NEW_SHARE,
    ) {
        // Expansion, the enqueue loop and drain() all happen under one lock now, not just
        // drain() -- two rapid shares (playlist or single) must never expand/enqueue
        // concurrently, since expansion is itself a blocking network call same as a download.
        // The lock is process-wide, so a stale instance's run, including both sweeps, is over
        // before a newer instance's run starts.
        runLock.withLock {
            // First, under the lock: this processor is the only thing that copies into the
            // destination, so no move can be in flight while its leftovers are swept.
            sweepPartials()
            if (!url.isNullOrBlank()) expandAndEnqueue(url)
            // Under the lock, so a halt recorded by the run just before this one is seen here.
            if (haltAllowsDrain(reason)) {
                // Recovery only touches DOWNLOADING rows, so expansion need not wait for it; the
                // first claim must, or recovery could requeue or fail the job it just started.
                awaitRecovery()
                drain()
            }
            // Still under the lock, so no download is writing into staging while it is swept.
            // Skipped if drain() throws: a cancelled run leaves its row DOWNLOADING for recovery.
            stagingCleaner.sweep()
        }
    }

    /**
     * Defence in depth behind MainActivity's own check: a halted queue is not claimed from on an
     * auto-start unless [QueueHaltPolicy] says the halt has lapsed. Nothing is read beyond the
     * halt itself while none is set.
     */
    private suspend fun haltAllowsDrain(reason: StartReason): Boolean {
        val halt = haltRecorder.current() ?: return true
        val decision = QueueHaltPolicy.decide(halt, clock(), store.countPending(), reason) {
            preconditions.check() is PreconditionResult.Ok
        }
        if (decision.clearHalt) {
            Log.i(TAG, "Clearing the ${halt.kind} halt ($reason)")
            haltRecorder.clear()
        }
        if (!decision.start) {
            Log.i(TAG, "Queue halted (${halt.kind}); not claiming on $reason")
            // Rows are still pending, so without this stopWhenIdle would keep the service up.
            fatalStop = true
        }
        return decision.start
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
        if (store.markFailed(jobId, message, clock()) == 0) {
            Log.i(TAG, "Job $jobId is no longer DOWNLOADING; timeout failure not recorded")
            return
        }
        // A timeout is not a halt: nothing is said about waiting links.
        notifier.failure(jobId, message, waiting = 0)
    }

    /**
     * Resolve [url] to one or more tracks (a plain video stays one; a playlist becomes N), queue
     * whichever of them are not already pending, and post exactly one toast summarising the
     * outcome -- never one toast per track, which would just spam and cancel itself into oblivion
     * on a real playlist.
     */
    private suspend fun expandAndEnqueue(url: String) {
        val tracks = bridge.expandUrls(url)

        // One batch for the pending rows and the new tracks together. A key seen in the pending
        // rows, or added by an earlier track of this same expansion, marks a duplicate.
        val pending = store.pendingUrls()
        val keys = dedupKeys(pending + tracks.map { it.url })
        val seen = keys.take(pending.size).toMutableSet()

        var queued = 0
        var duplicate = 0
        tracks.forEachIndexed { i, track ->
            if (!seen.add(keys[pending.size + i])) {
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

    /**
     * The bridge's keys for [urls], or [urls] themselves (exact-string matching, the old rule) if
     * it throws or answers with the wrong number of keys. A duplicate check must never lose a share.
     */
    @Suppress("TooGenericExceptionCaught") // whatever the bridge throws, the share must not be lost
    private fun dedupKeys(urls: List<String>): List<String> = try {
        bridge.dedupKeys(urls).takeIf { it.size == urls.size } ?: urls
    } catch (e: Exception) {
        Log.w(TAG, "Dedup keys unavailable (${e.javaClass.simpleName}); comparing exact URLs")
        urls
    }

    /** Whether the queue should keep going after a job resolved, and if not, why. */
    private sealed interface Outcome {
        data object Continue : Outcome

        data class Stop(val kind: HaltKind) : Outcome
    }

    private suspend fun drain() {
        while (!fatalStop) {
            val job = store.claimNext() ?: break
            // Before currentJobId is published, so an onTimeout that sees this job's id cannot
            // have its abort flag wiped by this line, and finds this job's abort signal.
            abortedJobId = null
            val abort = AbortSignal()
            inFlight = InFlight(job.id, abort)
            activeRun.publish(abort)
            currentJobId = job.id

            val outcome = try {
                runJob(job, abort)
            } catch (c: CancellationException) {
                // The scope is going away; leave the row DOWNLOADING for recovery to resolve.
                throw c
            } catch (t: Throwable) {
                // A crash here must still resolve the job and the notification. The throwable goes
                // to the log; the user gets the generic line, because an unhandled failure by
                // definition has no message worth showing them.
                Log.e(TAG, "Unhandled failure for ${job.url}", t)
                failJob(job, strings.get(R.string.error_unknown))
                Outcome.Continue
            } finally {
                currentJobId = null
                activeRun.withdraw(abort)
                inFlight = null
            }

            processedInRun++
            if (outcome is Outcome.Stop) {
                fatalStop = true
                // Persisted, so the next onResume does not undo the stop (#70).
                haltRecorder.halt(outcome.kind, clock())
                break
            }
        }
    }

    private suspend fun runJob(job: DownloadJob, abort: AbortSignal): Outcome {
        // countPending includes the row just claimed, so `processed + pending` is the queue's
        // total as it stands right now -- and it grows correctly if a share arrives mid-drain.
        val pending = store.countPending()
        notifier.progress(job.title ?: job.url, processedInRun + 1, processedInRun + pending)

        return when (val pre = preconditions.check()) {
            is PreconditionResult.Failed -> {
                // Both preconditions -- destination folder and ffmpeg -- are queue-wide
                // conditions. Draining the rest would fail every remaining job with the identical
                // message and throw the work away, so stop instead and keep them queued.
                Log.e(TAG, "Preconditions failed: ${pre.reasons}")
                failJob(job, pre.reasons.joinToString(" "), stopping = true)
                Outcome.Stop(HaltKind.SETUP)
            }

            is PreconditionResult.Ok -> {
                download(job, pre, abort)
            }
        }
    }

    /** The blocking yt-dlp call, then whatever its result calls for. */
    private suspend fun download(job: DownloadJob, pre: PreconditionResult.Ok, abort: AbortSignal): Outcome {
        val result = bridge.download(job.url, pre.outputDir, pre.ffmpegBinDir, abort)
        return when {
            abortedJobId == job.id -> {
                // onTimeout already retired this row and told the user; a late return must
                // not write the row, move the file or notify. fatalStop ends the drain.
                // Continue, never Stop: a timeout is not a halt and must not be persisted.
                Log.w(TAG, "Job ${job.id} returned after the timeout; discarding the result")
                discardStaged(result.path)
                Outcome.Continue
            }

            !result.ok -> {
                failDownload(job, result)
            }

            else -> {
                deliver(job, result, pre.destination, abort)
            }
        }
    }

    private suspend fun failDownload(job: DownloadJob, result: JobResult): Outcome {
        Log.e(TAG, "Download failed for ${job.url}: ${result.error}\n${result.trace ?: ""}")
        // A full staging volume and a site that is rate-limiting us are the yt-dlp failures that
        // are not about this URL: every remaining job would fail at the same point. Distinct from
        // the destination volume filling during the move, which the mover reports with a halt kind --
        // same reasoning, different volume. An abort is neither (no halt), and on a row Emergency
        // Stop already cancelled the guarded markFailed writes and announces nothing.
        val stop = haltKindFor(result.kind)
        failJob(job, ErrorMessageMapper.messageFor(result.kind, strings), stopping = stop != null)
        return if (stop != null) Outcome.Stop(stop) else Outcome.Continue
    }

    /**
     * Records the staged file, then moves it. The abort is checked once more just before the move
     * (Emergency Stop, #60). A move already under way cannot be stopped: the file is delivered,
     * and the guarded markCompleted, finding the row CANCELLED, announces no success.
     */
    private suspend fun deliver(
        job: DownloadJob,
        result: JobResult,
        destination: String,
        abort: AbortSignal,
    ): Outcome {
        Log.i(TAG, "Staged at ${result.path}")
        // Recorded before the move, not after: this is the only pointer a recovery pass will have
        // to the bytes on disk if the process dies during the copy.
        val live = store.markStaged(job.id, result.title, result.path) != 0
        // The move is blocking and cannot be cancelled; a cancelled scope stops here.
        if (live) currentCoroutineContext().ensureActive()
        return when {
            !live -> {
                Log.w(TAG, "Job ${job.id} is no longer DOWNLOADING; discarding ${result.path}")
                discardStaged(result.path)
                Outcome.Continue
            }

            abort.requested -> {
                Log.w(TAG, "Job ${job.id} was cancelled before the move; discarding ${result.path}")
                discardStaged(result.path)
                // A no-op when the requester already retired the row, which Emergency Stop does.
                failJob(job, strings.get(R.string.error_aborted))
                Outcome.Continue
            }

            else -> {
                move(job, result, destination)
            }
        }
    }

    /**
     * The result's warnings (#45) are used here and nowhere else: only a delivered file has a
     * saved-without note, and only a row markCompleted actually wrote announces it.
     */
    private suspend fun move(job: DownloadJob, result: JobResult, destination: String): Outcome =
        when (val moved = mover.move(result, destination)) {
            is MoveResult.Moved -> {
                val note = WarningMessageMapper.noteFor(result.warnings, strings)
                if (store.markCompleted(job.id, result.title ?: job.title, note, clock()) == 0) {
                    // Already delivered; the row was retired meanwhile (a timeout or Emergency
                    // Stop during the move). Leave the file be.
                    Log.w(TAG, "Job ${job.id} was delivered but is no longer DOWNLOADING")
                } else {
                    notifier.success(
                        job.id,
                        result.title ?: job.url,
                        strings.get(R.string.notif_saved_to, moved.displayName),
                        note,
                    )
                }
                Outcome.Continue
            }

            is MoveResult.Failed -> {
                Log.e(TAG, "Move failed for ${job.url}: ${moved.message}")
                failJob(job, moved.message, stopping = moved.queueWide)
                moved.halt?.let { Outcome.Stop(it) } ?: Outcome.Continue
            }
        }

    /**
     * The yt-dlp failure kinds that halt the whole queue; null for one that only fails this job.
     * ABORTED is deliberately null: an abort is the app's own request, never a halt (#60).
     */
    private fun haltKindFor(kind: String?): HaltKind? = when (kind) {
        ErrorKinds.STORAGE -> HaltKind.STORAGE
        ErrorKinds.RATE_LIMITED -> HaltKind.RATE_LIMITED
        else -> null
    }

    /**
     * @param stopping this failure is a fatal stop; the notification then also says how many links
     *   are left waiting. The count goes to the notifier on its own and never into [message], so a
     *   stale number can never reach the row's failureReason or the history list.
     */
    private suspend fun failJob(job: DownloadJob, message: String, stopping: Boolean = false) {
        if (store.markFailed(job.id, message, clock()) == 0) {
            Log.i(TAG, "Job ${job.id} is no longer DOWNLOADING; failure not recorded")
            return
        }
        // After markFailed, so this job is no longer counted: what is left is what waits.
        val waiting = if (stopping) store.countPending() else 0
        notifier.failure(job.id, message, waiting)
    }

    private fun discardStaged(path: String?) {
        path?.let { clearStaged(File(it)) }
    }

    private companion object {
        /** Same tag as before the extraction, so log lines read exactly as they did. */
        const val TAG = "DownloadService"
    }
}
