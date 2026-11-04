package com.v1per.sxdc.download

import android.content.Context

/**
 * Settings' Emergency Stop: cancel every pending row, the one DOWNLOADING included, then ask the
 * download in flight to stop (#60).
 *
 * Rows first, so that whatever the aborted call writes when it returns hits the DOWNLOADING
 * guards: no FAILED overwrite, no notification, no halt, no delivery. A move that has already
 * started cannot be stopped; that file is delivered, with no success notification. The halt is
 * cleared last: nothing is left queued, so nothing is left to resume.
 */
internal object EmergencyStop {
    suspend fun run(
        cancelRows: suspend () -> Unit,
        abortInFlight: () -> Unit,
        clearHalt: () -> Unit,
    ) {
        cancelRows()
        abortInFlight()
        clearHalt()
    }

    /** Blocking database and Python work: call off the main thread. */
    suspend fun run(context: Context) = run(
        cancelRows = { DownloadQueue.dao(context).cancelAllPending(System.currentTimeMillis()) },
        abortInFlight = { QueueRunLock.active.abortCurrent() },
        clearHalt = { QueueHaltStore.clear(context) },
    )
}
