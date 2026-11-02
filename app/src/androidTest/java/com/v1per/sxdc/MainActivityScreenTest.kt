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

private fun textEndsWith(suffix: String) = SemanticsMatcher("text ends with '$suffix'") { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.endsWith(suffix) }
}

/** Generous: on a cold process the checks import yt-dlp and start ffmpeg several times. */
private const val CHECKS_TIMEOUT_MS = 30_000L

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
}
