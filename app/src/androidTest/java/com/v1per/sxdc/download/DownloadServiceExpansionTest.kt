package com.v1per.sxdc.download

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.ShareReceiverActivity
import com.v1per.sxdc.TestDatabaseRule
import com.v1per.sxdc.TestSettingsRule
import com.v1per.sxdc.TestSxdcApp
import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.DownloadStatus
import com.v1per.sxdc.isDownloadServiceRunning
import com.v1per.sxdc.pollUntil
import com.v1per.sxdc.saf.TestSafFolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * #75: a playlist share whose expansion is still running when the service times out is still
 * queued, and one whose expansion is still running when Emergency Stop is pressed is not.
 * The expansion is a [ScriptedDownloadBridge] call that blocks until the test releases it.
 */
@RunWith(AndroidJUnit4::class)
class DownloadServiceExpansionTest {
    private val safFolder = TestSafFolder()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestDatabaseRule())
        .around(TestSettingsRule())
        .around(safFolder)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as TestSxdcApp
    private val dao = DownloadQueue.dao(context)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val tracks = listOf(Track(TRACK_1, "One"), Track(TRACK_2, "Two"))
    private val bridge = ScriptedDownloadBridge(
        blockUrl = TRACK_1,
        expandBlockUrl = PLAYLIST,
        expansions = mapOf(PLAYLIST to tracks),
    )

    @Before
    fun setUp() {
        assumeTrue("onTimeout(int, int) needs API 35", Build.VERSION.SDK_INT >= 35)
        // Only ever the debug package.
        assertEquals(DEBUG_PACKAGE, context.packageName)
        assertFalse("DownloadService is already running", isDownloadServiceRunning(context))
        safFolder.open()
        app.bridgeOverride = bridge
    }

    @After
    fun tearDown() {
        // Never leave a worker thread blocked, whatever the test did.
        bridge.expandRelease.countDown()
        bridge.release.countDown()
        pollUntil(UI_TIMEOUT_MS) { !isDownloadServiceRunning(context) }
        app.bridgeOverride = null
        manager.cancel(Notifications.ID_TERMINAL_SUMMARY)
    }

    @Test
    fun timeoutDuringExpansion_theBlockedExpansionStillQueuesThePlaylist() {
        share(PLAYLIST)
        assertTrue("expansion never started", pollUntil(UI_TIMEOUT_MS) { "expand:$PLAYLIST" in bridge.events })
        val service = DownloadService.live
        assertNotNull("no live DownloadService", service)

        instrumentation.runOnMainSync { service!!.onTimeout(0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        assertTrue("the timed-out service did not stop", pollUntil(UI_TIMEOUT_MS) { !serviceRunning() })

        bridge.expandRelease.countDown()
        assertTrue("the playlist was not queued", pollUntil(UI_TIMEOUT_MS) { jobs().size == tracks.size })
        assertEquals(tracks.map { it.url }, jobs().map { it.url })
        // Queued for the next visible resume; nothing drains it from the torn-down service.
        Thread.sleep(SETTLE_MS)
        assertTrue(jobs().all { it.status == DownloadStatus.QUEUED })
        assertFalse("nothing should have started downloading", bridge.started(TRACK_1))
    }

    @Test
    fun emergencyStopDuringExpansion_queuesNothing() {
        share(PLAYLIST)
        assertTrue("expansion never started", pollUntil(UI_TIMEOUT_MS) { "expand:$PLAYLIST" in bridge.events })

        runBlocking { EmergencyStop.run(context) }
        bridge.expandRelease.countDown()

        assertTrue("DownloadService did not stop itself", pollUntil(UI_TIMEOUT_MS) { !serviceRunning() })
        assertEquals(emptyList<DownloadJob>(), jobs())
        assertFalse("a dropped share must not download", bridge.started(TRACK_1))
    }

    private fun serviceRunning() = isDownloadServiceRunning(context)

    private fun jobs(): List<DownloadJob> = runBlocking { dao.observeAll().first() }.sortedBy { it.id }

    /** Exactly as the share sheet would; the activity starts the service from its onResume. */
    private fun share(url: String) {
        val intent = Intent(context, ShareReceiverActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        ActivityScenario.launch<ShareReceiverActivity>(intent).use { scenario ->
            assertTrue(
                "ShareReceiverActivity did not hand the link to the service",
                pollUntil(UI_TIMEOUT_MS) { scenario.state == Lifecycle.State.DESTROYED },
            )
        }
    }

    private companion object {
        const val DEBUG_PACKAGE = "com.v1per.sxdc.debug"
        const val PLAYLIST = "https://example.invalid/playlist"
        const val TRACK_1 = "https://example.invalid/t1"
        const val TRACK_2 = "https://example.invalid/t2"
        const val UI_TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 1_500L
    }
}
