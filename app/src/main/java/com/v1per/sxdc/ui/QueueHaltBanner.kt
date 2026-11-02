package com.v1per.sxdc.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.v1per.sxdc.R
import com.v1per.sxdc.download.DownloadService
import com.v1per.sxdc.download.HaltKind
import com.v1per.sxdc.download.QueueHalt
import com.v1per.sxdc.ui.theme.Spacing

/**
 * Why the queue stopped, how many links are held back, and the one way to go on. Renders nothing
 * unless a halt is set and something is still waiting, so it can sit unconditionally at the top
 * of the live status panel.
 *
 * The button is the explicit "go": DownloadService clears the halt under its queue lock before
 * claiming, so the banner disappears as soon as the run actually starts.
 */
@Composable
fun QueueHaltBanner(halt: QueueHalt?, waiting: Int) {
    if (halt == null || waiting <= 0) return
    val context = LocalContext.current
    val (reason, hint) = when (halt.kind) {
        HaltKind.STORAGE -> R.string.queue_halt_storage to R.string.queue_halt_hint_storage
        HaltKind.RATE_LIMITED -> R.string.queue_halt_rate_limited to R.string.queue_halt_hint_rate_limited
        HaltKind.SETUP -> R.string.queue_halt_setup to R.string.queue_halt_hint_setup
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(
            text = stringResource(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = pluralStringResource(R.plurals.queue_halt_waiting, waiting, waiting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                // A click is a resumed activity by definition, which the foreground start needs.
                try {
                    DownloadService.resume(context, userResume = true)
                } catch (e: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException; the halt stays, so does the button.
                    Log.w("QueueHaltBanner", "Could not resume the download queue", e)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.queue_halt_resume))
        }
    }
}
