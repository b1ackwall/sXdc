package com.v1per.sxdc.download

import android.content.Context
import android.os.Handler
import com.v1per.sxdc.ui.Toasts

/** Everything the queue engine shows the user. */
internal interface QueueNotifier {
    fun progress(label: String, position: Int, total: Int)

    /** Posts the success notification under the job's terminal id, then the group summary. */
    fun success(jobId: Long, title: String, savedTo: String)

    /** Posts the failure notification under the job's terminal id, then the group summary. */
    fun failure(jobId: Long, message: String)

    fun toast(message: String)
}

/**
 * Keeps the service's threading: progress and toasts hop to the main thread, the terminal
 * notifications are posted directly from the calling (worker) thread.
 *
 * @param startForeground the service's own foreground-start helper, which must stay inside it
 */
internal class ServiceQueueNotifier(
    private val context: Context,
    private val main: Handler,
    private val startForeground: (label: String, position: Int, total: Int) -> Unit,
) : QueueNotifier {

    override fun progress(label: String, position: Int, total: Int) {
        main.post { startForeground(label, position, total) }
    }

    override fun success(jobId: Long, title: String, savedTo: String) {
        Notifications.post(
            context,
            Notifications.terminalId(jobId),
            Notifications.success(context, title, savedTo),
        )
        Notifications.postSummary(context)
    }

    override fun failure(jobId: Long, message: String) {
        Notifications.post(
            context,
            Notifications.terminalId(jobId),
            Notifications.failure(context, jobId, message),
        )
        Notifications.postSummary(context)
    }

    override fun toast(message: String) {
        main.post { Toasts.showReplacing(context, message) }
    }
}
