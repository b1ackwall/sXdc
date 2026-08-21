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
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * dataSync foreground service that runs yt-dlp + ffmpeg for one shared URL.
 *
 * dataSync covers both halves of the job: Android documents the type as covering data
 * download *and* local file processing, which is exactly fetch-then-transcode.
 */
class DownloadService : Service() {

    /**
     * Single worker thread, so two rapid shares queue instead of running yt-dlp and ffmpeg
     * concurrently on a phone. Deliberately an Executor rather than coroutines: the job is one
     * sequential blocking call, and this keeps the service free of any dependency that is only
     * present transitively.
     */
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val outstanding = AtomicInteger(0)

    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen before anything else: the system gives us a few seconds from
        // startForegroundService() to get here, and any earlier work risks that deadline.
        startForegroundNow(getString(R.string.notif_preparing))

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "Started with no URL; stopping.")
            if (outstanding.get() == 0) shutdown()
            return START_NOT_STICKY
        }

        outstanding.incrementAndGet()
        worker.execute {
            try {
                runJob(url)
            } catch (t: Throwable) {
                // A crash on the worker must still resolve the notification and free the service.
                // The throwable goes to the log; the user gets the generic line, because an
                // unhandled failure by definition has no message worth showing them.
                Log.e(TAG, "Unhandled failure for $url", t)
                Notifications.post(
                    this,
                    Notifications.ID_PROGRESS,
                    Notifications.failure(this, getString(R.string.error_unknown)),
                )
            } finally {
                if (outstanding.decrementAndGet() == 0) main.post { shutdown() }
            }
        }
        return START_NOT_STICKY
    }

    private fun runJob(url: String) {
        main.post { startForegroundNow(url) }

        when (val pre = DownloadPreconditions.check(this)) {
            is DownloadPreconditions.Result.Failed -> {
                Notifications.post(
                    this,
                    Notifications.ID_PROGRESS,
                    Notifications.failure(this, pre.reasons.joinToString(" ")),
                )
            }

            is DownloadPreconditions.Result.Ok -> {
                val result = invokeYtDlp(url, pre.outputDir, pre.ffmpegBinDir)
                if (result.ok) {
                    Log.i(TAG, "Staged at ${result.path}")
                    when (val moved = moveToDestination(result, pre.destinationTreeUri)) {
                        is MoveResult.Moved -> Notifications.post(
                            this,
                            Notifications.ID_PROGRESS,
                            Notifications.success(
                                this,
                                result.title ?: url,
                                getString(R.string.notif_saved_to, moved.displayName),
                            ),
                        )

                        is MoveResult.Failed -> {
                            Log.e(TAG, "Move failed for $url: ${moved.message}")
                            Notifications.post(
                                this,
                                Notifications.ID_PROGRESS,
                                Notifications.failure(this, moved.message),
                            )
                        }
                    }
                } else {
                    Log.e(TAG, "Download failed for $url: ${result.error}\n${result.trace ?: ""}")
                    Notifications.post(
                        this,
                        Notifications.ID_PROGRESS,
                        Notifications.failure(this, messageFor(result.kind)),
                    )
                }
            }
        }
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
        data class Failed(val message: String) : MoveResult
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
            return MoveResult.Failed(DownloadPreconditions.REASON_FOLDER_LOST)
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
            val message =
                if (e.isOutOfSpace()) getString(R.string.error_storage)
                else getString(R.string.error_copy_failed)
            return MoveResult.Failed(message)
        }

        if (copied != expected) {
            // A short write with no exception is almost always the volume filling up mid-copy.
            Log.e(TAG, "Short copy into ${created.uri}: $copied of $expected bytes")
            created.delete()
            return MoveResult.Failed(getString(R.string.error_storage))
        }

        // Only now is the staged copy expendable.
        val displayName = created.name ?: source.name
        clearStaging(source)
        Log.i(TAG, "Moved to ${created.uri} as $displayName ($copied bytes)")
        return MoveResult.Moved(displayName)
    }

    /**
     * Remove the file just moved plus the thumbnail sidecar yt-dlp may have left beside it.
     *
     * Named files only, never a directory sweep: `paths.temp` and `paths.home` are the same
     * staging directory, so a glob here could delete another job's in-flight download.
     */
    private fun clearStaging(moved: File) {
        if (!moved.delete()) Log.w(TAG, "Could not delete staged file ${moved.path}")

        val stem = moved.name.substringBeforeLast('.')
        for (ext in THUMBNAIL_EXTENSIONS) {
            val sidecar = File(moved.parentFile, "$stem.$ext")
            if (sidecar.isFile && !sidecar.delete()) {
                Log.w(TAG, "Could not delete thumbnail sidecar ${sidecar.path}")
            }
        }
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

    // FOREGROUND_SERVICE_TYPE_DATA_SYNC is an int constant added in API 29, inlined by the
    // compiler, so there is no field lookup to fail on API 28. ServiceCompat drops the type
    // argument entirely below API 29, where typed foreground services do not exist.
    @SuppressLint("InlinedApi")
    private fun startForegroundNow(label: String) {
        if (stopping) return
        ServiceCompat.startForeground(
            this,
            Notifications.ID_PROGRESS,
            Notifications.progress(this, label),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * STOP_FOREGROUND_DETACH, not _REMOVE: the terminal success/failure notification was posted
     * to the same id and must outlive the service rather than being torn down with it.
     */
    private fun shutdown() {
        stopping = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    /**
     * dataSync services are capped at 6 hours per 24-hour period on API 35+. If the cap is ever
     * reached the system calls this and allows a few seconds before killing us with a
     * RemoteServiceException, so stop immediately.
     */
    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type=$fgsType); stopping.")
        Notifications.post(
            this,
            Notifications.ID_PROGRESS,
            Notifications.failure(this, getString(R.string.error_timeout)),
        )
        shutdown()
    }

    override fun onDestroy() {
        worker.shutdown()
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

        /** What EmbedThumbnail may leave behind if it fails before deleting its own sidecar. */
        private val THUMBNAIL_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

        /**
         * Must be called synchronously from a foreground activity. Apps targeting Android 12+
         * may only start a foreground service while visible; deferring this to a background
         * thread or to after the caller finishes risks ForegroundServiceStartNotAllowedException.
         */
        fun start(context: Context, url: String) {
            val intent = Intent(context, DownloadService::class.java).putExtra(EXTRA_URL, url)
            context.startForegroundService(intent)
        }
    }
}
