package com.v1per.sxdc.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.R
import com.v1per.sxdc.ShareReceiverActivity
import com.v1per.sxdc.pollUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builders and channels only. The one posting test uses an unusual job id and cancels it after.
 * Channel creation is idempotent and is what SxdcApp already does at every startup.
 */
@RunWith(AndroidJUnit4::class)
class NotificationsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val testJobId = 987_654L
    private var posted = false

    @Before
    fun setUp() {
        Notifications.ensureChannels(context)
    }

    @After
    fun tearDown() {
        if (posted) manager.cancel(Notifications.terminalId(testJobId))
    }

    @Test
    fun channels_existWithExpectedImportance() {
        Notifications.ensureChannels(context) // second call: idempotent

        val progress = manager.getNotificationChannel(Notifications.CHANNEL_PROGRESS)
        val terminal = manager.getNotificationChannel(Notifications.CHANNEL_TERMINAL)
        assertNotNull(progress)
        assertNotNull(terminal)
        assertEquals("sxdc.downloads", progress.id)
        assertEquals("sxdc.downloads.terminal", terminal.id)
        // Importance is user-adjustable after creation; this asserts the code's value as long as
        // the user has not changed it on this device.
        assertEquals(NotificationManager.IMPORTANCE_LOW, progress.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, terminal.importance)
        assertEquals(context.getString(R.string.channel_downloads_name), progress.name.toString())
        assertEquals(context.getString(R.string.channel_downloads_terminal_name), terminal.name.toString())
    }

    @Test
    fun ids_matchScheme() {
        assertEquals(1001, Notifications.ID_PROGRESS)
        assertEquals(1002, Notifications.ID_TERMINAL_SUMMARY)
        assertEquals(2001, Notifications.terminalId(1))
        assertEquals(2000 + 987_654, Notifications.terminalId(testJobId))
    }

    @Test
    fun progress_withTotal_isOngoingDeterminate() {
        val n = Notifications.progress(context, "Song A", position = 3, total = 10)

        assertEquals(Notifications.CHANNEL_PROGRESS, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.CATEGORY_PROGRESS, n.category)
        assertEquals(context.getString(R.string.notif_downloading_title), n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            context.getString(R.string.notif_queue_progress, 3, 10, "Song A"),
            n.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals(10, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(2, n.extras.getInt(Notification.EXTRA_PROGRESS)) // position - 1
        assertEquals(false, n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun progress_withoutTotal_isIndeterminateWithLabel() {
        val label = context.getString(R.string.notif_preparing)
        val n = Notifications.progress(context, label)

        assertEquals(Notifications.CHANNEL_PROGRESS, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(label, n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(true, n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun success_buildsTerminalGroupedNotification() {
        val n = Notifications.success(context, "My Song", "Music/sXdc")

        assertEquals(Notifications.CHANNEL_TERMINAL, n.channelId)
        assertEquals("com.v1per.sxdc.notifications.TERMINAL", n.group)
        assertEquals(context.getString(R.string.notif_done_title), n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("My Song", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("My Song\nMusic/sXdc", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT == 0)
        assertNull(n.contentIntent)
    }

    @Test
    fun failure_buildsTerminalNotification_withPerJobFixItIntent() {
        val message = "Test failure message"
        val n = Notifications.failure(context, testJobId, message)

        assertEquals(Notifications.CHANNEL_TERMINAL, n.channelId)
        assertEquals("com.v1per.sxdc.notifications.TERMINAL", n.group)
        assertEquals(context.getString(R.string.notif_failed_title), n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(message, n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(message, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)

        val content = n.contentIntent
        assertNotNull(content)
        assertEquals(context.packageName, content.creatorPackage)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(content.isActivity)

        // PendingIntent identity = request code + Intent.filterEquals (component, action).
        // FLAG_NO_CREATE looks it up without creating one, which makes the request code observable.
        val fixIt = ShareReceiverActivity.fixItIntent(context, "extras are ignored by matching")
        val sameCode = PendingIntent.getActivity(
            context, Notifications.terminalId(testJobId), fixIt,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertEquals(content, sameCode)
        val otherCode = PendingIntent.getActivity(
            context, Notifications.terminalId(testJobId + 1), fixIt,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertNull(otherCode)
    }

    @Test
    fun post_terminalNotification_appearsWithTerminalId() {
        assumeTrue(
            "Notifications are disabled for the app",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        val id = Notifications.terminalId(testJobId)
        posted = true
        Notifications.post(context, id, Notifications.success(context, "sXdc test", "test"))

        val found = pollUntil(3_000) { manager.activeNotifications.any { it.id == id } }
        assertTrue("notification $id not active", found)
        val sbn = manager.activeNotifications.first { it.id == id }
        assertEquals(Notifications.CHANNEL_TERMINAL, sbn.notification.channelId)
    }
}
