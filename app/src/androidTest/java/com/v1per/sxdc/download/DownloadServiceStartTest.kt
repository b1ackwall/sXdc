package com.v1per.sxdc.download

import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.v1per.sxdc.MainActivity
import com.v1per.sxdc.TestDatabaseRule
import com.v1per.sxdc.isDownloadServiceRunning
import com.v1per.sxdc.pollUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * resume() with an empty queue. The queue is the test database, emptied by TestDatabaseRule, so
 * the service has nothing to claim. start(context, url) is never called.
 */
@RunWith(AndroidJUnit4::class)
class DownloadServiceStartTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestDatabaseRule()).around(composeRule)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun resume_withEmptyQueue_startsAndStopsItself() {
        composeRule.waitForIdle()
        assertEquals(Lifecycle.State.RESUMED, composeRule.activityRule.scenario.state)

        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

        var thrown: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                DownloadService.resume(composeRule.activity)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        assertEquals(null, thrown)

        // Phase 1: give it a moment to show up (an idle run can be over before we look).
        val seenRunning = pollUntil(3_000) { isDownloadServiceRunning(context) }
        // Phase 2: it must stop itself.
        val stopped = pollUntil(10_000) { !isDownloadServiceRunning(context) }
        Log.i(TAG, "notificationsEnabled=$notificationsEnabled seenRunning=$seenRunning stopped=$stopped")
        assertTrue("DownloadService still running 10 s after resume() on an empty queue", stopped)
    }

    private companion object {
        const val TAG = "DownloadServiceStartTest"
    }
}
