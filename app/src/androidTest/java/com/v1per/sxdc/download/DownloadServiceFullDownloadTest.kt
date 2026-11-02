package com.v1per.sxdc.download

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.R
import com.v1per.sxdc.ShareReceiverActivity
import com.v1per.sxdc.TestDatabaseRule
import com.v1per.sxdc.TestSettingsRule
import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.DownloadStatus
import com.v1per.sxdc.http.LocalHttpServer
import com.v1per.sxdc.isDownloadServiceRunning
import com.v1per.sxdc.pollUntil
import com.v1per.sxdc.realStagingDir
import com.v1per.sxdc.saf.TestSafFolder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * One share, end to end, through the real DownloadService, offline (#24): ShareReceiverActivity
 * receives an ACTION_SEND for a URL on [LocalHttpServer], checks the preconditions and starts the
 * service; yt-dlp and ffmpeg stage a FLAC in the test staging folder; SafFileMover delivers it to
 * a [TestSafFolder] root; the terminal notification is posted and the service stops itself.
 *
 * The queue is the empty test database and the destination a fresh test root, so nothing here
 * touches the install's jobs, settings or chosen folder. Nothing asserts how HTTP 404 is
 * classified (#64).
 */
@RunWith(AndroidJUnit4::class)
class DownloadServiceFullDownloadTest {

    private val safFolder = TestSafFolder()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestDatabaseRule())
        .around(TestSettingsRule())
        .around(safFolder)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val dao = DownloadQueue.dao(context)

    /** A .part file, which the staging sweep would delete if it ever reached the real folder. */
    private val realLeftover = File(realStagingDir(context), "sxdc-test-leftover-${UUID.randomUUID()}.part")

    private val postedIds = mutableListOf<Int>()

    @Before
    fun setUp() {
        // Only ever the debug package: never grant anything to the release app.
        assertEquals(DEBUG_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assertTrue(
            "Notifications are disabled for the app despite the grant",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        assertTrue("DownloadService is already running", !isDownloadServiceRunning(context))
        realLeftover.writeBytes(LEFTOVER)
    }

    @After
    fun tearDown() {
        realLeftover.delete()
        postedIds.forEach(manager::cancel)
        manager.cancel(Notifications.ID_TERMINAL_SUMMARY)
    }

    @Test
    fun share_localWav_isDeliveredAsFlac_notifiedAndTheServiceStops() {
        val tree = safFolder.open()

        val job = shareAndAwaitResult(server.url("/sxdc-tone.wav"))

        assertEquals(DownloadStatus.COMPLETED, job.status)
        assertEquals("sxdc-tone", job.title)
        assertEquals(null, job.stagedFilePath)
        assertNotNull(job.completedAt)

        assertEquals(listOf(DELIVERED_NAME), safFolder.names(tree))
        val delivered = safFolder.read(tree, DELIVERED_NAME)
        assertTrue("delivered file is empty", delivered.isNotEmpty())
        assertArrayEquals("fLaC".toByteArray(), delivered.copyOf(4))

        assertEquals(context.getString(R.string.notif_done_title), awaitTerminal(job).title())

        assertStoppedAndClean()
    }

    @Test
    fun share_http404_failsWithNoOutputAnywhere() {
        val tree = safFolder.open()

        val job = shareAndAwaitResult(server.url("/status/404"))

        // Only that it failed, with a reason: the kind for this status is #64's to settle.
        assertEquals(DownloadStatus.FAILED, job.status)
        assertTrue("no failure reason", !job.failureReason.isNullOrBlank())
        assertEquals(null, job.stagedFilePath)

        assertEquals(emptyList<String>(), safFolder.names(tree))
        assertEquals(context.getString(R.string.notif_failed_title), awaitTerminal(job).title())

        assertStoppedAndClean()
    }

    /**
     * Shares [url] exactly as the share sheet would, and waits for the one job it queues to end.
     * The activity starts the service from its onResume and finishes; anything else means the
     * preconditions failed and it is showing the fix-it screen.
     */
    private fun shareAndAwaitResult(url: String): DownloadJob {
        val intent = Intent(context, ShareReceiverActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Test share: $url")
        ActivityScenario.launch<ShareReceiverActivity>(intent).use { scenario ->
            assertTrue(
                "ShareReceiverActivity did not hand the link to the service",
                pollUntil(UI_TIMEOUT_MS) { scenario.state == Lifecycle.State.DESTROYED },
            )
        }

        var last: List<DownloadJob> = emptyList()
        val ended = pollUntil(DOWNLOAD_TIMEOUT_MS, stepMs = 250) {
            last = runBlocking { dao.observeAll().first() }
            last.size == 1 && last.single().status in TERMINAL
        }
        assertTrue("job did not end within $DOWNLOAD_TIMEOUT_MS ms: $last", ended)
        assertEquals(url, last.single().url)
        return last.single()
    }

    private fun awaitTerminal(job: DownloadJob): Notification {
        val id = Notifications.terminalId(job.id)
        postedIds += id
        assertTrue("terminal notification $id not active", pollUntil(UI_TIMEOUT_MS) { active(id) != null })
        val sbn = active(id)!!
        assertEquals(Notifications.CHANNEL_TERMINAL, sbn.notification.channelId)
        return sbn.notification
    }

    private fun active(id: Int) = manager.activeNotifications.firstOrNull { it.id == id }

    private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    /** The service and its progress notification are gone, and both staging folders are as they should be. */
    private fun assertStoppedAndClean() {
        val stopped = pollUntil(UI_TIMEOUT_MS) { !isDownloadServiceRunning(context) }
        assertTrue("DownloadService did not stop itself", stopped)
        assertEquals(null, active(Notifications.ID_PROGRESS))

        val staging = DownloadPreconditions.outputDir(context)
        assertEquals(emptyList<String>(), staging.list().orEmpty().toList())
        assertArrayEquals("real staging leftover was touched", LEFTOVER, realLeftover.readBytes())
    }

    companion object {
        private const val DEBUG_PACKAGE = "com.v1per.sxdc.debug"
        private const val DELIVERED_NAME = "sxdc-tone [sxdc-tone].flac"
        private const val UI_TIMEOUT_MS = 15_000L
        private const val DOWNLOAD_TIMEOUT_MS = 120_000L
        private val TERMINAL = setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
        private val LEFTOVER = "not the test's to delete".toByteArray()

        private lateinit var server: LocalHttpServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            server = LocalHttpServer()
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            server.close()
        }
    }
}
