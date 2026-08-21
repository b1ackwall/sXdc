package com.v1per.sxdc.download

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import com.chaquo.python.Python
import com.v1per.sxdc.R
import org.json.JSONObject
import java.io.File
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
                Log.e(TAG, "Unhandled failure for $url", t)
                Notifications.post(
                    this,
                    Notifications.ID_PROGRESS,
                    Notifications.failure(this, t.message ?: t.javaClass.simpleName),
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
                    Log.i(TAG, "Saved to ${result.path}")
                    Notifications.post(
                        this,
                        Notifications.ID_PROGRESS,
                        Notifications.success(
                            this,
                            result.title ?: url,
                            getString(R.string.notif_saved_to, result.path ?: pre.outputDir.path),
                        ),
                    )
                } else {
                    Log.e(TAG, "Download failed for $url: ${result.error}\n${result.trace ?: ""}")
                    Notifications.post(
                        this,
                        Notifications.ID_PROGRESS,
                        Notifications.failure(this, result.error ?: getString(R.string.error_unknown)),
                    )
                }
            }
        }
    }

    private data class JobResult(
        val ok: Boolean,
        val path: String?,
        val title: String?,
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
            error = json.optStringOrNull("error"),
            trace = json.optStringOrNull("trace"),
        )
    } catch (e: Exception) {
        JobResult(false, null, null, "${e.javaClass.simpleName}: ${e.message}", null)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

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
