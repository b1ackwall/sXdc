package com.v1per.sxdc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Never types into the extra-params field, never picks a folder, and never taps a confirm button.
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
        composeRule.onNodeWithText(text(R.string.settings_folder_heading).uppercase()).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_choose_folder)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_ytdlp_heading).uppercase()).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_history_heading).uppercase()).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_emergency_stop_heading).uppercase()).performScrollTo().assertIsDisplayed()
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
    fun extraParamsField_exists() {
        composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
        composeRule.onNodeWithText(text(R.string.settings_ytdlp_label), substring = true, useUnmergedTree = true)
            .assertExists()
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
