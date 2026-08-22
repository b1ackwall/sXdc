package com.v1per.sxdc.download

import android.content.Context
import android.util.Log
import com.v1per.sxdc.R
import com.v1per.sxdc.data.DownloadJobDao
import com.v1per.sxdc.data.SxdcDatabase
import java.io.File

/**
 * The bits of queue handling that are not pure SQL, chiefly the startup recovery pass.
 */
object DownloadQueue {

    private const val TAG = "DownloadQueue"

    fun dao(context: Context): DownloadJobDao = SxdcDatabase.get(context).downloadJobs()

    /**
     * Resolve every job left claimed by a process that did not survive.
     *
     * Nothing runs when Android kills the process, so no "we died" marker can ever be written.
     * The inference stands in for one: DOWNLOADING is only ever set by the service worker in this
     * process, so any row still holding it *at process start* has no live owner by definition.
     * COMPLETED and FAILED rows need no attention at all -- those writes committed before the
     * kill, which is the whole reason the queue is in a database.
     *
     * Files are cleared before the rows are touched: the staged path is about to be nulled out, so
     * reading it afterwards is not an option, and a requeued job re-downloads from scratch anyway.
     *
     * Strictly bookkeeping. This must **not** start DownloadService -- it runs from
     * Application.onCreate, which the system may reach for any background reason, and a dataSync
     * foreground service started from there throws ForegroundServiceStartNotAllowedException.
     * Restarting the queue is MainActivity's job, from a visible Activity.
     */
    suspend fun recoverOrphans(context: Context) {
        val dao = dao(context)
        val orphans = dao.orphanedJobs()
        if (orphans.isEmpty()) return

        Log.i(TAG, "Recovering ${orphans.size} job(s) left mid-flight by a previous process")
        orphans.forEach { job ->
            job.stagedFilePath?.let { StagingFiles.clear(File(it)) }
        }

        dao.recoverOrphans(
            reason = context.getString(R.string.error_gave_up),
            now = System.currentTimeMillis(),
        )
    }
}
