package com.v1per.sxdc.download

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import com.chaquo.python.Python
import com.v1per.sxdc.R
import com.v1per.sxdc.appScope
import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * dataSync foreground service that drains the Room-backed download queue, one job at a time.
 *
 * dataSync covers both halves of the job: Android documents the type as covering data
 * download *and* local file processing, which is exactly fetch-then-transcode.
 *
 * The queue itself lives in the database, not in this object. Nothing about which jobs exist or
 * how far they got is held in a field here, so a killed process loses only the claim on the job
 * that was mid-flight -- which DownloadQueue.recoverOrphans resolves on next launch.
 */
class DownloadService : Service() {

    /**
     * Coroutines rather than the single-thread Executor this used to run on: Room's DAO is
     * suspend, and the loop now has to interleave database reads with the blocking download.
     * A plain Service has no lifecycleScope -- that is an extension on LifecycleOwner -- so the
     * scope is built and cancelled by hand. SupervisorJob so one job's failure cannot take the
     * scope, and therefore the rest of the queue, down with it.
     */
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    /**
     * Guarantees a single consumer. Every onStartCommand launches a drain, and two rapid shares
     * would otherwise have two loops claiming rows at once.
     */
    private val processMutex = Mutex()

    private val main = Handler(Looper.getMainLooper())

    private val dao by lazy { DownloadQueue.dao(this) }

    /** Only ever touched under [processMutex]. */
    private var processedInRun = 0

    /** Read by onTimeout from the main thread while the loop writes it from an IO thread. */
    @Volatile
    private var currentJobId: Long? = null

    /**
     * Set when a failure applies to the whole queue rather than one URL -- no destination folder,
     * no working ffmpeg. Stops the drain with the remaining rows still QUEUED, so fixing the cause
     * in Settings and re-opening the app picks up where this left off. Cleared on every new start
     * command, since a fresh share is the user's signal that they believe it is fixed.
     */
    @Volatile
    private var fatalStop = false

    /**
     * Guards against a Handler callback outliving the service. Unlike the `stopping` flag this
     * replaces, it is set in onDestroy rather than at the point a stop is *decided*, so it can
     * never block a legitimate startForeground -- a destroyed Service instance is never reused.
     */
    @Volatile
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen before anything else: the system gives us a few seconds from
        // startForegroundService() to get here, and any earlier work risks that deadline.
        startForegroundNow(getString(R.string.notif_preparing))

        fatalStop = false
        val url = intent?.getStringExtra(EXTRA_URL)

