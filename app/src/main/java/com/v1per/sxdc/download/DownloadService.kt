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
import com.v1per.sxdc.R
import com.v1per.sxdc.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * dataSync foreground service that drains the Room-backed download queue, one job at a time.
 *
 * dataSync covers both halves of the job: Android documents the type as covering data
 * download *and* local file processing, which is exactly fetch-then-transcode.
 *
 * The queue itself lives in the database, not in this object. Nothing about which jobs exist or
 * how far they got is held in a field here, so a killed process loses only the claim on the job
 * that was mid-flight -- which DownloadQueue.recoverOrphans resolves on next launch.
 *
 * This class is the Android shell: foreground state, threading and lifecycle. The queue logic
 * itself (enqueue, drain, per-job outcome) lives in [QueueProcessor].
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

    private val main = Handler(Looper.getMainLooper())

    /**
     * One per Service instance, so its run counter lives exactly as long as this object. Lazy
     * because the adapters need a Context, which a Service only has after attach.
     */
    private val processor by lazy {
        val strings = ContextStringProvider(this)
        QueueProcessor(
            store = DaoJobStore(DownloadQueue.dao(this)),
            bridge = PythonDownloadBridge(),
            mover = SafFileMover(this, strings),
            preconditions = DevicePreconditionChecker(this),
            notifier = ServiceQueueNotifier(this, main) { label, position, total ->
                startForegroundNow(label, position, total)
            },
            strings = strings,
            clock = System::currentTimeMillis,
            stagingCleaner = DeviceStagingCleaner(this),
        )
    }

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

        processor.fatalStop = false
        val url = intent?.getStringExtra(EXTRA_URL)

        serviceScope.launch {
            // Expansion, enqueue and drain run under the processor's lock; the stop decision
            // deliberately happens outside it.
            processor.process(url)
            stopWhenIdle(startId)
        }
        return START_NOT_STICKY
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
        if (!processor.shouldStop()) return
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
        processor.fatalStop = true

        val jobId = processor.currentJobId
        if (jobId != null) {
            val message = getString(R.string.error_timeout)
            appScope.launch {
                processor.failJobById(jobId, message)
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
