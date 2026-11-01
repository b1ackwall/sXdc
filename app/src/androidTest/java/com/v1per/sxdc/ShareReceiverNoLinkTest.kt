package com.v1per.sxdc

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Share intents that carry no http(s) link, so the activity never reaches the service. No test in
 * this class ever sends text containing an http:// or https:// link.
 */
@RunWith(AndroidJUnit4::class)
class ShareReceiverNoLinkTest {

    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestDatabaseRule()).around(composeRule)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sendIntent(text: String?): Intent =
        Intent(context, ShareReceiverActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .apply { if (text != null) putExtra(Intent.EXTRA_TEXT, text) }

    private fun assertNoUrlScreen_andNoService(intent: Intent) {
        ActivityScenario.launch<ShareReceiverActivity>(intent).use {
            composeRule.onNodeWithText(context.getString(R.string.fixit_title)).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.fixit_no_url), substring = true)
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.fixit_open_settings)).assertIsDisplayed()

            // The service must never appear; watch for 3 seconds.
            val appeared = pollUntil(3_000) { isDownloadServiceRunning(context) }
            assertFalse("DownloadService started for a link-less share", appeared)
        }
    }

    @Test
    fun plainTextWithoutUrl_showsNoUrl() {
        assertNoUrlScreen_andNoService(sendIntent("just some words, no link here"))
    }

    @Test
    fun emptyText_showsNoUrl() {
        assertNoUrlScreen_andNoService(sendIntent(""))
    }

    @Test
    fun missingExtraText_showsNoUrl() {
        assertNoUrlScreen_andNoService(sendIntent(null))
    }

    @Test
    fun ftpScheme_showsNoUrl() {
        assertNoUrlScreen_andNoService(sendIntent("ftp://example.com/file"))
    }

    @Test
    fun fixItIntent_showsMessage_andBackFinishes() {
        val message = "Test fix-it message"
        val scenario = ActivityScenario.launch<ShareReceiverActivity>(
            ShareReceiverActivity.fixItIntent(context, message),
        )
        scenario.use {
            composeRule.onNodeWithText(context.getString(R.string.fixit_title)).assertIsDisplayed()
            composeRule.onNodeWithText("• $message").assertIsDisplayed()
            assertFalse(isDownloadServiceRunning(context))

            composeRule.onNodeWithContentDescription(context.getString(R.string.nav_back)).performClick()
            assertTrue(
                "activity did not finish",
                pollUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED },
            )
        }
    }

    @Test
    fun fixItActionWithoutMessage_showsGenericError() {
        // Same action string fixItIntent uses, minus the message extra.
        val intent = Intent(context, ShareReceiverActivity::class.java)
            .setAction("com.v1per.sxdc.action.FIX_IT")
        ActivityScenario.launch<ShareReceiverActivity>(intent).use { scenario ->
            composeRule.onNodeWithText("• " + context.getString(R.string.error_unknown)).assertIsDisplayed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
