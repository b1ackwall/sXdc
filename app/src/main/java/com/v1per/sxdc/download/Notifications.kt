package com.v1per.sxdc.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.v1per.sxdc.MainActivity
import com.v1per.sxdc.R
import com.v1per.sxdc.ShareReceiverActivity

/**
 * Two notification channels, one ongoing queue notification, and one terminal notification per
 * finished job.
 *
 * The channel split exists because the two kinds want opposite importance. The ongoing progress
 * notification is LOW, so starting a download never buzzes. The terminal success and failure
 * notifications are DEFAULT: a finished download is worth a sound, and a failure needs one, since
 * it is the only prompt to open the fix-it screen.
 *
 * The id scheme changed with the queue. The old model posted the terminal state *over* the
 * foreground-service notification at [ID_PROGRESS] and detached it on the way out. That only
 * worked while there was exactly one job: a queue has to keep showing progress for jobs 3..10
 * while job 2's result stays on screen, so results now get their own ids and their own group.
 */
object Notifications {

    private const val TAG = "Notifications"

    /** Ongoing, indeterminate progress. LOW so a started download is silent. */
    const val CHANNEL_PROGRESS = "sxdc.downloads"

    /** Terminal success/failure. DEFAULT so a finished or failed download is audible. */
    const val CHANNEL_TERMINAL = "sxdc.downloads.terminal"

    /** The foreground-service notification. One per service run, never per job. */
    const val ID_PROGRESS = 1001

    /** The group summary that collapses the per-job results together. */
    const val ID_TERMINAL_SUMMARY = 1002

    /** Terminal ids start here, clear of [ID_PROGRESS] and [ID_TERMINAL_SUMMARY]. */
    private const val TERMINAL_ID_BASE = 2000

    /** The low 30 bits of a job id: [TERMINAL_ID_BASE] + this always fits in an Int. */
    private const val JOB_ID_MASK = 0x3FFFFFFFL

    private const val GROUP_TERMINAL = "com.v1per.sxdc.notifications.TERMINAL"

    /**
     * The notification id (and PendingIntent request code) for a job's terminal notification.
     *
     * Always >= [TERMINAL_ID_BASE], never negative, so never equal to [ID_PROGRESS] or
     * [ID_TERMINAL_SUMMARY], for any Long. For job ids below 2^30 it is [TERMINAL_ID_BASE] + the
     * id, as it has always been. Ids repeat only every 2^30 jobs, which is harmless: only live
     * notifications need distinct ids.
     */
    fun terminalId(jobId: Long): Int = TERMINAL_ID_BASE + (jobId and JOB_ID_MASK).toInt()

    /** Idempotent: re-creating an existing channel is a no-op, so this is safe on every start. */
    fun ensureChannels(context: Context) {
        val progress = NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManager.IMPORTANCE_LOW)
            .setName(context.getString(R.string.channel_downloads_name))
            .setDescription(context.getString(R.string.channel_downloads_description))
            .setShowBadge(false)
            .build()
        val terminal = NotificationChannelCompat.Builder(CHANNEL_TERMINAL, NotificationManager.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.channel_downloads_terminal_name))
            .setDescription(context.getString(R.string.channel_downloads_terminal_description))
            .setShowBadge(true)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(listOf(progress, terminal))
    }

    /**
     * The queue's ongoing notification.
     *
     * With a [total] it reports position in the queue and draws a determinate bar across the whole
     * queue rather than the current job -- yt-dlp runs with `noprogress`, so per-job progress is
     * not available to report, but "3 of 10" is, and it is the more useful number anyway. Without
     * one (the pre-first-claim "Preparing" state) it falls back to an indeterminate bar.
     */
    fun progress(context: Context, label: String, position: Int = 0, total: Int = 0): Notification {
        val builder = base(context, CHANNEL_PROGRESS)
            .setContentTitle(context.getString(R.string.notif_downloading_title))
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(true)
            .setSilent(true)
            // Progress updates reuse one id; without this every label change would re-alert.
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (total > 0) {
            builder
                .setContentText(context.getString(R.string.notif_queue_progress, position, total, label))
                .setProgress(total, position - 1, false)
        } else {
            builder
                .setContentText(label)
                .setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun success(context: Context, title: String, location: String): Notification =
        base(context, CHANNEL_TERMINAL)
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$location"))
            .setSmallIcon(R.drawable.ic_stat_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setGroup(GROUP_TERMINAL)
            .build()

    /**
     * Tapping a failure opens the fix-it screen carrying *this job's* error text.
     *
     * The request code has to be per-job. PendingIntent equality ignores extras, and every fix-it
     * intent shares a component and an action, so with a fixed request code all outstanding
     * failure notifications would resolve to one PendingIntent -- and FLAG_UPDATE_CURRENT would
     * rewrite its extras on each post, leaving every one of them opening the *last* message.
     * Invisible while only one job could fail at a time; guaranteed with a queue.
     *
     * A fatal stop that left [waiting] links queued is the exception: its text gains a line with
     * the count, and the tap opens MainActivity instead, where the halt banner and Resume are.
     */
    fun failure(context: Context, jobId: Long, message: String, waiting: Int = 0): Notification {
        val halted = waiting > 0
        val intent = if (halted) mainIntent(context) else ShareReceiverActivity.fixItIntent(context, message)
        val pending = PendingIntent.getActivity(
            context,
            terminalId(jobId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val bigText = if (halted) {
            message + "\n" + context.resources.getQuantityString(R.plurals.notif_halt_waiting, waiting, waiting)
        } else {
            message
        }
        return base(context, CHANNEL_TERMINAL)
            .setContentTitle(context.getString(R.string.notif_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_stat_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setGroup(GROUP_TERMINAL)
            .build()
    }

    /** The main screen, brought to the front rather than stacked a second time. */
    fun mainIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /**
     * Posted under [ID_TERMINAL_SUMMARY] alongside every result so a ten-song queue collapses into
     * one shade entry.
     *
     * Silent and GROUP_ALERT_CHILDREN: the children are what should make the sound, and re-posting
     * this on every completion would otherwise buzz twice per job.
     */
    fun summary(context: Context): Notification =
        base(context, CHANNEL_TERMINAL)
            .setContentTitle(context.getString(R.string.notif_terminal_summary_title))
            .setSmallIcon(R.drawable.ic_stat_done)
            .setGroup(GROUP_TERMINAL)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    /**
     * Posting is best-effort. With POST_NOTIFICATIONS denied the framework drops the notification
     * silently; the download itself is unaffected, so a denial must never surface as an error.
     */
    fun post(poster: NotificationPoster, id: Int, notification: Notification) {
        try {
            poster.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification $id not posted: ${e.message}")
        }
    }

    /**
     * Note the absence of setOnlyAlertOnce on the terminal builders: each result lands on a fresh
     * id, but a *retried* job reuses its row id and therefore its notification id, and
     * FLAG_ONLY_ALERT_ONCE suppresses the alert on any update to a notification already on screen.
     */
    private fun base(context: Context, channelId: String) =
        NotificationCompat.Builder(context, channelId).setLocalOnly(true)
}
