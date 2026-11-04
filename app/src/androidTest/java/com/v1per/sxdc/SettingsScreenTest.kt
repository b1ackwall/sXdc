package com.v1per.sxdc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.DownloadJobDao
import com.v1per.sxdc.data.DownloadStatus
import com.v1per.sxdc.download.DownloadQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Never picks a folder and taps only Emergency Stop's confirm button, against the test database.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    private val composeRule = createAndroidComposeRule<SettingsActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestDatabaseRule()).around(composeRule)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(id: Int) = context.getString(id)

    @Test
    fun showsSections() {
        composeRule.onNodeWithText(text(R.string.settings_title)).assertIsDisplayed()
        composeRule.onNodeWithText(
            text(R.string.settings_folder_heading).uppercase(),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_choose_folder)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            text(R.string.settings_ytdlp_heading).uppercase(),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            text(R.string.settings_history_heading).uppercase(),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            text(R.string.settings_emergency_stop_heading).uppercase(),
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun commandPreview_isVisible_andStartsWithFormatThenFlacExtraction() {
        composeRule.onNodeWithText("yt-dlp -f bestaudio/best -x --audio-format flac", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        val shown = composeRule.onNodeWithText("yt-dlp -f bestaudio/best -x --audio-format flac", substring = true)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }
        assertTrue(shown, shown.startsWith("yt-dlp -f bestaudio/best -x --audio-format flac"))
    }

    @Test
    fun commandPreview_helperText_saysItIsAnIllustration() {
        composeRule.onNodeWithText(text(R.string.settings_ytdlp_preview_note))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun versionFooter_containsVersionName() {
        composeRule.onNodeWithText(context.getString(R.string.settings_version, BuildConfig.VERSION_NAME))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aboutSection_licensesRow_opensLicensesScreenWithFfmpegRow() {
        composeRule.onNodeWithText(text(R.string.settings_about_heading).uppercase())
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_licenses)).performScrollTo().performClick()

        assertTrue(pollUntil(5_000) { resumedActivity() is LicensesActivity })
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("FFmpeg (ffmpeg, ffprobe)")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("FFmpeg (ffmpeg, ffprobe)").performScrollTo().assertIsDisplayed()
        Espresso.pressBackUnconditionally()
        assertTrue(pollUntil(5_000) { resumedActivity() is SettingsActivity })
    }

    @Test
    fun extraParamsField_isNotShown() {
        // #63: the field was removed because nothing passed its text to yt-dlp. It must not come
        // back before it is wired in safely, so no text field and no label of it may exist.
        assertEquals(0, composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Extra parameters", substring = true, ignoreCase = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun emergencyStop_showsConfirm_cancelDismisses() {
        composeRule.onNodeWithText(text(R.string.settings_emergency_stop)).performScrollTo().performClick()

        composeRule.onNodeWithText(text(R.string.settings_emergency_stop_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_emergency_stop_confirm_body)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_emergency_stop_confirm)).assertIsDisplayed()

        composeRule.onNodeWithText(text(R.string.settings_emergency_stop_cancel)).performClick()
        composeRule.onNodeWithText(text(R.string.settings_emergency_stop_confirm_title)).assertDoesNotExist()
    }

    /**
     * Confirming cancels the QUEUED rows and the DOWNLOADING one (#60); a finished row is kept.
     * No download is in flight here, so the abort reaches nothing, and Emergency Stop never
     * starts or stops the service.
     */
    @Test
    fun emergencyStop_confirm_cancelsQueuedAndDownloading_keepsCompleted() = runBlocking {
        val dao = DownloadQueue.dao(context)
        val queued = (1..3).map { dao.insert(DownloadJob(url = "https://example.com/queued-$it")) }
        val downloading = dao.insert(
            DownloadJob(url = "https://example.com/downloading", status = DownloadStatus.DOWNLOADING),
        )
        val completed = dao.insert(
            DownloadJob(url = "https://example.com/completed", status = DownloadStatus.COMPLETED, completedAt = 1L),
        )
        val before = jobs(dao)

        composeRule.onNodeWithText(text(R.string.settings_emergency_stop)).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.settings_emergency_stop_confirm)).performClick()
        composeRule.onNodeWithText(text(R.string.settings_emergency_stop_confirm_title)).assertDoesNotExist()

        val pending = queued + downloading
        val cancelled = pollUntil(5_000) {
            val rows = runBlocking { jobs(dao) }
            pending.all { rows.getValue(it).status == DownloadStatus.CANCELLED }
        }
        assertTrue("QUEUED and DOWNLOADING rows were not cancelled", cancelled)
        val after = jobs(dao)
        pending.forEach { assertNotNull("completedAt of cancelled job $it", after.getValue(it).completedAt) }
        assertEquals(before.getValue(completed), after.getValue(completed))
        assertEquals(0, dao.countPending())
        assertFalse("Emergency Stop started the service", isDownloadServiceRunning(context))
    }

    private suspend fun jobs(dao: DownloadJobDao): Map<Long, DownloadJob> =
        dao.observeAll().first().associateBy { it.id }

    @Test
    fun clearHistory_showsConfirm_cancelDismisses() {
        composeRule.onNodeWithText(text(R.string.settings_clear_history)).performScrollTo().performClick()

        composeRule.onNodeWithText(text(R.string.settings_clear_history_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_clear_history_confirm_body)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_clear_history_confirm)).assertIsDisplayed()

        composeRule.onNodeWithText(text(R.string.settings_clear_history_cancel)).performClick()
        composeRule.onNodeWithText(text(R.string.settings_clear_history_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun topBarBack_finishesActivity() {
        val activity = composeRule.activity
        composeRule.onNodeWithContentDescription(text(R.string.nav_back)).performClick()
        assertTrue(pollUntil(5_000) { activity.isFinishing || activity.isDestroyed })
    }

    @Test
    fun systemBack_finishesActivity() {
        val activity = composeRule.activity
        Espresso.pressBackUnconditionally()
        assertTrue(pollUntil(5_000) { activity.isFinishing || activity.isDestroyed })
    }
}
