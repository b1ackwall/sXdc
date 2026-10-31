package com.v1per.sxdc.download

import android.util.Log
import com.v1per.sxdc.R
import java.io.File

/**
 * The startup recovery pass, behind injectable collaborators. See DownloadQueue.recoverOrphans
 * for why it exists and why it must never start the service.
 */
internal class QueueRecovery(
    private val store: JobStore,
    private val strings: StringProvider,
    private val clock: () -> Long,
    private val clearStaged: (File) -> Unit = StagingFiles::clear,
) {

    suspend fun recover() {
        val orphans = store.orphanedJobs()
        if (orphans.isEmpty()) return

        Log.i(TAG, "Recovering ${orphans.size} job(s) left mid-flight by a previous process")
        // Files are cleared before the rows are touched: the staged path is about to be nulled
        // out, so reading it afterwards is not an option.
        orphans.forEach { job ->
            job.stagedFilePath?.let { clearStaged(File(it)) }
        }

        store.recoverOrphans(
            reason = strings.get(R.string.error_gave_up),
            now = clock(),
        )
    }

    private companion object {
        /** Same tag as before the extraction, so log lines read exactly as they did. */
        const val TAG = "DownloadQueue"
    }
}
