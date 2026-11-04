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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #75: the service times out while the real SafFileMover is about to copy. A gated wrapper holds
 * the move open (the download itself returns at once); the test then times the service out.
 */
@RunWith(AndroidJUnit4::class)
class DownloadServiceTimeoutMoveTest {
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
    private val bridge = ScriptedDownloadBridge(blockUrl = "https://example.invalid/none")
    private val entered = CountDownLatch(1)
    private val proceed = CountDownLatch(1)
    private var jobId = 0L
    private val moveEvents = java.util.concurrent.CopyOnWriteArrayList<String>()

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
        proceed.countDown()
        pollUntil(UI_TIMEOUT_MS) { !isDownloadServiceRunning(context) }
        app.bridgeOverride = null
        app.moverWrapper = null
        manager.cancel(Notifications.terminalId(jobId))
        manager.cancel(Notifications.ID_TERMINAL_SUMMARY)
    }

    @Test
    fun timeoutBeforeTheCopy_abortsIt_andLeavesTheFolderEmpty_withTheTimeoutRow() {
        val tree = safFolder.open()
        timeOutDuringMove(honourAbort = true)

        assertTrue("the move did not finish", pollUntil(UI_TIMEOUT_MS) { "move-returned" in moveEvents })
        assertEquals(emptyList<String>(), safFolder.names(tree))
        assertEquals(DownloadStatus.FAILED, job().status)
        assertEquals(context.getString(R.string.error_timeout), job().failureReason)
        assertNull("a timeout must not record a halt", QueueHaltStore.read(context))
    }

    @Test
    fun timeoutAfterTheLastCheck_deliversTheFile_andCorrectsTheRowToCompleted() {
        val tree = safFolder.open()
        timeOutDuringMove(honourAbort = false)

        assertTrue("the row was not corrected", pollUntil(UI_TIMEOUT_MS) { job().status == DownloadStatus.COMPLETED })
        val name = ScriptedDownloadBridge.finishedName("a")
        assertEquals(listOf(name), safFolder.names(tree))
        assertArrayEquals(ScriptedDownloadBridge.CONTENT, safFolder.read(tree, name))
        assertNull(job().failureReason)
        assertNull("a timeout must not record a halt", QueueHaltStore.read(context))
    }

    /** Starts the service on a queued job, holds its move open, times the service out, then lets the move run. */
    private fun timeOutDuringMove(honourAbort: Boolean) {
        app.moverWrapper = { real -> GatedMover(real, honourAbort) }
        jobId = runBlocking { dao.insert(DownloadJob(url = URL_A)) }
        // A duplicate of the queued row queues nothing; it only starts the service.
        share(URL_A)
        assertTrue("the move never started", entered.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        val service = DownloadService.live
        assertNotNull("no live DownloadService", service)

        instrumentation.runOnMainSync { service!!.onTimeout(0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        assertTrue("A was not failed", pollUntil(UI_TIMEOUT_MS) { job().status == DownloadStatus.FAILED })
        assertTrue(
            "the timed-out service did not stop",
            pollUntil(UI_TIMEOUT_MS) { !isDownloadServiceRunning(context) },
        )
        // The abort request follows the FAILED write on another thread; give it a moment.
        Thread.sleep(ABORT_SETTLE_MS)
        proceed.countDown()
    }

    private inner class GatedMover(private val real: FileMover, private val honourAbort: Boolean) : FileMover {
        override fun move(result: JobResult, destination: String, shouldAbort: () -> Boolean): MoveResult {
            entered.countDown()
            proceed.await(UI_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
            return real.move(result, destination, if (honourAbort) shouldAbort else ({ false })).also {
                moveEvents += "move-returned"
            }
        }

        override fun sweepPartials(): Int = real.sweepPartials()
    }

    private fun job(): DownloadJob = runBlocking { dao.observeAll().first() }.single { it.id == jobId }

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
        const val UI_TIMEOUT_MS = 15_000L
        const val ABORT_SETTLE_MS = 500L
    }
}
