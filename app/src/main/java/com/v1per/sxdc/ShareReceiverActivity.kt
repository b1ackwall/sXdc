package com.v1per.sxdc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v1per.sxdc.download.DownloadPreconditions
import com.v1per.sxdc.download.DownloadService
import com.v1per.sxdc.ui.Panel
import com.v1per.sxdc.ui.Toasts
import com.v1per.sxdc.ui.theme.SXdcTheme
import com.v1per.sxdc.ui.theme.Spacing

/**
 * Share-sheet entry point for `ACTION_SEND` / `text/plain`, and the placeholder fix-it screen.
 *
 * Kept separate from MainActivity rather than adding the filter there: MainActivity is the
 * LAUNCHER entry and owns the Task 1/2 diagnostics UI, whereas this activity is transient and
 * normally finishes within a frame of being created. It also declares `taskAffinity=""` so a
 * share from YouTube Music lands in its own task instead of being merged into (and resurfacing)
 * the sXdc main task.
 */
@OptIn(ExperimentalMaterial3Api::class)
class ShareReceiverActivity : ComponentActivity() {

    private var state by mutableStateOf<UiState>(UiState.Working)
    private var handled = false

    private sealed interface UiState {
        data object Working : UiState
        data class FixIt(val reasons: List<String>) : UiState
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SXdcTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        Column {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = stringResource(R.string.fixit_title),
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.nav_back),
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background,
                                ),
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.primary),
                            ) {}
                        }
                    },
                ) { padding ->
                    when (val s = state) {
                        is UiState.Working -> Unit
                        is UiState.FixIt -> FixItScreen(s.reasons, Modifier.padding(padding))
                    }
                }
            }
        }
    }

    /**
     * Handling runs in onResume, not onCreate, so the foreground-service start provably happens
     * while this activity is resumed and visible -- the condition Android requires for the
     * "transitions from a user-visible state" exemption. Everything on this path is synchronous
     * on the main thread; nothing is posted or dispatched.
     */
    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handled = false // re-handled by the onResume that follows
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_FIX_IT) {
            val message = intent.getStringExtra(EXTRA_MESSAGE)
            state = UiState.FixIt(listOfNotNull(message).ifEmpty { listOf(getString(R.string.error_unknown)) })
            return
        }

        val url = extractUrl(intent)
        if (url == null) {
            state = UiState.FixIt(listOf(getString(R.string.fixit_no_url)))
            return
        }

        // Acknowledged the moment there is something to act on, before the precondition check --
        // that check touches the filesystem and the SAF grant table, and the share sheet is
        // already gone by then, so without this the tap has no visible effect at all. The service
        // replaces this line with the queue outcome once its duplicate check has run.
        Toasts.showReplacing(this, getString(R.string.toast_link_received))

        when (val pre = DownloadPreconditions.check(this)) {
            // No toast here: the fix-it screen below is the feedback for this path.
            is DownloadPreconditions.Result.Failed -> state = UiState.FixIt(pre.reasons)

            is DownloadPreconditions.Result.Ok -> {
                DownloadService.start(this, url)
                finish()
            }
        }
    }

    /**
     * Shared text is rarely a bare URL -- "Check this out: https://youtu.be/x" is typical -- so
     * pull the first web URL out of the payload rather than trusting the whole string.
     */
    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return null

        val matcher = Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val candidate = text.substring(matcher.start(), matcher.end())
            if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) {
                return candidate
            }
        }
        return null
    }

    companion object {
        private const val ACTION_FIX_IT = "com.v1per.sxdc.action.FIX_IT"
        private const val EXTRA_MESSAGE = "com.v1per.sxdc.extra.MESSAGE"

        /** Intent used by the failure notification to open this screen with the error text. */
        fun fixItIntent(context: Context, message: String): Intent =
            Intent(context, ShareReceiverActivity::class.java)
                .setAction(ACTION_FIX_IT)
                .putExtra(EXTRA_MESSAGE, message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/**
 * Why the share could not proceed, plus the one action that resolves most of these reasons.
 *
 * Settings is offered unconditionally rather than only for folder-related reasons: it is where
 * every user-fixable precondition is corrected, and matching on message text to decide whether to
 * show a button would break the moment a reason is reworded.
 */
@Composable
private fun FixItScreen(reasons: List<String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.Item),
    ) {
        // Error-outlined rather than error-filled: red marks the panel as the problem without
        // making the reasons inside it hard to read.
        Panel(borderColor = MaterialTheme.colorScheme.error) {
            reasons.forEach { reason ->
                Text(text = "• $reason", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Button(
            onClick = { context.startActivity(SettingsActivity.intent(context)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.fixit_open_settings))
        }
        Text(
            text = stringResource(R.string.fixit_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
