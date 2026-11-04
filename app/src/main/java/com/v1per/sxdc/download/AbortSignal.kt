package com.v1per.sxdc.download

/**
 * Asks one download call to stop (#60). Made fresh for every claimed job.
 *
 * Plain Kotlin, so the queue logic and its tests never touch Python. PythonDownloadBridge
 * [attach]es the per-call Python threading.Event that sxdc_download.download_audio checks from
 * its yt-dlp hooks; [request] then sets it. A request that arrives before the attach is not lost:
 * the attach runs the action at once.
 *
 * Cooperative only: yt-dlp notices at its next hook call, so an extraction, a stalled socket (up
 * to the socket timeout per retry) or a running ffmpeg step finishes first.
 */
internal class AbortSignal {
    @Volatile
    var requested = false
        private set

    private var action: (() -> Unit)? = null

    /** Idempotent. Runs the attached action, if any, on the caller's thread. */
    fun request() {
        val toRun = synchronized(this) {
            if (requested) return
            requested = true
            action
        }
        toRun?.invoke()
    }

    fun attach(onRequest: () -> Unit) {
        val runNow = synchronized(this) {
            action = onRequest
            requested
        }
        if (runNow) onRequest()
    }
}
