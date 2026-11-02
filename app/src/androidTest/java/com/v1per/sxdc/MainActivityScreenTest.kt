package com.v1per.sxdc

import android.app.NotificationManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.DownloadStatus
import com.v1per.sxdc.download.DownloadPreconditions
import com.v1per.sxdc.download.DownloadQueue
import com.v1per.sxdc.download.HaltKind
import com.v1per.sxdc.download.Notifications
import com.v1per.sxdc.download.QueueHaltStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

private fun textStartsWith(prefix: String) = SemanticsMatcher("text starts with '$prefix'") { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(prefix) }
}

private fun textEndsWith(suffix: String) = SemanticsMatcher("text ends with '$suffix'") { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.endsWith(suffix) }
}

/** Generous: on a cold process the checks import yt-dlp and start ffmpeg several times. */
private const val CHECKS_TIMEOUT_MS = 30_000L

/** Hold for the banner to follow a database or preferences write. */
private const val UI_TIMEOUT_MS = 10_000L

/**
 * Layout characterization, plus the queue halt banner. The test database and settings start empty;
 * the banner tests write rows and a halt only after launch, so the launch's onResume never starts
 * the queue on its own.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityScreenTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    private val settingsRule = TestSettingsRule()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestDatabaseRule()).around(settingsRule).around(composeRule)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao get() = DownloadQueue.dao(context)
    private val postedIds = mutableListOf<Int>()

    @After
    fun tearDown() {
        pollUntil(UI_TIMEOUT_MS) { !isDownloadServiceRunning(context) }
        val manager = context.getSystemService(NotificationManager::class.java)
        postedIds.forEach(manager::cancel)
        manager.cancel(Notifications.ID_TERMINAL_SUMMARY)
    }

    private fun queue(vararg urls: String): List<Long> = runBlocking { urls.map { dao.insert(DownloadJob(url = it)) } }

    private fun jobs(): List<DownloadJob> = runBlocking { dao.observeAll().first() }

    private fun awaitText(text: String) = composeRule.waitUntil(UI_TIMEOUT_MS) {
        composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun resume() = context.getString(R.string.queue_halt_resume)

    @Test
    fun showsTopBar_andSettingsIcon() {
        composeRule.onNodeWithText(context.getString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.settings_open)).assertIsDisplayed()
    }

    @Test
    fun showsSystemCheckRows_andPanelHeadings() {
        composeRule.onNodeWithText(context.getString(R.string.main_diagnostics).uppercase()).performScrollTo().assertIsDisplayed()
        // The checks load off the main thread (#21): wait until no row still reads "checking…".
        val pending = context.getString(R.string.check_pending)
        composeRule.waitUntil(CHECKS_TIMEOUT_MS) {
            composeRule.onAllNodes(textEndsWith(": $pending")).fetchSemanticsNodes().isEmpty()
        }
        listOf(
            R.string.check_bridge, R.string.check_ytdlp, R.string.check_ffmpeg, R.string.check_ffprobe,
            R.string.check_integration, R.string.check_native_tier,
        ).forEach { label ->
            val prefix = "${context.getString(label)}: "
            composeRule.waitUntil(CHECKS_TIMEOUT_MS) {
                composeRule.onAllNodes(textStartsWith(prefix)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(textStartsWith(prefix)).performScrollTo().assertIsDisplayed()
        }

        composeRule.onNodeWithText(context.getString(R.string.main_live_status).uppercase()).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.main_history).uppercase()).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun liveStatus_isEmptyWhileNothingPending() {
        // The guard guarantees countPending() == 0, so the empty-state line must be shown.
        composeRule.onNodeWithText(context.getString(R.string.main_live_status_empty))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun settingsIcon_opensSettingsActivity() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.settings_open)).performClick()

        val opened = pollUntil(5_000) { resumedActivity() is SettingsActivity }
        val settings = resumedActivity()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { (settings as? SettingsActivity)?.finish() }
        assertTrue("SettingsActivity was not resumed, got $settings", opened)
    }

    @Test
    fun haltBanner_showsReason_waitingCount_hint_andResume() {
        queue("https://example.invalid/a", "https://example.invalid/b")
        QueueHaltStore.set(context, HaltKind.STORAGE, System.currentTimeMillis())

        awaitText(context.getString(R.string.queue_halt_storage))
        composeRule.onNodeWithText(context.getString(R.string.queue_halt_storage)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2 links waiting").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.queue_halt_hint_storage))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(resume()).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun haltBanner_followsTheKind_andTheOneForm() {
        queue("https://example.invalid/a")
        listOf(
            HaltKind.RATE_LIMITED to (R.string.queue_halt_rate_limited to R.string.queue_halt_hint_rate_limited),
            HaltKind.SETUP to (R.string.queue_halt_setup to R.string.queue_halt_hint_setup),
        ).forEach { (kind, texts) ->
            QueueHaltStore.set(context, kind, System.currentTimeMillis())
            awaitText(context.getString(texts.first))
            awaitText(context.getString(texts.second))
        }
        composeRule.onNodeWithText("1 link waiting").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noBanner_whenNothingIsPending_andTheNextResumeClearsTheHalt() {
        QueueHaltStore.set(context, HaltKind.STORAGE, System.currentTimeMillis())
        composeRule.waitForIdle()
        assertTrue(!shown(context.getString(R.string.queue_halt_storage)))
        assertTrue(!shown(resume()))

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        assertTrue("halt was not cleared lazily", pollUntil(UI_TIMEOUT_MS) { QueueHaltStore.read(context) == null })
    }

    @Test
    fun noBanner_withoutAHalt() {
        queue("https://example.invalid/a")
        awaitText("https://example.invalid/a")
        assertNull(QueueHaltStore.read(context))
        assertTrue(!shown(resume()))
    }

    @Test
    fun resumeTap_clearsTheHalt_andTheServiceClaimsTheQueue() {
        val (id) = queue("https://example.invalid/a")
        QueueHaltStore.set(context, HaltKind.STORAGE, System.currentTimeMillis())
        awaitText(resume())
        postedIds += Notifications.terminalId(id)

        composeRule.onNodeWithText(resume()).performScrollTo().performClick()

        // The test settings hold no folder, so the run claims the row and fails it on setup. A
        // claim at all proves the storage halt was cleared, since the run never claims past one.
        val failed = pollUntil(UI_TIMEOUT_MS) { jobs().single().status == DownloadStatus.FAILED }
        assertTrue("Resume did not start a run that claimed the row; got ${jobs()}", failed)
        assertEquals(DownloadPreconditions.REASON_NO_FOLDER, jobs().single().failureReason)
        assertEquals(HaltKind.SETUP, QueueHaltStore.read(context)?.kind)
        assertTrue("DownloadService did not stop", pollUntil(UI_TIMEOUT_MS) { !isDownloadServiceRunning(context) })
    }
}
