package com.v1per.sxdc.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.v1per.sxdc.R
import com.v1per.sxdc.ShareReceiverActivity

/**
 * Two notification channels plus the three notification states the download flow uses.
 *
 * The split exists because the two kinds of notification want opposite importance. The ongoing
 * progress notification is LOW, so starting a download never buzzes. The terminal success and
 * failure notifications are DEFAULT: a finished download is worth a sound, and a failure needs
 * one, since it is the only prompt to open the fix-it screen.
 */
object Notifications {

    /** Ongoing, indeterminate progress. LOW so a started download is silent. */
    const val CHANNEL_PROGRESS = "sxdc.downloads"

    /** Terminal success/failure. DEFAULT so a finished or failed download is audible. */
    const val CHANNEL_TERMINAL = "sxdc.downloads.terminal"

    /** The foreground-service notification. Reused for the terminal state after detaching. */
    const val ID_PROGRESS = 1001

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

    fun progress(context: Context, label: String): Notification =
        base(context, CHANNEL_PROGRESS)
            .setContentTitle(context.getString(R.string.notif_downloading_title))
            .setContentText(label)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(true)
            .setSilent(true)
            // Progress updates reuse one id; without this every label change would re-alert.
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    fun success(context: Context, title: String, location: String): Notification =
        base(context, CHANNEL_TERMINAL)
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$location"))
            .setSmallIcon(R.drawable.ic_stat_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

    /** Tapping a failure opens the fix-it screen carrying the error text. */
    fun failure(context: Context, message: String): Notification {
        val intent = ShareReceiverActivity.fixItIntent(context, message)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return base(context, CHANNEL_TERMINAL)
            .setContentTitle(context.getString(R.string.notif_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_stat_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
    }

    /**
     * Posting is best-effort. With POST_NOTIFICATIONS denied the framework drops the notification
     * silently; the download itself is unaffected, so a denial must never surface as an error.
     */
    @SuppressLint("MissingPermission")
    fun post(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted. Intentionally ignored.
        }
    }

    /**
     * Note the absence of setOnlyAlertOnce here: the terminal notifications replace the still
     * showing progress notification under [ID_PROGRESS], and FLAG_ONLY_ALERT_ONCE suppresses the
     * alert on any update to a notification already on screen — which would silence them again.
     */
    private fun base(context: Context, channelId: String) =
        NotificationCompat.Builder(context, channelId).setLocalOnly(true)
}
