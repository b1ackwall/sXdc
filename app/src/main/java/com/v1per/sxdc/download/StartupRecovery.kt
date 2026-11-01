package com.v1per.sxdc.download

import android.util.Log
import kotlinx.coroutines.CompletableDeferred

/**
 * The gate between the startup recovery pass and the first queue claim (issue #67).
 *
 * Recovery runs in the background from SxdcApp.onCreate, and resolves every DOWNLOADING row as an
 * orphan. A queue run that claimed a row before that update landed would have its live job
 * requeued or failed under it, so QueueProcessor waits here before its first claim.
 *
 * The gate opens exactly once per process and never closes again. [run] opens it whatever
 * recovery does, so a failed recovery can never leave the queue waiting.
 */
internal object StartupRecovery {

    private const val TAG = "StartupRecovery"

    private val done = CompletableDeferred<Unit>()

    fun markDone() {
        done.complete(Unit)
    }

    suspend fun awaitDone() {
        done.await()
    }

    /**
     * SxdcApp's launch body: runs [recover], logs instead of throwing, and always calls
     * [markDone] last. A recovery failure must not crash the process from a background launch.
     */
    suspend fun run(recover: suspend () -> Unit, markDone: () -> Unit = ::markDone) {
        try {
            recover()
        } catch (e: Exception) {
            Log.w(TAG, "Startup recovery failed; the queue continues without it", e)
        } finally {
            markDone()
        }
    }
}