        serviceScope.launch {
            // Insert first, and in the same coroutine that goes on to drain, so the row is
            // committed before any loop can look for it. This is why the URL travels in an extra
            // instead of being inserted by ShareReceiverActivity: an activity-side insert would
            // have to be asynchronous to keep off the main thread, and the service could then
            // start, find an empty queue and stop before the insert landed.
            if (!url.isNullOrBlank()) enqueue(url)
            processMutex.withLock { drain() }
            stopWhenIdle(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * Add a shared URL, unless the same one is already waiting or running.
     *
     * COMPLETED and FAILED rows deliberately do not count as duplicates -- re-sharing something
     * that finished, or something that failed, is how a retry is expressed.
     */
    private suspend fun enqueue(url: String) {
        val duplicate = dao.countPendingForUrl(url) > 0
        if (duplicate) {
            Log.i(TAG, "Already queued, skipping: $url")
        } else {
            val id = dao.insert(DownloadJob(url = url))
            Log.i(TAG, "Queued job $id for $url")
        }

        val message = getString(
            if (duplicate) R.string.toast_already_queued else R.string.toast_added_to_queue,
        )
        main.post { Toasts.showReplacing(this, message) }
    }

    /** Whether the queue should keep going after a job resolved. */
    private enum class Outcome { CONTINUE, STOP }

    private suspend fun drain() {
        while (!fatalStop) {
            val job = dao.claimNext() ?: break
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
                failJob(job, getString(R.string.error_unknown))
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
        val pending = dao.countPending()
        updateProgress(job.title ?: job.url, processedInRun + 1, processedInRun + pending)

        when (val pre = DownloadPreconditions.check(this)) {
            is DownloadPreconditions.Result.Failed -> {
                // Both preconditions -- destination folder and ffmpeg -- are queue-wide
                // conditions. Draining the rest would fail every remaining job with the identical
                // message and throw the work away, so stop instead and keep them queued.
                Log.e(TAG, "Preconditions failed: ${pre.reasons}")
                failJob(job, pre.reasons.joinToString(" "))
                return Outcome.STOP
            }

            is DownloadPreconditions.Result.Ok -> {
                val result = invokeYtDlp(job.url, pre.outputDir, pre.ffmpegBinDir)
                if (!result.ok) {
                    Log.e(TAG, "Download failed for ${job.url}: ${result.error}\n${result.trace ?: ""}")
                    failJob(job, messageFor(result.kind))
                    // A full staging volume is the one yt-dlp failure that is not about this URL:
                    // every remaining job would run out of room at the same point. Distinct from
                    // the destination volume filling during the move, which moveToDestination
                    // reports as queueWide -- same reasoning, different volume.
                    return if (result.kind == KIND_STORAGE) Outcome.STOP else Outcome.CONTINUE
                }

                Log.i(TAG, "Staged at ${result.path}")
                // Recorded before the move, not after: this is the only pointer a recovery pass
                // will have to the bytes on disk if the process dies during the copy.
                dao.markStaged(job.id, result.title, result.path)

                return when (val moved = moveToDestination(result, pre.destinationTreeUri)) {
                    is MoveResult.Moved -> {
                        dao.markCompleted(job.id, result.title ?: job.title, System.currentTimeMillis())
                        Notifications.post(
                            this,
                            Notifications.terminalId(job.id),
                            Notifications.success(
                                this,
                                result.title ?: job.url,
                                getString(R.string.notif_saved_to, moved.displayName),
                            ),
                        )
                        Notifications.postSummary(this)
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
        dao.markFailed(job.id, message, System.currentTimeMillis())
        Notifications.post(
            this,
            Notifications.terminalId(job.id),
            Notifications.failure(this, job.id, message),
        )
        Notifications.postSummary(this)
    }

    private data class JobResult(
        val ok: Boolean,
        val path: String?,
        val title: String?,
        /** Bytes yt-dlp saw on disk. The move verifies its copy against this before deleting. */
        val size: Long?,
        /** One of sxdc_download.py's KIND_* ids. Chooses the message; [error] is log-only. */
        val kind: String?,
        val error: String?,
        val trace: String?,
    )

    /**
     * Blocking, and deliberately left that way. Chaquopy's callAttr is a JNI call with no
     * interruption point, so wrapping it in anything cancellable would only give the illusion of
     * one -- cancelling the scope cannot stop yt-dlp mid-download. A job in flight runs to
     * completion; a job whose process is killed leaves its row DOWNLOADING for recovery.
     */
    private fun invokeYtDlp(url: String, outputDir: File, ffmpegBinDir: String): JobResult = try {
        val module = Python.getInstance().getModule("sxdc_download")
        val raw = module.callAttr(
            "download_audio",
            url,
            outputDir.absolutePath,
            ffmpegBinDir,
        ).toString()
        val json = JSONObject(raw)
        JobResult(
            ok = json.optBoolean("ok", false),
            path = json.optStringOrNull("path"),
            title = json.optStringOrNull("title"),
            size = if (json.isNull("size")) null else json.optLong("size").takeIf { it > 0 },
            kind = json.optStringOrNull("kind"),
            error = json.optStringOrNull("error"),
            trace = json.optStringOrNull("trace"),
        )
    } catch (e: Exception) {
        // The bridge itself failed -- Python never ran, or returned something unparseable.
        Log.e(TAG, "yt-dlp bridge call failed", e)
        JobResult(
            ok = false,
            path = null,
            title = null,
            size = null,
            kind = KIND_ENGINE,
            error = "${e.javaClass.simpleName}: ${e.message}",
            trace = null,
        )
    }

    /**
     * Turn a classifier id into something worth putting on a lock screen.
     *
     * Unknown or absent ids fall through to the generic line rather than to raw text: a message
     * the user cannot act on is worse than one that simply admits the failure.
     */
    private fun messageFor(kind: String?): String = getString(
        when (kind) {
            KIND_NETWORK -> R.string.error_network
            KIND_UNAVAILABLE -> R.string.error_unavailable
            KIND_UNSUPPORTED -> R.string.error_unsupported
            KIND_RESTRICTED -> R.string.error_restricted
            KIND_AUTH -> R.string.error_auth
            KIND_STORAGE -> R.string.error_storage
            KIND_MEDIA_TOOLS -> R.string.error_media_tools
            KIND_NO_OUTPUT -> R.string.error_no_output
            KIND_ENGINE -> R.string.error_engine
            else -> R.string.error_unknown
        },
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private sealed interface MoveResult {
        /** @param displayName the name the provider actually assigned, which may not be ours. */
        data class Moved(val displayName: String) : MoveResult

        /**
         * @param queueWide true when the cause is the destination itself rather than this file --
         *   the folder grant being gone, or the volume being full. Every remaining job would fail
         *   the same way, so the queue stops rather than turning one problem into N identical
         *   notifications.
         */
        data class Failed(val message: String, val queueWide: Boolean = false) : MoveResult
    }

    /**
     * Copy the staged file into the user's SAF folder, then drop the staged copy.
     *
     * A copy, not a rename: the destination is a `content://` document behind a provider, so
     * there is no path for File.renameTo() to target. The source is deleted only once the byte
     * count is confirmed, and a failure anywhere deletes the half-written destination document
     * while leaving the staged file untouched, so the download never has to be repeated.
     */
    private fun moveToDestination(result: JobResult, treeUri: Uri): MoveResult {
        val source = result.path?.let(::File)
        if (source == null || !source.isFile) {
            return MoveResult.Failed(getString(R.string.error_staged_file_missing))
        }

        val tree = DocumentFile.fromTreeUri(this, treeUri)
        if (tree == null || !tree.canWrite()) {
            // The same condition DownloadPreconditions gates on, noticed later. Queue-wide.
            return MoveResult.Failed(DownloadPreconditions.REASON_FOLDER_LOST, queueWide = true)
        }

        // "audio/flac" is the platform's canonical type for .flac, so the provider appends no
        // second extension. It may still rename to dodge a collision -- hence the readback below.
        val created = tree.createFile(MIME_FLAC, source.name)
            ?: return MoveResult.Failed(getString(R.string.error_create_document))

        val expected = result.size ?: source.length()
        val copied = try {
            contentResolver.openOutputStream(created.uri).use { out ->
                if (out == null) throw IOException("openOutputStream returned null")
                source.inputStream().use { input -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Copy into ${created.uri} failed", e)
            created.delete()
            // A full volume is a property of the destination, not of this file, so it stops the
            // queue for the same reason a lost folder grant does. Anything else here is specific
            // to this copy and leaves the remaining jobs worth attempting.
            return if (e.isOutOfSpace()) {
                MoveResult.Failed(getString(R.string.error_storage), queueWide = true)
            } else {
                MoveResult.Failed(getString(R.string.error_copy_failed))
            }
        }

        if (copied != expected) {
            // A short write with no exception is almost always the volume filling up mid-copy.
            Log.e(TAG, "Short copy into ${created.uri}: $copied of $expected bytes")
            created.delete()
            return MoveResult.Failed(getString(R.string.error_storage), queueWide = true)
        }

        // Only now is the staged copy expendable.
        val displayName = created.name ?: source.name
        StagingFiles.clear(source)
        Log.i(TAG, "Moved to ${created.uri} as $displayName ($copied bytes)")
        return MoveResult.Moved(displayName)
    }

    /**
     * Whether a copy failure was the volume running out of room.
     *
     * Walks the cause chain because the ENOSPC that matters is usually wrapped: a provider throws
     * IOException over an ErrnoException several frames down. Matching on text is unlovely, but
     * the framework surfaces no typed out-of-space signal through a `content://` write.
     */
    private fun Throwable.isOutOfSpace(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            val text = cause.message?.lowercase()
            if (text != null && OUT_OF_SPACE_MARKERS.any { it in text }) return true
            cause = cause.cause.takeIf { it != cause }
        }
        return false
    }

    private fun updateProgress(label: String, position: Int, total: Int) {
        main.post { startForegroundNow(label, position, total) }
    }

    // FOREGROUND_SERVICE_TYPE_DATA_SYNC is an int constant added in API 29, inlined by the
    // compiler, so there is no field lookup to fail on API 28. ServiceCompat drops the type
    // argument entirely below API 29, where typed foreground services do not exist.
    @SuppressLint("InlinedApi")
    private fun startForegroundNow(label: String, position: Int = 0, total: Int = 0) {
        if (destroyed) return
        ServiceCompat.startForeground(
            this,
            Notifications.ID_PROGRESS,
            Notifications.progress(this, label, position, total),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * Stop once the database says there is nothing left, and only if no newer start command is
     * outstanding.
     *
     * "Is there more work" is a query now, which is what retires the old AtomicInteger/@Volatile
     * pair -- and with it the window where a flag set at stop-decision time could block the
     * startForeground of a start command that arrived just afterwards.
     *
     * stopSelfResult, not stopSelf: a share landing while the last job finished delivers a newer
     * startId, and tearing down on the older one would drop it. The progress notification is
     * removed only on the branch that actually stops, so a service that stays alive never loses
     * the notification the system requires it to be showing.
     */
    private suspend fun stopWhenIdle(startId: Int) {
        if (!fatalStop && dao.countPending() > 0) return
        withContext(Dispatchers.Main) {
            if (stopSelfResult(startId)) {
                // STOP_FOREGROUND_REMOVE, not _DETACH: results now live on their own ids, so
                // there is nothing under ID_PROGRESS that needs to outlive the service.
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
        }
    }

    /**
     * dataSync services are capped at 6 hours per 24-hour period on API 35+. If the cap is ever
     * reached the system calls this and allows a few seconds before killing us with a
     * RemoteServiceException, so stop immediately.
     *
     * Only the job that was running is retired; everything still QUEUED stays queued for the next
     * launch. The write goes on [appScope] because the service's own scope is about to be
     * cancelled and would drop it.
     */
    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type=$fgsType); stopping.")
        fatalStop = true

        val jobId = currentJobId
        if (jobId != null) {
            val app = applicationContext
            val message = getString(R.string.error_timeout)
            appScope.launch {
                DownloadQueue.dao(app).markFailed(jobId, message, System.currentTimeMillis())
                Notifications.post(
                    app,
                    Notifications.terminalId(jobId),
                    Notifications.failure(app, jobId, message),
                )
                Notifications.postSummary(app)
            }
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val EXTRA_URL = "com.v1per.sxdc.extra.URL"
        private const val MIME_FLAC = "audio/flac"

        // Mirrors the KIND_* ids in sxdc_download.py.
        private const val KIND_NETWORK = "network"
        private const val KIND_UNAVAILABLE = "unavailable"
        private const val KIND_UNSUPPORTED = "unsupported"
        private const val KIND_RESTRICTED = "restricted"
        private const val KIND_AUTH = "auth"
        private const val KIND_STORAGE = "storage"
        private const val KIND_MEDIA_TOOLS = "media_tools"
        private const val KIND_NO_OUTPUT = "no_output"
        private const val KIND_ENGINE = "engine"

        private val OUT_OF_SPACE_MARKERS =
            listOf("enospc", "no space left", "disk full", "not enough space")

        /**
         * Must be called synchronously from a foreground activity. Apps targeting Android 12+
         * may only start a foreground service while visible; deferring this to a background
         * thread or to after the caller finishes risks ForegroundServiceStartNotAllowedException.
         */
        fun start(context: Context, url: String) {
            val intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_URL, url)
            context.startForegroundService(intent)
        }

        /**
         * Work the existing queue with nothing new to add -- the absence of [EXTRA_URL] is the
         * whole signal, so there is no sentinel to keep in sync.
         *
         * Same visibility rule as [start]: only call this from a resumed Activity.
         */
        fun resume(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }
    }
}
