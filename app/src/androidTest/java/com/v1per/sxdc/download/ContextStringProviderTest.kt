package com.v1per.sxdc.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.R
import java.util.IllegalFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import com.v1per.sxdc.test.R as TestR

/** ContextStringProvider's two branches, over strings in the test APK and one from the app (#29). */
@RunWith(AndroidJUnit4::class)
class ContextStringProviderTest {

    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun noArgs_returnsTheTextUnformatted_evenWithABarePercentSign() {
        assertEquals("100% done", ContextStringProvider(testContext).get(TestR.string.test_percent_plain))
    }

    @Test
    fun noArgs_wouldBreakIfItWentThroughFormat() {
        // Why the no-args branch must not call getString(id, *emptyArray()): that formats.
        try {
            testContext.getString(TestR.string.test_percent_plain, *emptyArray<Any>())
            fail("String.format accepted a bare '%'")
        } catch (_: IllegalFormatException) {
            // Expected.
        }
    }

    @Test
    fun withArgs_formats_includingAnEscapedPercentSign() {
        assertEquals(
            "50% of the queue",
            ContextStringProvider(testContext).get(TestR.string.test_percent_formatted, 50, "the queue"),
        )
    }

    @Test
    fun withArgs_matchesTheAppsOwnGetString() {
        val provider = ContextStringProvider(targetContext)

        assertEquals(
            targetContext.getString(R.string.notif_queue_progress, 3, 10, "Song A"),
            provider.get(R.string.notif_queue_progress, 3, 10, "Song A"),
        )
        assertEquals(targetContext.getString(R.string.error_copy_failed), provider.get(R.string.error_copy_failed))
    }
}
