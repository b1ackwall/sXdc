package com.v1per.sxdc.download

import android.app.Notification
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ServiceQueueNotifier over a [NotificationPoster] that records, drops or throws instead of
 * reaching the notification shade (#22). Toasts are not exercised: they would show on screen.
 */
@RunWith(AndroidJUnit4::class)
class ServiceQueueNotifierTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())

    /** Records every id it is handed, in order; throws SecurityException for [failingIds]. */
    private class RecordingPoster(private val failingIds: Set<Int> = emptySet()) : NotificationPoster {
        val posts = mutableListOf<Pair<Int, Notification>>()
        val ids get() = posts.map { it.first }

        override fun notify(id: Int, notification: Notification) {
            posts += id to notification
            if (id in failingIds) throw SecurityException("test: posting refused for $id")
        }
    }

    private class ForegroundCall(val label: String, val position: Int, val total: Int, val onMain: Boolean)

    private fun notifier(poster: NotificationPoster, onForeground: (ForegroundCall) -> Unit = {}) =
        ServiceQueueNotifier(context, main, poster) { label, position, total ->
            onForeground(ForegroundCall(label, position, total, Looper.myLooper() == Looper.getMainLooper()))
        }

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private val summaryId = Notifications.ID_TERMINAL_SUMMARY
    private fun terminal(jobId: Long) = Notifications.terminalId(jobId)

    @Test
    fun progress_reachesStartForegroundOnTheMainThread_andNeverThePoster() {
        val poster = RecordingPoster()
        val latch = CountDownLatch(1)
        var call: ForegroundCall? = null
        val notifier = notifier(poster) {
            call = it
            latch.countDown()
        }
        notifier.progress("Song A", 3, 10)

        assertTrue("startForeground was not called", latch.await(5, TimeUnit.SECONDS))
        val seen = call!!
        assertEquals("Song A", seen.label)
        assertEquals(3, seen.position)
        assertEquals(10, seen.total)
        assertTrue("startForeground ran off the main thread", seen.onMain)
        assertTrue(poster.posts.isEmpty())
    }

    @Test
    fun success_postsTheResultUnderItsTerminalId_thenTheSummary() {
        val poster = RecordingPoster()
        notifier(poster).success(JOB_A, "My Song", "Music/sXdc")

        assertEquals(listOf(terminal(JOB_A), summaryId), poster.ids)
        val (_, result) = poster.posts[0]
        assertEquals(Notifications.CHANNEL_TERMINAL, result.channelId)
        assertEquals("My Song", result.text())
        assertSummary(poster.posts[1].second)
    }

    @Test
    fun failure_postsTheResultWithItsFixItIntent_thenTheSummary() {
        val poster = RecordingPoster()
        notifier(poster).failure(JOB_B, "Test failure message", waiting = 0)

        assertEquals(listOf(terminal(JOB_B), summaryId), poster.ids)
        val (_, result) = poster.posts[0]
        assertEquals(Notifications.CHANNEL_TERMINAL, result.channelId)
        assertEquals("Test failure message", result.text())
        assertNotNull(result.contentIntent)
        assertSummary(poster.posts[1].second)
    }

    @Test
    fun failure_passesTheWaitingCountApartFromTheMessage() {
        val poster = RecordingPoster()
        notifier(poster).failure(JOB_B, "Stopped", waiting = 3)

        val (_, result) = poster.posts[0]
        assertEquals("Stopped", result.text())
        assertEquals(
            "Stopped\n" + context.resources.getQuantityString(R.plurals.notif_halt_waiting, 3, 3),
            result.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        )
        assertSummary(poster.posts[1].second)
    }

    @Test
    fun postingOrder_isEachResultFollowedByTheSummary() {
        val poster = RecordingPoster()
        val notifier = notifier(poster)
        notifier.success(JOB_A, "A", "here")
        notifier.failure(JOB_B, "B failed", waiting = 0)
        notifier.success(JOB_C, "C", "here")

        assertEquals(
            listOf(terminal(JOB_A), summaryId, terminal(JOB_B), summaryId, terminal(JOB_C), summaryId),
            poster.ids,
        )
    }

    @Test
    fun securityException_doesNotEscape_andTheNextPostsAreStillMade() {
        // Every post refused: nothing may escape, and every later post is still attempted.
        val poster = RecordingPoster(failingIds = setOf(terminal(JOB_A), terminal(JOB_B), summaryId))
        val latch = CountDownLatch(1)
        val notifier = notifier(poster) { latch.countDown() }

        notifier.success(JOB_A, "A", "here")
        notifier.failure(JOB_B, "B failed", waiting = 0)
        notifier.progress("next", 1, 1)

        assertEquals(listOf(terminal(JOB_A), summaryId, terminal(JOB_B), summaryId), poster.ids)
        assertTrue("progress stopped working after a refused post", latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun securityExceptionOnTheResult_stillPostsTheSummary() {
        val poster = RecordingPoster(failingIds = setOf(terminal(JOB_A)))
        notifier(poster).success(JOB_A, "A", "here")

        assertEquals(listOf(terminal(JOB_A), summaryId), poster.ids)
    }

    @Test
    fun disabledPoster_dropsEverything_andTheFlowStillCompletes() {
        // What a denied POST_NOTIFICATIONS looks like on API 33+: no exception, nothing shown.
        var dropped = 0
        val latch = CountDownLatch(2)
        val notifier = notifier({ _, _ -> dropped++ }) { latch.countDown() }

        notifier.progress("first", 1, 2)
        notifier.success(JOB_A, "A", "here")
        notifier.progress("second", 2, 2)
        notifier.failure(JOB_B, "B failed", waiting = 0)

        assertTrue("progress did not complete", latch.await(5, TimeUnit.SECONDS))
        assertEquals(4, dropped)
        val manager = context.getSystemService(NotificationManager::class.java)
        val shown = manager.activeNotifications.map { it.id }
        assertFalse(terminal(JOB_A) in shown)
        assertFalse(terminal(JOB_B) in shown)
    }

    private fun assertSummary(summary: Notification) {
        assertEquals(Notifications.CHANNEL_TERMINAL, summary.channelId)
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals("com.v1per.sxdc.notifications.TERMINAL", summary.group)
    }

    private companion object {
        // Unusual ids, clear of anything a real install has posted.
        const val JOB_A = 987_701L
        const val JOB_B = 987_702L
        const val JOB_C = 987_703L
    }
}
