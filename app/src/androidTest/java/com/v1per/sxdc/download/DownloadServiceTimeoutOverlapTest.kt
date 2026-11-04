package com.v1per.sxdc.download

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.R
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

/**
 * #61: a service instance that timed out while its download was still running must not overlap
 * the next instance (#43).
 *
 * The system's 6-hour limit can only be shortened through a device-wide setting, so the test
 * calls onTimeout itself on the live instance, from the main thread, the way the system would.
 * The download is a [ScriptedDownloadBridge] that blocks job A until the test releases it.
 */
@RunWith(AndroidJUnit4::class)
class DownloadServiceTimeoutOverlapTest {
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
    private val bridge = ScriptedDownloadBridge(blockUrl = URL_A)
    private val staging get() = DownloadPreconditions.outputDir(context)
    private val jobIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        assumeTrue("onTimeout(int, int) needs API 35", Build.VERSION.SDK_INT >= 35)
        // Only ever the debug package.
        assertEquals(DEBUG_PACKAGE, context.packageName)
        assertFalse("DownloadService is already running", isDownloadServiceRunning(context))
        app.bridgeOverride = bridge
    }

    @After
    fun tearDown() {
        // Never leave a worker thread blocked, whatever the test did.
        bridge.release.countDown()
        pollUntil(UI_TIMEOUT_MS) { !isDownloadServiceRunning(context) }
        app.bridgeOverride = null
        jobIds.forEach { manager.cancel(Notifications.terminalId(it)) }
        manager.cancel(Notifications.ID_TERMINAL_SUMMARY)
    }

    @Test
    fun timeoutMidDownload_nextInstanceWaitsForTheStaleCall_thenDeliversTheNextJob() {
        val tree = safFolder.open()
        val a = insert(URL_A)
        val b = insert(URL_B)
        val partA = File(staging, ScriptedDownloadBridge.partName("a"))

        // A duplicate of A queues nothing; it only starts the service, which claims A first.
        share(URL_A)
        assertTrue("A never started", pollUntil(UI_TIMEOUT_MS) { bridge.started(URL_A) })
        assertTrue("A's .part was not staged", partA.isFile)
        val first = DownloadService.live
        assertNotNull("no live DownloadService", first)

        var thrown: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                first!!.onTimeout(0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        assertNull(thrown)
        assertTrue("A was not failed", pollUntil(UI_TIMEOUT_MS) { job(a).status == DownloadStatus.FAILED })
        assertEquals(context.getString(R.string.error_timeout), job(a).failureReason)
        assertTrue("the timed-out service did not stop", pollUntil(UI_TIMEOUT_MS) { !serviceRunning() })
        assertFalse("A's blocked call returned early", bridge.returned(URL_A))

        // A new instance, while the stale call is still blocked.
        share(URL_B)
        assertTrue(
            "no new DownloadService instance",
            pollUntil(UI_TIMEOUT_MS) { DownloadService.live.let { it != null && it !== first } },
        )
        Thread.sleep(OVERLAP_WINDOW_MS)
        assertFalse("B started while the timed-out call was still running (#43)", bridge.started(URL_B))
        assertTrue("A's .part was swept while its call was running", partA.isFile)

        bridge.release.countDown()
        assertTrue("B did not finish", pollUntil(DOWNLOAD_TIMEOUT_MS, stepMs = 250) { job(b).status in TERMINAL })

        assertEquals(DownloadStatus.COMPLETED, job(b).status)
        assertTrue(
            "the stale call must return before B starts: ${bridge.events}",
            bridge.events.indexOf("return:$URL_A") < bridge.events.indexOf("start:$URL_B"),
        )
        assertEquals(1, bridge.maxConcurrent)
        // A's late result was discarded: not delivered, row still the timeout's.
        val bName = ScriptedDownloadBridge.finishedName("b")
        assertEquals(listOf(bName), safFolder.names(tree))
        assertArrayEquals(ScriptedDownloadBridge.CONTENT, safFolder.read(tree, bName))
        assertEquals(DownloadStatus.FAILED, job(a).status)
        assertEquals(context.getString(R.string.error_timeout), job(a).failureReason)
        assertNull("a timeout must not record a halt", QueueHaltStore.read(context))

        assertTrue("DownloadService did not stop itself", pollUntil(UI_TIMEOUT_MS) { !serviceRunning() })
        assertEquals(emptyList<String>(), staging.list().orEmpty().toList())
    }

    private fun serviceRunning() = isDownloadServiceRunning(context)

    private fun insert(url: String): Long = runBlocking { dao.insert(DownloadJob(url = url)) }.also { jobIds += it }

    private fun job(id: Long): DownloadJob = runBlocking { dao.observeAll().first() }.single { it.id == id }

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
        const val URL_A = "https://example.invalid/a"
        const val URL_B = "https://example.invalid/b"
        const val UI_TIMEOUT_MS = 15_000L
        const val DOWNLOAD_TIMEOUT_MS = 60_000L
        const val OVERLAP_WINDOW_MS = 2_000L
        val TERMINAL = setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
    }
}
