package com.v1per.sxdc

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

private fun textStartsWith(prefix: String) = SemanticsMatcher("text starts with '$prefix'") { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(prefix) }
}

/** Layout characterization only. The test database starts empty, so no row content is asserted. */
@RunWith(AndroidJUnit4::class)
class MainActivityScreenTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestDatabaseRule()).around(composeRule)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun showsTopBar_andSettingsIcon() {
        composeRule.onNodeWithText(context.getString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.settings_open)).assertIsDisplayed()
    }

    @Test
    fun showsSystemCheckRows_andPanelHeadings() {
        composeRule.onNodeWithText(context.getString(R.string.main_diagnostics).uppercase()).performScrollTo().assertIsDisplayed()
        // System-check row labels are hardcoded in MainActivity (no string resources).
        listOf(
            "Chaquopy bridge: ", "yt-dlp: ", "ffmpeg: ", "ffprobe: ",
            "yt-dlp sees ffmpeg: ", "Native install tier: ",
        ).forEach { prefix ->
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
}
