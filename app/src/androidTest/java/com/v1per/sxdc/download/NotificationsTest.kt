package com.v1per.sxdc.download

import android.Manifest
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builders and channels only. The one posting test uses an unusual job id and cancels it after.
 * Channel creation is idempotent and is what SxdcApp already does at every startup.
 *
 * POST_NOTIFICATIONS is granted to the debug package before every test, so the posting test
 * never depends on what an earlier install left behind (#22). It is never revoked here: revoking
 * a runtime permission kills the app process, and with it the test run.
 */
@RunWith(AndroidJUnit4::class)
class NotificationsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val testJobId = 987_654L
    private var posted = false

    @Before
    fun setUp() {
        // Only ever the debug package: never grant anything to the release app.
        assertEquals(DEBUG_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
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
        val n = Notifications.failure(context, testJobId, message, waiting = 0)

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
    fun haltFailure_addsTheWaitingLine_andOpensMainActivity() {
        val message = "Not enough free space to save the file."
        val haltJobId = testJobId + 10
        val n = Notifications.failure(context, haltJobId, message, waiting = 2)

        val waitingLine = context.resources.getQuantityString(R.plurals.notif_halt_waiting, 2, 2)
        assertEquals("2 more links waiting. Open sXdc to resume.", waitingLine)
        // The collapsed text is the failure reason alone; the count is only the extra line.
        assertEquals(message, n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("$message\n$waitingLine", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())

        val content = n.contentIntent
        assertNotNull(content)
        val main = PendingIntent.getActivity(
            context, Notifications.terminalId(haltJobId), Notifications.mainIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertEquals(content, main)
        val fixIt = PendingIntent.getActivity(
            context, Notifications.terminalId(haltJobId), ShareReceiverActivity.fixItIntent(context, message),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertNull(fixIt)
    }

    @Test
    fun haltFailure_singleWaiting_usesTheOneForm() {
        val n = Notifications.failure(context, testJobId + 11, "m", waiting = 1)
        assertEquals(
            "m\n1 more link waiting. Open sXdc to resume.",
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
        )
    }

    @Test
    fun post_terminalNotification_appearsWithTerminalId() {
        assertTrue(
            "Notifications are disabled for the app despite the grant",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        val id = Notifications.terminalId(testJobId)
        posted = true
        Notifications.post(SystemNotificationPoster(context), id, Notifications.success(context, "sXdc test", "test"))

        val found = pollUntil(3_000) { manager.activeNotifications.any { it.id == id } }
        assertTrue("notification $id not active", found)
        val sbn = manager.activeNotifications.first { it.id == id }
        assertEquals(Notifications.CHANNEL_TERMINAL, sbn.notification.channelId)
    }

    private companion object {
        const val DEBUG_PACKAGE = "com.v1per.sxdc.debug"
    }
}
