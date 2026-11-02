package com.v1per.sxdc.download

import android.app.Notification
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
 * @param poster where the terminal notifications and the summary go; the system in production
 * @param startForeground the service's own foreground-start helper, which must stay inside it.
 *   Progress goes there, never through [poster]: it is the foreground-service notification.
 */
internal class ServiceQueueNotifier(
    private val context: Context,
    private val main: Handler,
    private val poster: NotificationPoster = SystemNotificationPoster(context),
    private val startForeground: (label: String, position: Int, total: Int) -> Unit,
) : QueueNotifier {

    override fun progress(label: String, position: Int, total: Int) {
        main.post { startForeground(label, position, total) }
    }

    override fun success(jobId: Long, title: String, savedTo: String) {
        postTerminal(jobId, Notifications.success(context, title, savedTo))
    }

    override fun failure(jobId: Long, message: String) {
        postTerminal(jobId, Notifications.failure(context, jobId, message))
    }

    override fun toast(message: String) {
        main.post { Toasts.showReplacing(context, message) }
    }

    private fun postTerminal(jobId: Long, notification: Notification) {
        Notifications.post(poster, Notifications.terminalId(jobId), notification)
        Notifications.post(poster, Notifications.ID_TERMINAL_SUMMARY, Notifications.summary(context))
    }
}
