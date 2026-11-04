package com.v1per.sxdc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

private const val UI_TIMEOUT_MS = 5_000L
private const val FFMPEG_ROW = "FFmpeg (ffmpeg, ffprobe)"
private const val LGPL_MARKER = "GNU LESSER GENERAL PUBLIC LICENSE"
private const val GPL3_MARKER = "Version 3, 29 June 2007"

/** Reads the license assets the build copies from the repo's LICENSE and licenses/. */
@RunWith(AndroidJUnit4::class)
class LicensesScreenTest {
    private val composeRule = createAndroidComposeRule<LicensesActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestDatabaseRule()).around(TestSettingsRule()).around(composeRule)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(id: Int) = context.getString(id)

    private fun awaitText(value: String, substring: Boolean = false) = composeRule.waitUntil(UI_TIMEOUT_MS) {
        composeRule.onAllNodes(hasText(value, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openRow(name: String) {
        awaitText(name)
        composeRule.onNodeWithText(name).performScrollTo().performClick()
    }

    @Test
    fun showsAppNoticeSourceLinkAndFfmpegRow() {
        composeRule.onNodeWithText(text(R.string.licenses_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.licenses_app_notice)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.licenses_source_url)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.licenses_source_offer)).performScrollTo().assertIsDisplayed()
        awaitText(FFMPEG_ROW)
        composeRule.onNodeWithText(FFMPEG_ROW).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ffmpegRow_opensLgplText() {
        openRow(FFMPEG_ROW)
        awaitText(LGPL_MARKER, substring = true)
        composeRule.onNodeWithText(LGPL_MARKER, substring = true).assertExists()
    }

    @Test
    fun appRow_opensGpl3Text() {
        openRow("sXdc")
        awaitText(GPL3_MARKER, substring = true)
        composeRule.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertExists()
    }

    @Test
    fun topBarBack_fromDetail_returnsToList_thenFinishes() {
        openRow(FFMPEG_ROW)
        awaitText(LGPL_MARKER, substring = true)
        composeRule.onNodeWithContentDescription(text(R.string.nav_back)).performClick()
        awaitText(text(R.string.licenses_title))
        composeRule.onNodeWithText(text(R.string.licenses_app_notice)).assertIsDisplayed()

        val activity = composeRule.activity
        composeRule.onNodeWithContentDescription(text(R.string.nav_back)).performClick()
        assertTrue(pollUntil(UI_TIMEOUT_MS) { activity.isFinishing || activity.isDestroyed })
    }

    @Test
    fun systemBack_fromDetail_returnsToList_thenFinishes() {
        openRow("sXdc")
        awaitText(GPL3_MARKER, substring = true)
        Espresso.pressBack()
        awaitText(text(R.string.licenses_app_notice))

        val activity = composeRule.activity
        Espresso.pressBackUnconditionally()
        assertTrue(pollUntil(UI_TIMEOUT_MS) { activity.isFinishing || activity.isDestroyed })
    }
}
