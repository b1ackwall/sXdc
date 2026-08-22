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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.v1per.sxdc.download.DownloadQueue
import com.v1per.sxdc.download.DownloadService
import com.v1per.sxdc.media.FfmpegBinaries
import com.v1per.sxdc.ui.Panel
import com.v1per.sxdc.ui.SectionLabel
import com.v1per.sxdc.ui.theme.SXdcTheme
import com.v1per.sxdc.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Python is started in SxdcApp; the share flow can reach the interpreter without this
        // activity ever running.
        val verifyModule = Python.getInstance().getModule("sxdc_verify")
        val greeting = verifyModule.callAttr("hello").toString()
        val sum = verifyModule.callAttr("add", 2, 2).toInt()
        val ytDlpVersion = verifyModule.callAttr("yt_dlp_version").toString()

        val diagnostics = try {
            val install = FfmpegBinaries.install(this)
            NativeDiagnostics.Ok(
                source = install.source.name,
                binDirPath = install.binDirPath,
                ffmpeg = ffmpegField(verifyModule, install.binDirPath, "ffmpeg"),
                ffprobe = ffmpegField(verifyModule, install.binDirPath, "ffprobe"),
                integration = integrationField(verifyModule, install.binDirPath),
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "ffmpeg install failed", e)
            NativeDiagnostics.Failed("ffmpeg install FAILED: ${e.message}")
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
                        NotificationPermissionRequest()

                        Panel {
                            SectionLabel(stringResource(R.string.main_diagnostics))

                            StatusRow(
                                label = "Chaquopy bridge",
                                value = "$greeting (2+2=$sum)",
                                ok = true,
                            )
                            StatusRow(label = "yt-dlp", value = ytDlpVersion, ok = true)

                            when (diagnostics) {
                                is NativeDiagnostics.Ok -> {
                                    StatusRow(
                                        label = diagnostics.ffmpeg.label,
                                        value = diagnostics.ffmpeg.value,
                                        ok = diagnostics.ffmpeg.ok,
                                    )
                                    StatusRow(
                                        label = diagnostics.ffprobe.label,
                                        value = diagnostics.ffprobe.value,
                                        ok = diagnostics.ffprobe.ok,
                                    )
                                    StatusRow(
                                        label = diagnostics.integration.label,
                                        value = diagnostics.integration.value,
                                        ok = diagnostics.integration.ok,
                                    )
                                    StatusRow(
                                        label = "Native install tier",
                                        value = "${diagnostics.source} @ ${diagnostics.binDirPath}",
                                        ok = true,
                                    )
                                }
                                is NativeDiagnostics.Failed -> {
                                    StatusRow(label = "Native install", value = diagnostics.message, ok = false)
                                }
                            }
                        }
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

/** One subsystem's outcome: what to label it, what to show, and whether it succeeded. */
private data class DiagField(val label: String, val value: String, val ok: Boolean)

/** Result of standing up the bundled ffmpeg/ffprobe binaries and probing them. */
private sealed class NativeDiagnostics {
    data class Ok(
        val source: String,
        val binDirPath: String,
        val ffmpeg: DiagField,
        val ffprobe: DiagField,
        val integration: DiagField,
    ) : NativeDiagnostics()

    data class Failed(val message: String) : NativeDiagnostics()
}

/** `None` round-trips through [PyObject.toString] as the literal text "None". */
private fun PyObject?.orNull(): String? = this?.toString()?.takeUnless { it == "None" }

/**
 * Reads one program's entry out of [sxdc_verify.ffmpeg_check]'s dict, called once per program so
 * the diagnostics screen can show ffmpeg and ffprobe as independent rows instead of one joined
 * string.
 */
private fun ffmpegField(verifyModule: PyObject, binDirPath: String, name: String): DiagField {
    val check = verifyModule.callAttr("ffmpeg_check", binDirPath)
    val entry = check.callAttr("get", name)
    val version = entry.callAttr("get", "version").orNull()
    val error = entry.callAttr("get", "error").orNull()
    return if (version != null) {
        DiagField(name, version, ok = true)
    } else {
        DiagField(name, error ?: "unknown error", ok = false)
    }
}

/** Reads [sxdc_verify.ytdlp_ffmpeg_check]'s result: whether yt-dlp's own discovery finds ffmpeg. */
private fun integrationField(verifyModule: PyObject, binDirPath: String): DiagField {
    val result = verifyModule.callAttr("ytdlp_ffmpeg_check", binDirPath)
    val error = result.callAttr("get", "error").orNull()
    if (error != null) {
        return DiagField("yt-dlp sees ffmpeg", "ERROR: $error", ok = false)
    }
    val available = result.callAttr("get", "available").toBoolean()
    val versions = result.callAttr("get", "versions").orNull()
    val value = if (available) "yes${versions?.let { " $it" } ?: ""}" else "NO"
    return DiagField("yt-dlp sees ffmpeg", value, ok = available)
}

/** One diagnostics line: a check/cross indicator, a label, and its value. */
@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    val indicatorColor = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = if (ok) "✓" else "✗",
            color = indicatorColor,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = Spacing.Tight),
        )
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
