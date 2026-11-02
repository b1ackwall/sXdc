package com.v1per.sxdc.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.sxdc.R
import com.v1per.sxdc.ui.theme.SXdcTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Renders the panel directly, so no Python or ffmpeg runs. */
@RunWith(AndroidJUnit4::class)
class SystemCheckPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pendingState_showsEveryRowAsChecking() {
        composeRule.setContent { SXdcTheme { SystemCheckPanel(SystemCheckState.Pending) } }

        val pending = context.getString(R.string.check_pending)
        listOf(
            R.string.check_bridge, R.string.check_ytdlp, R.string.check_ffmpeg, R.string.check_ffprobe,
            R.string.check_integration, R.string.check_native_tier,
        ).forEach { label ->
            composeRule.onNodeWithText("${context.getString(label)}: $pending").assertIsDisplayed()
        }
        // A neutral mark per row: neither the success tick nor the failure cross.
        composeRule.onAllNodesWithText("○").assertCountEquals(6)
        composeRule.onAllNodesWithText("✓").assertCountEquals(0)
        composeRule.onAllNodesWithText("✗").assertCountEquals(0)
    }

    @Test
    fun failedInstall_showsOneNativeInstallRow() {
        val state = Diagnostics(
            greeting = "Chaquopy OK",
            sum = 4,
            ytDlpVersion = "2025.01.01",
            native = NativeDiagnostics.Failed("ffmpeg install FAILED: boom"),
        )
        composeRule.setContent { SXdcTheme { SystemCheckPanel(SystemCheckState.Loaded(state)) } }

        val bridge = context.getString(R.string.check_bridge)
        val nativeInstall = context.getString(R.string.check_native_install)
        composeRule.onNodeWithText("$bridge: Chaquopy OK (2+2=4)").assertIsDisplayed()
        composeRule.onNodeWithText("$nativeInstall: ffmpeg install FAILED: boom").assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.check_pending), substring = true).assertCountEquals(0)
    }

    @Test
    fun failedLoad_showsOneFailureRow_andFiveNotCheckedRows() {
        val message = "PyException: ModuleNotFoundError: No module named 'yt_dlp'"
        composeRule.setContent { SXdcTheme { SystemCheckPanel(SystemCheckState.Failed(message)) } }

        val bridge = context.getString(R.string.check_bridge)
        composeRule.onNodeWithText("$bridge: ${context.getString(R.string.check_failed, message)}").assertIsDisplayed()
        val notChecked = context.getString(R.string.check_not_checked)
        listOf(
            R.string.check_ytdlp, R.string.check_ffmpeg, R.string.check_ffprobe,
            R.string.check_integration, R.string.check_native_tier,
        ).forEach { label ->
            composeRule.onNodeWithText("${context.getString(label)}: $notChecked").assertIsDisplayed()
        }
        composeRule.onAllNodesWithText("✗").assertCountEquals(1)
        composeRule.onAllNodesWithText("○").assertCountEquals(5)
        composeRule.onAllNodesWithText("✓").assertCountEquals(0)
        composeRule.onAllNodesWithText(context.getString(R.string.check_pending), substring = true).assertCountEquals(0)
    }
}
