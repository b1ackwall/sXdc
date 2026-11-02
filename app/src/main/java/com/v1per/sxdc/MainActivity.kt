package com.v1per.sxdc

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v1per.sxdc.data.DownloadJob
import com.v1per.sxdc.data.DownloadStatus
import com.v1per.sxdc.download.DownloadQueue
import com.v1per.sxdc.download.DownloadService
import com.v1per.sxdc.ui.Panel
import com.v1per.sxdc.ui.SectionLabel
import com.v1per.sxdc.ui.SystemCheckPanel
import com.v1per.sxdc.ui.loadDiagnostics
import com.v1per.sxdc.ui.systemCheckCache
import com.v1per.sxdc.ui.theme.SXdcTheme
import com.v1per.sxdc.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    /**
     * The System check result; null renders the panel's "checking…" rows. Seeded from the
     * per-process cache, so a recreated activity (rotation) shows it at once and never reruns it.
     */
    private var diagnostics by mutableStateOf(systemCheckCache.peek())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The checks start Python modules and ffmpeg processes, so they run off the main thread
        // and the first frame no longer waits for them (#21). Nothing else reads the result.
        if (diagnostics == null) {
            lifecycleScope.launch {
                diagnostics = systemCheckCache.get { loadDiagnostics(applicationContext) }
            }
        }

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
                                        text = stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.headlineSmall,
                                    )
                                },
                                actions = {
                                    IconButton(
                                        onClick = { startActivity(SettingsActivity.intent(this@MainActivity)) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Settings,
                                            contentDescription = stringResource(R.string.settings_open),
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background,
                                ),
                            )
                            // The accent rule, relocated from the old in-body ScreenTitle: still the
                            // one "this is an sXdc screen" marker, now anchoring the top bar instead.
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.primary),
                            ) {}
                        }
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(Spacing.Gutter),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Item),
                    ) {
                        // Off in instrumented tests, where the system dialog would cover the screen.
                        if ((application as SxdcApp).requestsNotificationPermission) NotificationPermissionRequest()

                        SystemCheckPanel(diagnostics)
                        LiveStatusPanel()
                        HistoryPanel()
                    }
                }
            }
        }
    }

    /**
     * The other half of queue recovery.
     *
     * SxdcApp only does the bookkeeping -- requeueing rows a killed process left claimed. Actually
     * restarting the service has to happen from somewhere provably visible, because a dataSync
     * foreground service cannot be started from the background: not from Application.onCreate, and
     * not from a BOOT_COMPLETED receiver either, since dataSync is not on the exemption list. A
     * resumed Activity is exactly the state Android grants the exemption for.
     *
     * Cheap enough to run on every resume: one indexed COUNT, and nothing at all if it is zero.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                DownloadQueue.dao(this@MainActivity).countPending()
            }
            if (pending == 0) return@launch
            // The query took us off the main thread, so re-check rather than assume: the user may
            // have left in the meantime, which would put the start back in the background.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            try {
                DownloadService.resume(this@MainActivity)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException. Not worth surfacing -- the queue is
                // intact, and the next share or launch will pick it up.
                Log.w("MainActivity", "Could not resume the download queue", e)
            }
        }
    }
}

/**
 * The queue as it stands right now: the job actually downloading (or about to be), and everything
 * still behind it. Always rendered, even with nothing queued -- an empty state is still a state.
 */
@Composable
private fun LiveStatusPanel() {
    val context = LocalContext.current
    val dao = remember(Unit) { DownloadQueue.dao(context) }
    val active by dao.observeActive().collectAsStateWithLifecycle(initialValue = emptyList())

    Panel {
        SectionLabel(stringResource(R.string.main_live_status))

        if (active.isEmpty()) {
            Text(
                text = stringResource(R.string.main_live_status_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val current = active.first()
            CurrentJobRow(current)
            active.drop(1).forEach { job ->
                QueuedJobRow(job)
            }
        }
    }
}

/** The job the service is (or is about to start) working on -- the only row with a spinner. */
@Composable
private fun CurrentJobRow(job: DownloadJob) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.padding(start = Spacing.Tight)) {
            Text(
                text = job.title ?: job.url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = statusLabel(job.status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Everything behind the current job -- one row each, so the list visibly shrinks as it drains. */
@Composable
private fun QueuedJobRow(job: DownloadJob) {
    Text(
        text = job.title ?: job.url,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.Item + 16.dp),
    )
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.DOWNLOADING -> "Downloading…"
    DownloadStatus.COMPLETED -> "Completed"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.CANCELLED -> "Cancelled"
}

/** The last 10 finished jobs, newest first -- what actually happened, not what is happening. */
@Composable
private fun HistoryPanel() {
    val context = LocalContext.current
    val dao = remember(Unit) { DownloadQueue.dao(context) }
    val all by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val finished = all
        .filter {
            it.status == DownloadStatus.COMPLETED ||
                it.status == DownloadStatus.FAILED ||
                it.status == DownloadStatus.CANCELLED
        }
        .take(50)

    Panel {
        SectionLabel(stringResource(R.string.main_history))

        if (finished.isEmpty()) {
            Text(
                text = stringResource(R.string.main_history_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            finished.forEach { job -> HistoryRow(job) }
        }
    }
}

@Composable
private fun HistoryRow(job: DownloadJob) {
    val ok = job.status == DownloadStatus.COMPLETED
    val indicatorColor = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    // CANCELLED reads as a failure visually (red ✗) but its second line is a plain status word,
    // not a failure reason -- there is no failureReason to show for a job that was never attempted.
    val secondLine = when (job.status) {
        DownloadStatus.CANCELLED -> statusLabel(job.status)
        else -> job.failureReason
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = if (ok) "✓" else "✗",
            color = indicatorColor,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = Spacing.Tight),
        )
        Column {
            Text(
                text = job.title ?: job.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!ok && secondLine != null) {
                Text(
                    text = secondLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Proactively requests POST_NOTIFICATIONS once per app launch, showing a short rationale first
 * when the system says one is warranted.
 *
 * A denial is not an error state: downloads still run, they are just invisible outside Android's
 * own Task Manager, so nothing here gates any functionality.
 */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val activity = context as? Activity
    var requested by rememberSaveable { mutableStateOf(false) }
    var showRationale by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i("MainActivity", "POST_NOTIFICATIONS granted=$granted")
    }

    LaunchedEffect(Unit) {
        if (requested) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return@LaunchedEffect

        requested = true
        val needsRationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (needsRationale) {
            showRationale = true
        } else {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.notif_rationale_title)) },
            text = { Text(stringResource(R.string.notif_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.notif_rationale_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text(stringResource(R.string.notif_rationale_dismiss))
                }
            },
        )
    }
}
